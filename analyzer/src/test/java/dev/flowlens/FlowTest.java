package dev.flowlens;

import dev.flowlens.model.Edge;
import dev.flowlens.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B2, B3, B10, B12, B13, E4, E5: how calls are followed. */
class FlowTest {

    @TempDir Path dir;

    private TestProject base() throws Exception {
        return new TestProject(dir)
                .java("demo/Account.java", """
                        package demo;
                        import jakarta.persistence.*;
                        @Entity @Table(name = "accounts")
                        public class Account { @Id Long id; String email; String status;
                            public void setEmail(String e) { email = e; } public void setStatus(String s) { status = s; } }
                        """)
                .java("demo/AccountRepository.java", """
                        package demo;
                        public interface AccountRepository extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {
                            boolean existsByEmail(String email);
                        }
                        """);
    }

    @Test
    void interfaceCallsReachTheImplementation_B2() throws Exception {
        TestProject.Result r = base()
                .java("demo/AccountService.java", "package demo; public interface AccountService { void create(String email); }")
                .java("demo/AccountServiceImpl.java", """
                        package demo;
                        @org.springframework.stereotype.Service
                        public class AccountServiceImpl implements AccountService {
                            private final AccountRepository repo;
                            AccountServiceImpl(AccountRepository repo) { this.repo = repo; }
                            @Override public void create(String email) { Account a = new Account(); a.setEmail(email); repo.save(a); }
                        }
                        """)
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C {
                            private final AccountService service;
                            C(AccountService s) { service = s; }
                            @org.springframework.web.bind.annotation.PostMapping("/accounts") public void create(String email) { service.create(email); }
                        }
                        """)
                .analyze();
        assertEquals(List.of("AccountServiceImpl.create()", "UPSERT accounts"), r.flowOf("POST /accounts"));
        assertEquals("service", r.node("AccountServiceImpl.create()").layer);
    }

    @Test
    void qualifierAndPrimaryPickTheImplementation_multipleImplsAreMedium_E5() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/Notifier.java", "package demo; public interface Notifier { void send(String m); }")
                .java("demo/EmailNotifier.java", "package demo; @org.springframework.stereotype.Service(\"email\") public class EmailNotifier implements Notifier { public void send(String m) {} }")
                .java("demo/SmsNotifier.java", "package demo; @org.springframework.stereotype.Service @org.springframework.context.annotation.Primary public class SmsNotifier implements Notifier { public void send(String m) {} }")
                .java("demo/PushNotifier.java", "package demo; @org.springframework.stereotype.Service public class PushNotifier implements Notifier { public void send(String m) {} }")
                .java("demo/C.java", """
                        package demo;
                        import org.springframework.beans.factory.annotation.Qualifier;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class C {
                            @Qualifier("email") private final Notifier byQualifier;
                            private final Notifier byPrimary;
                            C(@Qualifier("email") Notifier a, Notifier b) { byQualifier = a; byPrimary = b; }
                            @GetMapping("/q") public void q() { byQualifier.send("x"); }
                            @GetMapping("/p") public void p() { byPrimary.send("x"); }
                        }
                        """)
                .analyze();
        assertEquals(List.of("EmailNotifier.send()"), r.flowOf("GET /q"));
        assertEquals(List.of("SmsNotifier.send()"), r.flowOf("GET /p"));

        TestProject.Result ambiguous = new TestProject(dir.resolve("b"))
                .java("demo/Notifier.java", "package demo; public interface Notifier { void send(String m); }")
                .java("demo/A.java", "package demo; @org.springframework.stereotype.Service public class A implements Notifier { public void send(String m) {} }")
                .java("demo/B.java", "package demo; @org.springframework.stereotype.Service public class B implements Notifier { public void send(String m) {} }")
                .java("demo/C.java", "package demo; @org.springframework.web.bind.annotation.RestController public class C { Notifier n; @org.springframework.web.bind.annotation.GetMapping(\"/x\") public void x() { n.send(\"\"); } }")
                .analyze();
        assertEquals(List.of("A.send()", "B.send()"), ambiguous.flowOf("GET /x"));
        for (Edge e : ambiguous.edgesTo("A.send()")) assertEquals("medium", e.confidence);
    }

    @Test
    void detailsAreNotDuplicatedAcrossEndpoints_B3() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/S.java", "package demo; @org.springframework.stereotype.Service public class S { public void go(int n) { if (n > 0) { n = 1; } } }")
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C { S s;
                            @org.springframework.web.bind.annotation.GetMapping("/a") public void a() { s.go(1); }
                            @org.springframework.web.bind.annotation.GetMapping("/b") public void b() { s.go(2); }
                            @org.springframework.web.bind.annotation.GetMapping("/c") public void c() { s.go(3); }
                        }
                        """)
                .analyze();
        Node go = r.node("S.go()");
        assertEquals(List.of("if (n > 0)"), go.conditions);
        assertEquals(List.of("n = 1"), go.mutations);
    }

    @Test
    void varLocalsAndChainedCallsResolve_B10() throws Exception {
        TestProject.Result r = base()
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C {
                            private final AccountRepository repo;
                            C(AccountRepository r) { repo = r; }
                            @org.springframework.web.bind.annotation.DeleteMapping("/a") public void a() { var r = repo; r.deleteAll(); }
                            @org.springframework.web.bind.annotation.GetMapping("/b") public void b(Long id) {
                                var acc = repo.findById(id).orElseThrow();
                                for (var x : repo.findAll()) { repo.delete(x); }
                            }
                        }
                        """)
                .analyze();
        assertEquals(List.of("DELETE accounts"), r.flowOf("DELETE /a"));
        r.assertFlowContains("GET /b", "SELECT accounts", "DELETE accounts");
        assertTrue(r.endpoint("DELETE /a").unresolved.isEmpty());
    }

    @Test
    void customBaseRepositoriesAreRepositories_B12() throws Exception {
        TestProject.Result r = base()
                .java("demo/BaseRepo.java", """
                        package demo;
                        @org.springframework.data.repository.NoRepositoryBean
                        public interface BaseRepo<T, ID> extends org.springframework.data.jpa.repository.JpaRepository<T, ID> {}
                        """)
                .java("demo/AccountRepo2.java", "package demo; public interface AccountRepo2 extends BaseRepo<Account, Long> { java.util.List<Account> findByStatus(String s); }")
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C { AccountRepo2 repo;
                            @org.springframework.web.bind.annotation.GetMapping("/s") public Object s() { return repo.findByStatus("A"); }
                        }
                        """)
                .analyze();
        assertEquals(List.of("SELECT accounts"), r.flowOf("GET /s"));
    }

    @Test
    void sameSimpleNameInTwoPackagesResolvesThroughImports_B13() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("a/Helper.java", "package a; @org.springframework.stereotype.Service public class Helper { public void run() {} }")
                .java("b/Helper.java", "package b; @org.springframework.stereotype.Service public class Helper { public void run() {} }")
                .java("c/C.java", """
                        package c;
                        import b.Helper;
                        @org.springframework.web.bind.annotation.RestController
                        public class C { Helper h; @org.springframework.web.bind.annotation.GetMapping("/h") public void x() { h.run(); } }
                        """)
                .analyze();
        Node ep = r.endpoint("GET /h");
        String target = r.graph.edges.stream().filter(e -> e.source.equals(ep.id)).findFirst().orElseThrow().target;
        assertTrue(target.startsWith("M:b.Helper#"), target);
    }

    @Test
    void branchesAndThrowsAreGraphNodes_E4() throws Exception {
        TestProject.Result r = base()
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C {
                            AccountRepository repo;
                            @org.springframework.web.bind.annotation.PostMapping("/x")
                            public void x(String email, boolean vip) {
                                if (repo.existsByEmail(email)) {
                                    throw new IllegalStateException("taken");
                                }
                                Account a = new Account();
                                if (vip) { repo.save(a); } else { repo.delete(a); }
                            }
                        }
                        """)
                .analyze();
        Node ifVip = r.node("if (vip)");
        assertEquals("branch", ifVip.type);
        Node ifExists = r.node("if (repo.existsByEmail(email))");
        // the existence check runs unconditionally: it hangs off the endpoint, not off a branch
        Node ep = r.endpoint("POST /x");
        Node select = r.node("SELECT accounts");
        assertTrue(r.graph.edges.stream().anyMatch(e -> e.source.equals(ep.id) && e.target.equals(select.id)));
        assertTrue(r.graph.edges.stream().anyMatch(e -> e.source.equals(ifExists.id) && e.label.startsWith("then")
                && r.graph.get(e.target).label.equals("throw IllegalStateException")));
        assertTrue(r.graph.edges.stream().anyMatch(e -> e.source.equals(ifVip.id) && e.label.equals("then · AccountRepository.save()")));
        assertTrue(r.graph.edges.stream().anyMatch(e -> e.source.equals(ifVip.id) && e.label.equals("else · AccountRepository.delete()")));
    }

    @Test
    void publishedEventsReachListeners() throws Exception {
        TestProject.Result r = base()
                .java("demo/AccountCreated.java", "package demo; public record AccountCreated(Long id) {}")
                .java("demo/Mailer.java", """
                        package demo;
                        @org.springframework.stereotype.Component
                        public class Mailer {
                            AccountRepository repo;
                            @org.springframework.context.event.EventListener public void on(AccountCreated e) { repo.findById(e.id()); }
                        }
                        """)
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C {
                            org.springframework.context.ApplicationEventPublisher events;
                            @org.springframework.web.bind.annotation.PostMapping("/e") public void e() { events.publishEvent(new AccountCreated(1L)); }
                        }
                        """)
                .analyze();
        assertEquals(List.of("Mailer.on()", "SELECT accounts"), r.flowOf("POST /e"));
    }

    @Test
    void unresolvedCallsAreListed_E5() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C {
                            @org.springframework.web.bind.annotation.GetMapping("/u")
                            public void u() { mystery.doThing(); }
                        }
                        """)
                .analyze();
        assertEquals(List.of("mystery.doThing()"), r.endpoint("GET /u").unresolved);
    }

    @Test
    void myBatisGeneratorQueryBuildersAreNotFlowSteps() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/OrderExample.java", """
                        package demo;
                        public class OrderExample {
                            public Criteria createCriteria() { return new Criteria(); }
                            public static class GeneratedCriteria { public Criteria andIdEqualTo(Long id) { return null; } }
                            public static class Criteria extends GeneratedCriteria {}
                        }
                        """)
                .java("demo/OrderMapper.java", "package demo; @org.apache.ibatis.annotations.Mapper public interface OrderMapper { java.util.List<Object> selectByExample(OrderExample e); }")
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C { OrderMapper mapper;
                            @org.springframework.web.bind.annotation.GetMapping("/o") public Object o() {
                                OrderExample example = new OrderExample();
                                example.createCriteria().andIdEqualTo(1L);
                                return mapper.selectByExample(example);
                            }
                        }
                        """)
                .analyze();
        assertEquals(List.of("SELECT order"), r.flowOf("GET /o"));
    }
}

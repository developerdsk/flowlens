package dev.flowlens;

import dev.flowlens.model.Graph;
import dev.flowlens.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for findings from the PR #3 review. */
class ReviewFindingsTest {

    @TempDir Path dir;

    @Test
    void mapStructMappersAreNotDatabases() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/UserMapper.java", """
                        package demo;
                        import org.mapstruct.Mapper;
                        @Mapper(componentModel = "spring")
                        public interface UserMapper { String toDto(Object u); }
                        """)
                .java("demo/C.java", "package demo; @org.springframework.web.bind.annotation.RestController public class C { UserMapper m; "
                        + "@org.springframework.web.bind.annotation.GetMapping(\"/u\") public Object u() { return m.toDto(null); } }")
                .analyze();
        assertTrue(r.sinks().isEmpty(), () -> "unexpected sinks " + r.sinkLabels());
    }

    @Test
    void sameClassNameInTwoModulesKeepsBoth() throws Exception {
        String ctl = """
                package com.ex.api;
                @org.springframework.web.bind.annotation.RestController
                @org.springframework.web.bind.annotation.RequestMapping("/%s")
                public class HealthController {
                    HealthService s;
                    @org.springframework.web.bind.annotation.GetMapping("/health") public String health() { return s.check(); }
                }
                """;
        String svc = """
                package com.ex.api;
                @org.springframework.stereotype.Service
                public class HealthService { %s repo; public String check() { repo.count(); return "ok"; } }
                """;
        TestProject.Result r = new TestProject(dir)
                .file("pom.xml", "<project/>")
                .file("svc-a/pom.xml", "<project/>")
                .file("svc-b/pom.xml", "<project/>")
                .file("svc-a/src/main/java/com/ex/api/HealthController.java", ctl.formatted("a"))
                .file("svc-b/src/main/java/com/ex/api/HealthController.java", ctl.formatted("b"))
                .file("svc-a/src/main/java/com/ex/api/HealthService.java", svc.formatted("ARepo"))
                .file("svc-b/src/main/java/com/ex/api/HealthService.java", svc.formatted("BRepo"))
                .file("svc-a/src/main/java/com/ex/api/ARepo.java", "package com.ex.api; public interface ARepo extends org.springframework.data.jpa.repository.JpaRepository<A, Long> {}")
                .file("svc-b/src/main/java/com/ex/api/BRepo.java", "package com.ex.api; public interface BRepo extends org.springframework.data.jpa.repository.JpaRepository<B, Long> {}")
                .file("svc-a/src/main/java/com/ex/api/A.java", "package com.ex.api; @jakarta.persistence.Entity public class A { @jakarta.persistence.Id Long id; }")
                .file("svc-b/src/main/java/com/ex/api/B.java", "package com.ex.api; @jakarta.persistence.Entity public class B { @jakarta.persistence.Id Long id; }")
                .analyze();
        assertEquals(List.of("GET /a/health", "GET /b/health"), r.https());
        assertEquals(List.of("HealthService.check()", "SELECT a"), r.flowOf("GET /a/health"));
        assertEquals(List.of("HealthService.check()", "SELECT b"), r.flowOf("GET /b/health"));
    }

    @Test
    void branchIdsDoNotDependOnLineNumbers() throws Exception {
        String svc = """
                package demo;
                @org.springframework.stereotype.Service
                public class S {
                %s
                    public void run(int n) { if (n > 0) { throw new IllegalStateException(); } }
                }
                """;
        String ctl = "package demo; @org.springframework.web.bind.annotation.RestController public class C { S s; "
                + "@org.springframework.web.bind.annotation.GetMapping(\"/r\") public void r() { s.run(1); } }";
        Graph base = new TestProject(dir.resolve("a")).java("demo/S.java", svc.formatted("")).java("demo/C.java", ctl).analyze().graph;
        Graph head = new TestProject(dir.resolve("b")).java("demo/S.java", svc.formatted("    private void helper() {}\n    private void other() {}\n"))
                .java("demo/C.java", ctl).analyze().graph;
        Graph d = GraphDiff.diff(base, head, "a");
        List<String> changed = d.nodes.stream().filter(n -> n.diff != null).map(n -> n.label + ":" + n.diff).toList();
        assertEquals(List.of(), changed, () -> d.edges.stream().filter(e -> e.diff != null).map(e -> e.key() + " " + e.diff).toList().toString());
    }

    @Test
    void inheritedOverloadedMappingsMatchBySignature() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/Api.java", """
                        package demo;
                        import org.springframework.web.bind.annotation.*;
                        public interface Api {
                            @GetMapping("/a") String get(String id);
                            @GetMapping("/b") String get(Long id);
                        }
                        """)
                .java("demo/C.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        public class C implements Api {
                            public String get(String id) { return ""; }
                            public String get(Long id) { return ""; }
                        }
                        """)
                .analyze();
        assertEquals(List.of("GET /a", "GET /b"), r.https());
    }

    @Test
    void sameNamedLibraryMethodWithOtherArityIsNotLinked() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/OrderService.java", """
                        package demo;
                        @org.springframework.stereotype.Service
                        public class OrderService extends com.baomidou.ServiceImpl<Object, Object> {
                            public void save(Object o, Object u) { audit(); }
                            void audit() {}
                        }
                        """)
                .java("demo/C.java", "package demo; @org.springframework.web.bind.annotation.RestController public class C { OrderService s; "
                        + "@org.springframework.web.bind.annotation.PostMapping(\"/o\") public void o() { s.save(new Object()); } }")
                .analyze();
        assertEquals(List.of(), r.flowOf("POST /o"));
    }

    @Test
    void ambiguousEndpointQueriesAndExactTableMatches() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/User.java", "package demo; @jakarta.persistence.Entity public class User { @jakarta.persistence.Id Long id; }")
                .java("demo/UserRole.java", "package demo; @jakarta.persistence.Entity public class UserRole { @jakarta.persistence.Id Long id; }")
                .java("demo/UserRepo.java", "package demo; public interface UserRepo extends org.springframework.data.jpa.repository.JpaRepository<User, Long> {}")
                .java("demo/RoleRepo.java", "package demo; public interface RoleRepo extends org.springframework.data.jpa.repository.JpaRepository<UserRole, Long> {}")
                .java("demo/C.java", """
                        package demo;
                        import org.springframework.web.bind.annotation.*;
                        @RestController @RequestMapping("/accounts")
                        public class C { UserRepo users; RoleRepo roles;
                            @GetMapping public Object list() { return users.findAll(); }
                            @PostMapping public Object create() { return roles.findAll(); }
                        }
                        """)
                .analyze();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> r.query.findEndpoint("accounts"));
        assertTrue(e.getMessage().contains("GET /accounts") && e.getMessage().contains("POST /accounts"));
        assertEquals("GET /accounts", r.query.findEndpoint("GET /accounts").http);
        assertEquals(java.util.Set.of("user"), r.query.tableAccess("user").keySet());
    }

    @Test
    void kafkaSendOfARecordVariableHasUnknownTopic() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/C.java", "package demo; @org.springframework.web.bind.annotation.RestController public class C { "
                        + "org.springframework.kafka.core.KafkaTemplate<String, String> kafka; "
                        + "@org.springframework.web.bind.annotation.PostMapping(\"/k\") public void k(org.apache.kafka.clients.producer.ProducerRecord<String, String> rec) { kafka.send(rec); } }")
                .analyze();
        Node n = r.node("KAFKA → ?");
        assertEquals("low", n.confidence);
    }
}

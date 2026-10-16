package dev.flowlens;

import dev.flowlens.model.Lineage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** E6: request field -> column. */
class LineageTest {

    @TempDir Path dir;

    @Test
    void requestFieldsFlowToColumnsThroughServices() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/Account.java", """
                        package demo;
                        import jakarta.persistence.*;
                        @Entity @Table(name = "accounts")
                        public class Account {
                            @Id Long id;
                            @Column(name = "email_address") String email;
                            String displayName;
                            public void setEmail(String e) { email = e; }
                            public void setDisplayName(String n) { displayName = n; }
                        }
                        """)
                .java("demo/Repo.java", "package demo; public interface Repo extends org.springframework.data.jpa.repository.JpaRepository<Account, Long> {}")
                .java("demo/CreateRequest.java", "package demo; public record CreateRequest(String email, Profile profile) {}")
                .java("demo/Profile.java", "package demo; public class Profile { String name; public String getName() { return name; } }")
                .java("demo/Service.java", """
                        package demo;
                        @org.springframework.stereotype.Service
                        public class Service {
                            Repo repo;
                            public Account create(CreateRequest req) {
                                Account a = new Account();
                                a.setEmail(req.email().trim());
                                a.setDisplayName(req.profile().getName());
                                return repo.save(a);
                            }
                        }
                        """)
                .java("demo/C.java", """
                        package demo;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        public class C {
                            Service service;
                            @PostMapping("/accounts") public Account create(@RequestBody CreateRequest request) { return service.create(request); }
                        }
                        """)
                .analyze();
        List<Lineage> lineage = r.endpoint("POST /accounts").lineage;
        assertTrue(lineage.contains(new Lineage("request.email", "accounts.email_address", null)), lineage.toString());
        assertTrue(lineage.contains(new Lineage("request.profile.name", "accounts.display_name", null)), lineage.toString());
        assertEquals("Service.create()", lineage.get(0).via);
    }
}

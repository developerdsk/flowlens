package dev.flowlens;

import dev.flowlens.model.Diagnostic;
import dev.flowlens.source.AnalysisOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** B1, B4, B6, B15, E1: what gets parsed, skipped and reported. */
class ParsingAndSourcesTest {

    @TempDir Path dir;

    static final String CONTROLLER = """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class C {
                private final Repo repo;
                C(Repo repo) { this.repo = repo; }
                @PostMapping("/purge") public void purge(@RequestBody Req r) { repo.purge(r.id()); }
            }
            """;

    @Test
    void modernJavaSyntaxIsParsed_B1() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/C.java", CONTROLLER)
                .java("demo/Req.java", "package demo; public record Req(Long id) {}")
                .java("demo/Ent.java", "package demo; @jakarta.persistence.Entity public class Ent { @jakarta.persistence.Id Long id; }")
                .java("demo/Repo.java", """
                        package demo;
                        import org.springframework.data.jpa.repository.*;
                        public interface Repo extends JpaRepository<Ent, Long> {
                            @Query(\"""
                                delete from Ent e
                                where e.id = :id
                                \""")
                            void purge(Long id);
                        }
                        """)
                .java("demo/Sealed.java", """
                        package demo;
                        public sealed interface Sealed permits Sealed.A {
                            record A(int x) implements Sealed {}
                            default String kind() { return switch (this) { case A a -> "a"; }; }
                        }
                        """)
                .analyze();
        assertEquals(0, r.graph.stats.filesSkipped, () -> "skipped: " + r.graph.diagnostics.stream().map(d -> d.file + " " + d.message).toList());
        assertEquals(java.util.List.of("DELETE ent"), r.flowOf("POST /purge"));
    }

    @Test
    void nonUtf8FilesAreDecodedNotSkipped_B6() throws Exception {
        byte[] latin1 = "package demo;\n// café über\n@org.springframework.web.bind.annotation.RestController public class L { @org.springframework.web.bind.annotation.GetMapping(\"/l\") public String l() { return \"é\"; } }\n"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        TestProject.Result r = new TestProject(dir).bytes("src/main/java/demo/L.java", latin1).analyze();
        assertEquals(java.util.List.of("GET /l"), r.https());
        assertTrue(r.graph.diagnostics.stream().anyMatch(d -> d.message.contains("decoded as")));
    }

    @Test
    void parseErrorsAreReportedInGraph_B15() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/Broken.java", "package demo; public class Broken { void x( { }")
                .java("demo/C.java", "package demo; @org.springframework.web.bind.annotation.RestController class C { @org.springframework.web.bind.annotation.GetMapping(\"/ok\") void ok() {} }")
                .analyze();
        assertEquals(1, r.graph.stats.filesSkipped);
        Diagnostic d = r.graph.diagnostics.stream().filter(x -> "warning".equals(x.severity)).findFirst().orElseThrow();
        assertEquals("src/main/java/demo/Broken.java", d.file);
        assertTrue(d.message.startsWith("skipped"));
        assertEquals(java.util.List.of("GET /ok"), r.https());
    }

    @Test
    void buildOutputIsSkippedButRealPackagesNamedBuildAreNot_B4() throws Exception {
        String ctl = "package demo; @org.springframework.web.bind.annotation.RestController class C { @org.springframework.web.bind.annotation.GetMapping(\"/real\") void x() {} }";
        String copy = ctl.replace("/real", "/copy");
        TestProject.Result r = new TestProject(dir)
                .java("demo/C.java", ctl)
                .file("target/gen/demo/C.java", copy)
                .file("build/tmp/demo/C.java", copy)
                .file("node_modules/x/C.java", copy)
                .file(".idea/C.java", copy)
                .java("demo/build/B.java", "package demo.build; @org.springframework.web.bind.annotation.RestController class B { @org.springframework.web.bind.annotation.GetMapping(\"/b\") void b() {} }")
                .analyze();
        assertEquals(java.util.List.of("GET /b", "GET /real"), r.https());
        assertEquals("src/main/java/demo/C.java", r.endpoint("GET /real").file);
    }

    @Test
    void generatedSourcesAreReadButHandWrittenWins() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/UsersController.java", """
                        package demo;
                        @org.springframework.web.bind.annotation.RestController
                        class UsersController implements UsersApi { public String getUser(Long id) { return "x"; } }
                        """)
                .file("target/generated-sources/openapi/src/main/java/demo/UsersApi.java", """
                        package demo;
                        import org.springframework.web.bind.annotation.*;
                        @RequestMapping("/v1")
                        public interface UsersApi { @GetMapping("/users/{id}") String getUser(@PathVariable Long id); }
                        """)
                .analyze();
        assertEquals(java.util.List.of("GET /v1/users/{id}"), r.https());
    }

    @Test
    void testSourcesOnlyWhenAsked() throws Exception {
        TestProject p = new TestProject(dir)
                .java("demo/C.java", "package demo; @org.springframework.web.bind.annotation.RestController class C { @org.springframework.web.bind.annotation.GetMapping(\"/main\") void x() {} }")
                .file("src/test/java/demo/T.java", "package demo; @org.springframework.web.bind.annotation.RestController class T { @org.springframework.web.bind.annotation.GetMapping(\"/test\") void x() {} }");
        assertEquals(java.util.List.of("GET /main"), p.analyze().https());
        AnalysisOptions o = new AnalysisOptions();
        o.includeTests = true;
        assertEquals(java.util.List.of("GET /main", "GET /test"), p.analyze(o).https());
    }

    @Test
    void multiModuleProjectsListAndFilterModules_E1() throws Exception {
        TestProject p = new TestProject(dir)
                .file("pom.xml", "<project/>")
                .file("orders/pom.xml", "<project/>")
                .file("billing/build.gradle", "")
                .file("orders/src/main/java/o/O.java", "package o; @org.springframework.web.bind.annotation.RestController class O { @org.springframework.web.bind.annotation.GetMapping(\"/orders\") void x() {} }")
                .file("billing/src/main/java/b/B.java", "package b; @org.springframework.web.bind.annotation.RestController class B { @org.springframework.web.bind.annotation.GetMapping(\"/bills\") void x() {} }");
        TestProject.Result all = p.analyze();
        assertEquals(java.util.List.of("billing", "orders"), all.graph.modules);
        assertEquals("orders", all.endpoint("GET /orders").module);
        AnalysisOptions o = new AnalysisOptions();
        o.modules.add("billing");
        assertEquals(java.util.List.of("GET /bills"), p.analyze(o).https());
    }
}

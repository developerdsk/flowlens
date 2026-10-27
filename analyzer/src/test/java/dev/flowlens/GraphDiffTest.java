package dev.flowlens;

import dev.flowlens.model.Graph;
import dev.flowlens.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GraphDiffTest {

    @TempDir Path dir;

    @Test
    void marksAddedRemovedChangedAndAffectedEndpoints() throws Exception {
        String svcV1 = "package demo; @org.springframework.stereotype.Service public class S { public void a() {} public void old() {} }";
        String svcV2 = "package demo; @org.springframework.stereotype.Service public class S { public void a() { int x = 1; } public void fresh() {} }";
        String ctl1 = "package demo; @org.springframework.web.bind.annotation.RestController public class C { S s; "
                + "@org.springframework.web.bind.annotation.GetMapping(\"/a\") public void a() { s.a(); } "
                + "@org.springframework.web.bind.annotation.GetMapping(\"/b\") public void b() { s.old(); } "
                + "@org.springframework.web.bind.annotation.GetMapping(\"/c\") public void c() {} }";
        String ctl2 = ctl1.replace("s.old()", "s.fresh()");
        Graph base = new TestProject(dir.resolve("v1")).java("demo/S.java", svcV1).java("demo/C.java", ctl1).analyze().graph;
        Graph head = new TestProject(dir.resolve("v2")).java("demo/S.java", svcV2).java("demo/C.java", ctl2).analyze().graph;
        Graph d = GraphDiff.diff(base, head, "v1");
        TestProject.Result r = new TestProject.Result(d);
        assertEquals("changed", r.node("S.a()").diff);
        assertEquals("added", r.node("S.fresh()").diff);
        assertEquals("removed", r.node("S.old()").diff);
        assertEquals("affected", r.endpoint("GET /a").diff);
        assertNotNull(r.endpoint("GET /b").diff);
        assertNull(r.endpoint("GET /c").diff);
        Node removed = r.node("S.old()");
        assertTrue(d.edges.stream().anyMatch(e -> e.target.equals(removed.id) && "removed".equals(e.diff)
                && Integer.valueOf(1).equals(e.line) && Integer.valueOf(1).equals(e.seq)));
    }
}

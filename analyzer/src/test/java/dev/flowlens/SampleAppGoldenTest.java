package dev.flowlens;

import dev.flowlens.model.Edge;
import dev.flowlens.model.Graph;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden snapshot of the sample app's flow. If an intended analyzer change alters it, regenerate:
 * {@code ./mvnw test -Dtest=SampleAppGoldenTest -Dflowlens.updateGolden=true}
 */
class SampleAppGoldenTest {

    @Test
    void sampleAppFlowMatchesGolden() throws Exception {
        Path sample = Path.of("../examples/sample-app");
        Graph g = new FlowAnalyzer().analyze(sample);
        List<String> lines = new ArrayList<>();
        for (Edge e : g.edges) {
            lines.add(g.get(e.source).label + " --[" + e.label + "]--> " + g.get(e.target).label);
        }
        g.nodes.stream().filter(n -> "endpoint".equals(n.type)).forEach(n -> {
            lines.add("ENDPOINT " + n.http + " " + n.file + ":" + n.line);
            n.lineage.forEach(l -> lines.add("  LINEAGE " + l.from + " -> " + l.to));
        });
        lines.sort(String::compareTo);
        String actual = String.join("\n", lines) + "\n";
        Path golden = Path.of("src/test/resources/golden/sample-app.txt");
        if (Boolean.getBoolean("flowlens.updateGolden") || !Files.exists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
        }
        // git may check the golden file out with CRLF line endings on Windows
        assertEquals(Files.readString(golden, StandardCharsets.UTF_8).replace("\r\n", "\n"), actual);
    }
}

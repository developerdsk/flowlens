package dev.flowlens;

import dev.flowlens.model.Edge;
import dev.flowlens.model.Graph;
import dev.flowlens.model.Node;
import dev.flowlens.source.AnalysisOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Builds a throwaway project on disk and queries the resulting graph. */
final class TestProject {
    final Path root;

    TestProject(Path root) {
        this.root = root;
    }

    TestProject file(String path, String content) throws IOException {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return this;
    }

    TestProject bytes(String path, byte[] content) throws IOException {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.write(p, content);
        return this;
    }

    /** A Java file in src/main/java, package derived from the path. */
    TestProject java(String fqcnPath, String body) throws IOException {
        return file("src/main/java/" + fqcnPath, body);
    }

    Result analyze() throws IOException {
        return analyze(new AnalysisOptions());
    }

    Result analyze(AnalysisOptions options) throws IOException {
        return new Result(new FlowAnalyzer().analyze(root, options, FlowAnalyzer.Progress.NONE, null));
    }

    static final class Result {
        final Graph graph;
        final GraphQuery query;

        Result(Graph graph) {
            this.graph = graph;
            this.query = new GraphQuery(graph);
        }

        List<Node> endpoints() {
            return graph.nodes.stream().filter(n -> "endpoint".equals(n.type)).toList();
        }

        List<String> https() {
            return endpoints().stream().map(n -> n.http).sorted().toList();
        }

        Node endpoint(String http) {
            return endpoints().stream().filter(n -> http.equals(n.http)).findFirst()
                    .orElseGet(() -> fail("no endpoint " + http + " in " + https()));
        }

        Optional<Node> byLabel(String label) {
            return graph.nodes.stream().filter(n -> label.equals(n.label)).findFirst();
        }

        Node node(String label) {
            return byLabel(label).orElseGet(() -> fail("no node '" + label + "' in "
                    + graph.nodes.stream().map(n -> n.label).collect(Collectors.joining(", "))));
        }

        /** Labels of all nodes reachable from the endpoint (excluding itself). */
        List<String> flowOf(String http) {
            Node ep = endpoint(http);
            return query.reachableFrom(ep.id).stream().filter(id -> !id.equals(ep.id))
                    .map(id -> graph.get(id).label).sorted().toList();
        }

        List<Node> sinks() {
            return graph.nodes.stream().filter(n -> "db".equals(n.type) || "external".equals(n.type)).toList();
        }

        List<String> sinkLabels() {
            return sinks().stream().map(n -> n.label).sorted().toList();
        }

        List<Edge> edgesTo(String targetLabel) {
            Node t = node(targetLabel);
            return graph.edges.stream().filter(e -> e.target.equals(t.id)).toList();
        }

        void assertFlowContains(String http, String... labels) {
            List<String> flow = flowOf(http);
            for (String l : labels) assertTrue(flow.contains(l), "expected '" + l + "' in flow of " + http + ": " + flow);
        }
    }
}

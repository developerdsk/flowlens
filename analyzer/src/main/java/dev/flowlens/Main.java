package dev.flowlens;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowlens.model.Graph;

import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: flowlens-analyzer <source-root> [output.json]");
            System.err.println("  <source-root>  path to a Java source tree (e.g. src/main/java)");
            System.err.println("  [output.json]  where to write the graph (default: graph.json)");
            System.exit(2);
        }
        Path sourceRoot = Path.of(args[0]);
        Path out = Path.of(args.length > 1 ? args[1] : "graph.json");

        if (!Files.exists(sourceRoot)) {
            System.err.println("Source root does not exist: " + sourceRoot.toAbsolutePath());
            System.exit(1);
        }

        System.out.println("FlowLens: analyzing " + sourceRoot.toAbsolutePath());
        Graph graph = new FlowAnalyzer().analyze(sourceRoot);

        ObjectMapper mapper = new ObjectMapper()
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), graph);

        long endpoints = graph.nodes.stream().filter(n -> "endpoint".equals(n.type)).count();
        long dbs = graph.nodes.stream().filter(n -> "db".equals(n.type)).count();
        System.out.printf("Wrote %s — %d nodes (%d endpoints, %d db sinks), %d edges%n",
                out.toAbsolutePath(), graph.nodes.size(), endpoints, dbs, graph.edges.size());
    }
}

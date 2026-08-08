package dev.flowlens;

import dev.flowlens.model.Graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("--serve")) {
            int port = args.length >= 2 ? Integer.parseInt(args[1]) : 7070;
            new Server(port).start();
            new CountDownLatch(1).await(); // keep the process alive
            return;
        }

        if (args.length < 1) {
            System.err.println("FlowLens analyzer");
            System.err.println("  Serve UI:  flowlens-analyzer --serve [port]        (default port 7070)");
            System.err.println("  One-shot:  flowlens-analyzer <source-root> [out.json]");
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
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), graph);

        long endpoints = graph.nodes.stream().filter(n -> "endpoint".equals(n.type)).count();
        long dbs = graph.nodes.stream().filter(n -> "db".equals(n.type)).count();
        System.out.printf("Wrote %s — %d nodes (%d endpoints, %d db sinks), %d edges%n",
                out.toAbsolutePath(), graph.nodes.size(), endpoints, dbs, graph.edges.size());
    }
}

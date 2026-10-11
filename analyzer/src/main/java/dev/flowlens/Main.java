package dev.flowlens;

import dev.flowlens.mcp.McpServer;
import dev.flowlens.model.Graph;
import dev.flowlens.server.Server;
import dev.flowlens.source.AnalysisOptions;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** FlowLens command line: one-shot analysis, local server (viewer + API), MCP server, graph diff. */
public class Main {

    private static final String USAGE = String.join("\n",
            "FlowLens " + Version.VERSION + " - static request-flow graphs for Java/Spring",
            "",
            "  Analyze:  flowlens <project-folder> [out.json] [--include-tests] [--modules a,b] [--ref <git-ref>]",
            "  Serve UI: flowlens --serve [port] [--open]           viewer + API on http://127.0.0.1:<port> (default 7070)",
            "  Engine:   flowlens --serve --port 0 --exit-on-stdin-close   (used by the desktop app; token via FLOWLENS_TOKEN)",
            "  MCP:      flowlens --mcp [project-folder]            Model Context Protocol server on stdio, for AI agents",
            "  Diff:     flowlens --diff <base.json> <head.json> [out.json]",
            "  Version:  flowlens --version");

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList(argv));
        if (args.contains("--version") || args.contains("-v")) {
            System.out.println(Version.VERSION);
            return;
        }
        if (args.contains("--help") || args.contains("-h")) {
            System.out.println(USAGE);
            return;
        }
        if (args.contains("--mcp")) {
            mcp(args);
            return;
        }
        if (args.contains("--serve")) {
            serve(args);
            return;
        }
        if (args.contains("--diff")) {
            diff(args);
            return;
        }
        analyze(args);
    }

    private static void serve(List<String> args) throws Exception {
        Server.Config cfg = new Server.Config();
        int idx = args.indexOf("--serve");
        if (idx + 1 < args.size() && args.get(idx + 1).matches("\\d+")) cfg.port = parsePort(args.get(idx + 1));
        String p = option(args, "--port");
        if (p != null) cfg.port = parsePort(p);
        String token = System.getenv("FLOWLENS_TOKEN");
        if (token != null && !token.isBlank()) {
            cfg.token = token.trim();
            cfg.allowPicker = false;     // the desktop shell shows the native dialog
            cfg.portFallback = false;
        }
        Server server = new Server(cfg);
        int port;
        try {
            port = server.start();
        } catch (Exception e) {
            System.err.println("FlowLens: " + e.getMessage());
            System.exit(1);
            return;
        }
        // Machine-readable line for the desktop shell, then a human one.
        System.out.println("FLOWLENS_READY port=" + port + " version=" + Version.VERSION);
        System.out.println("FlowLens " + Version.VERSION + " running at  http://localhost:" + port);
        if (cfg.token == null) {
            System.out.println("Open that URL, then pick a folder or paste a project path to index.");
            System.out.println("Press Ctrl+C to stop.");
        }
        System.out.flush();

        if (args.contains("--exit-on-stdin-close")) {
            Thread watcher = new Thread(() -> {
                try {
                    while (System.in.read() != -1) { /* parent alive */ }
                } catch (Exception ignored) { }
                System.err.println("FlowLens: parent process went away, exiting.");
                System.exit(0);
            }, "parent-watch");
            watcher.setDaemon(true);
            watcher.start();
        }
        if (args.contains("--open")) openBrowser("http://localhost:" + port);
        new CountDownLatch(1).await();
    }

    private static void openBrowser(String url) {
        try {
            if (ModuleLayer.boot().findModule("java.desktop").isPresent()) {
                Class<?> desktop = Class.forName("java.awt.Desktop");
                Object d = desktop.getMethod("getDesktop").invoke(null);
                desktop.getMethod("browse", java.net.URI.class).invoke(d, java.net.URI.create(url));
            }
        } catch (Throwable ignored) {
            // headless or unsupported: the URL is printed anyway
        }
    }

    private static void mcp(List<String> args) throws Exception {
        // stdout carries the protocol; anything else printed must go to stderr.
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        int idx = args.indexOf("--mcp");
        Path project = idx + 1 < args.size() && !args.get(idx + 1).startsWith("--") ? Path.of(args.get(idx + 1)) : null;
        if (project == null && System.getenv("FLOWLENS_PROJECT") != null) project = Path.of(System.getenv("FLOWLENS_PROJECT"));
        new McpServer(project == null ? null : project.toAbsolutePath(), protocol).run(System.in);
    }

    private static void diff(List<String> args) throws Exception {
        int idx = args.indexOf("--diff");
        if (idx + 2 >= args.size()) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Graph base = Json.MAPPER.readValue(Path.of(args.get(idx + 1)).toFile(), Graph.class).reindex();
        Graph head = Json.MAPPER.readValue(Path.of(args.get(idx + 2)).toFile(), Graph.class).reindex();
        Graph d = GraphDiff.diff(base, head, args.get(idx + 1));
        Path out = Path.of(idx + 3 < args.size() ? args.get(idx + 3) : "graph.diff.json");
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), d);
        long affected = d.nodes.stream().filter(n -> "endpoint".equals(n.type) && n.diff != null).count();
        System.out.printf("Wrote %s - %d endpoints added/changed/affected%n", out.toAbsolutePath(), affected);
    }

    private static void analyze(List<String> args) throws Exception {
        AnalysisOptions options = new AnalysisOptions();
        options.includeTests = args.remove("--include-tests");
        String modules = option(args, "--modules");
        if (modules != null) options.modules.addAll(Arrays.asList(modules.split(",")));
        options.ref = option(args, "--ref");
        List<String> positional = args.stream().filter(a -> !a.startsWith("--")).toList();
        if (positional.isEmpty()) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path sourceRoot = Path.of(positional.get(0));
        Path out = Path.of(positional.size() > 1 ? positional.get(1) : "graph.json");
        if (!Files.isDirectory(sourceRoot)) {
            System.err.println("Project folder does not exist: " + sourceRoot.toAbsolutePath());
            System.exit(1);
        }

        System.out.println("FlowLens: analyzing " + sourceRoot.toAbsolutePath());
        Graph graph = new FlowAnalyzer().analyze(sourceRoot, options, FlowAnalyzer.Progress.NONE, null);
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), graph);

        long endpoints = graph.nodes.stream().filter(n -> "endpoint".equals(n.type)).count();
        long dbs = graph.nodes.stream().filter(n -> "db".equals(n.type)).count();
        long ext = graph.nodes.stream().filter(n -> "external".equals(n.type)).count();
        System.out.printf("Wrote %s - %d nodes (%d entry points, %d db sinks, %d external), %d edges, %d files skipped, %d ms%n",
                out.toAbsolutePath(), graph.nodes.size(), endpoints, dbs, ext, graph.edges.size(),
                graph.stats.filesSkipped, graph.stats.durationMs);
    }

    /** Remove "--name value" from args and return value (or null). */
    private static String option(List<String> args, String name) {
        int i = args.indexOf(name);
        if (i < 0) return null;
        if (i + 1 >= args.size()) {
            System.err.println("Missing value for " + name);
            System.exit(2);
        }
        String v = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        return v;
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p < 0 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            System.err.println("Invalid port: " + s + " (expected 0-65535)");
            System.exit(2);
            return -1;
        }
    }
}

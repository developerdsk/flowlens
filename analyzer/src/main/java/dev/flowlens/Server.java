package dev.flowlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.flowlens.model.Graph;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A tiny localhost web server so the viewer can index a project on demand.
 * Serves the bundled viewer UI and exposes POST /api/analyze.
 *
 * Bound to 127.0.0.1 only: the /api/analyze {path:...} form reads files from
 * disk, so this must never be exposed to the network.
 */
public class Server {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".html", "text/html; charset=utf-8",
            ".js", "text/javascript; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".json", "application/json; charset=utf-8",
            ".svg", "image/svg+xml",
            ".ico", "image/x-icon");

    private final int port;

    public Server(int port) { this.port = port; }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/analyze", this::handleAnalyze);
        server.createContext("/api/pick", this::handlePick);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("FlowLens server running at  http://localhost:" + port);
        System.out.println("Open that URL, then pick a folder or paste a project path to index.");
        System.out.println("Press Ctrl+C to stop.");
    }

    // --- static viewer assets (bundled on the classpath under /viewer) -------

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        // prevent path traversal
        if (path.contains("..")) { send(ex, 400, "text/plain", "bad path".getBytes()); return; }

        String resource = "/viewer" + path;
        try (InputStream in = Server.class.getResourceAsStream(resource)) {
            if (in == null) { send(ex, 404, "text/plain", ("Not found: " + path).getBytes()); return; }
            byte[] body = in.readAllBytes();
            send(ex, 200, contentType(path), body);
        }
    }

    // --- native folder picker (opens on the machine running the server) ------

    private void handlePick(HttpExchange ex) throws IOException {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                sendError(ex, 400, "No desktop available to open a folder chooser — paste a path instead.");
                return;
            }
            String chosen = chooseDirectory();
            if (chosen == null) {
                send(ex, 200, "application/json; charset=utf-8", "{\"cancelled\":true}".getBytes());
                return;
            }
            Graph graph = new FlowAnalyzer().analyze(Path.of(chosen));
            graph.sourcePath = chosen;
            System.out.printf("Picked %s -> %d nodes, %d edges%n", chosen, graph.nodes.size(), graph.edges.size());
            send(ex, 200, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(graph));
        } catch (Exception e) {
            sendError(ex, 500, "Folder pick failed: " + e.getMessage());
        }
    }

    /** Show a native directories-only chooser on the server host; return the absolute path or null. */
    private static String chooseDirectory() throws Exception {
        final String[] result = new String[1];
        Runnable task = () -> {
            javax.swing.JFrame parent = new javax.swing.JFrame();
            parent.setAlwaysOnTop(true);
            parent.setUndecorated(true);
            parent.setType(java.awt.Window.Type.UTILITY);
            parent.setLocationRelativeTo(null);
            parent.setVisible(true);
            parent.toFront();

            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setDialogTitle("Select a Java / Spring project folder");
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            int res = fc.showDialog(parent, "Index this folder");
            parent.dispose();
            if (res == javax.swing.JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                result[0] = fc.getSelectedFile().getAbsolutePath();
            }
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) task.run();
        else javax.swing.SwingUtilities.invokeAndWait(task);
        return result[0];
    }

    // --- analysis endpoint ---------------------------------------------------

    private void handleAnalyze(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Use POST");
            return;
        }
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            JsonNode req = Json.MAPPER.readTree(body);
            Graph graph;

            if (req.hasNonNull("path")) {
                Path root = Path.of(req.get("path").asText().trim());
                if (!Files.exists(root)) { sendError(ex, 400, "Path does not exist: " + root); return; }
                graph = new FlowAnalyzer().analyze(root);
            } else if (req.has("files") && req.get("files").isArray()) {
                List<FlowAnalyzer.SourceFile> files = new ArrayList<>();
                for (JsonNode f : req.get("files")) {
                    files.add(new FlowAnalyzer.SourceFile(
                            f.path("path").asText(null), f.path("content").asText(null)));
                }
                if (files.isEmpty()) { sendError(ex, 400, "No .java files were provided"); return; }
                graph = new FlowAnalyzer().analyzeSources(files);
            } else {
                sendError(ex, 400, "Provide either { path } or { files: [...] }");
                return;
            }

            byte[] out = Json.MAPPER.writeValueAsBytes(graph);
            System.out.printf("Analyzed: %d nodes, %d edges%n", graph.nodes.size(), graph.edges.size());
            send(ex, 200, "application/json; charset=utf-8", out);
        } catch (Exception e) {
            sendError(ex, 500, "Analysis failed: " + e.getMessage());
        }
    }

    // --- helpers -------------------------------------------------------------

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot).toLowerCase();
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    private static void sendError(HttpExchange ex, int code, String message) throws IOException {
        byte[] body = ("{\"error\":" + quote(message) + "}").getBytes(StandardCharsets.UTF_8);
        send(ex, code, "application/json; charset=utf-8", body);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}

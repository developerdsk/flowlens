package dev.flowlens.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.flowlens.GraphDiff;
import dev.flowlens.Json;
import dev.flowlens.Version;
import dev.flowlens.model.Graph;
import dev.flowlens.source.AnalysisOptions;
import dev.flowlens.source.DiskTree;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * The local FlowLens engine: serves the bundled viewer and the analysis API.
 *
 * <p>Security model (it reads any folder the caller names, so it must only ever serve the
 * local user):
 * <ul>
 *   <li>binds to 127.0.0.1 only;</li>
 *   <li>rejects requests whose {@code Host} isn't this loopback address (defeats DNS rebinding);</li>
 *   <li>rejects cross-origin requests ({@code Origin} must be this server);</li>
 *   <li>API POSTs must be {@code application/json} (no "simple" cross-site form posts);</li>
 *   <li>when started by the desktop app, every API call must carry the per-launch token.</li>
 * </ul>
 */
public class Server {

    public static final String TOKEN_HEADER = "X-FlowLens-Token";
    private static final long MAX_BODY = 256L * 1024 * 1024;

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry(".html", "text/html; charset=utf-8"),
            Map.entry(".js", "text/javascript; charset=utf-8"),
            Map.entry(".mjs", "text/javascript; charset=utf-8"),
            Map.entry(".css", "text/css; charset=utf-8"),
            Map.entry(".json", "application/json; charset=utf-8"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".png", "image/png"),
            Map.entry(".ico", "image/x-icon"));

    /** Server settings. */
    public static class Config {
        public int port = 7070;
        /** Try the next ports if {@link #port} is taken (browser mode). Ignored for port 0. */
        public boolean portFallback = true;
        /** Required on every /api call when set (desktop mode). */
        public String token;
        /** Allow the server-side folder chooser (browser mode). */
        public boolean allowPicker = true;
    }

    private final Config config;
    private final Jobs jobs = new Jobs();
    private HttpServer server;
    private int boundPort;

    public Server(Config config) {
        this.config = config;
    }

    public Server(int port) {
        this(defaultConfig(port));
    }

    private static Config defaultConfig(int port) {
        Config c = new Config();
        c.port = port;
        return c;
    }

    /** Start listening; returns the bound port (useful with port 0). */
    public int start() throws IOException {
        int port = config.port;
        int attempts = config.port == 0 || !config.portFallback ? 1 : 20;
        BindException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", port + i), 0);
                break;
            } catch (BindException e) {
                last = e;
                if (attempts > 1) System.err.println("Port " + (port + i) + " is in use, trying " + (port + i + 1) + "...");
            }
        }
        if (server == null) {
            throw new IOException("Could not start: port " + port + (attempts > 1 ? "-" + (port + attempts - 1) : "")
                    + " already in use. Pass a different port, e.g. --serve 7171" + (last == null ? "" : ""));
        }
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "flowlens-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        boundPort = server.getAddress().getPort();
        return boundPort;
    }

    public int port() {
        return boundPort;
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ------------------------------------------------------------------------------------------
    // request guard
    // ------------------------------------------------------------------------------------------

    /** Returns an error message if the request must be rejected, or null. */
    String rejectReason(HttpExchange ex, boolean api) {
        String host = ex.getRequestHeaders().getFirst("Host");
        Set<String> allowedHosts = Set.of("127.0.0.1:" + boundPort, "localhost:" + boundPort, "[::1]:" + boundPort);
        if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return "forbidden host";
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("null")) {
            Set<String> allowedOrigins = Set.of("http://127.0.0.1:" + boundPort, "http://localhost:" + boundPort);
            if (!allowedOrigins.contains(origin.toLowerCase(Locale.ROOT))) return "cross-origin request refused";
        } else if ("null".equals(origin)) {
            return "cross-origin request refused";
        }
        if (!api) return null;
        String fetchSite = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (fetchSite != null && fetchSite.equals("cross-site")) return "cross-site request refused";
        if (config.token != null && !config.token.isEmpty()) {
            String t = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
            if (t == null || !MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), config.token.getBytes(StandardCharsets.UTF_8))) {
                return "missing or invalid token";
            }
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json")) return "Content-Type must be application/json";
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // static viewer assets (bundled on the classpath under /viewer)
    // ------------------------------------------------------------------------------------------

    private void handleStatic(HttpExchange ex) throws IOException {
        try {
            String reject = rejectReason(ex, false);
            if (reject != null) { sendError(ex, 403, reject); return; }
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) { sendError(ex, 405, "Use GET"); return; }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            if (path.contains("..") || path.contains("\\")) { sendError(ex, 400, "bad path"); return; }
            try (InputStream in = Server.class.getResourceAsStream("/viewer" + path)) {
                if (in == null) { sendError(ex, 404, "Not found: " + path); return; }
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                ex.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'");
                send(ex, 200, contentType(path), in.readAllBytes());
            }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------------------------------

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            String reject = rejectReason(ex, true);
            if (reject != null) {
                sendError(ex, reject.startsWith("Content-Type") ? 415 : reject.startsWith("missing") ? 401 : 403, reject);
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            try {
                if (path.equals("/api/info") && method.equals("GET")) info(ex);
                else if (path.equals("/api/analyze") && method.equals("POST")) analyzeSync(ex);
                else if (path.equals("/api/jobs") && method.equals("POST")) startJob(ex);
                else if (path.startsWith("/api/jobs/") && method.equals("GET")) jobStatus(ex, path.substring("/api/jobs/".length()));
                else if (path.startsWith("/api/jobs/") && method.equals("DELETE")) cancelJob(ex, path.substring("/api/jobs/".length()));
                else if (path.equals("/api/pick") && method.equals("POST")) pick(ex);
                else if (path.equals("/api/fingerprint") && method.equals("POST")) fingerprint(ex);
                else if (path.equals("/api/diff") && method.equals("POST")) diff(ex);
                else sendError(ex, 404, "Unknown API: " + method + " " + path);
            } catch (BadRequest e) {
                sendError(ex, 400, e.getMessage());
            } catch (Exception e) {
                sendError(ex, 500, "Analysis failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        } finally {
            ex.close();
        }
    }

    private static final class BadRequest extends Exception {
        BadRequest(String m) { super(m); }
    }

    private void info(HttpExchange ex) throws IOException {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("version", Version.VERSION);
        o.put("schemaVersion", Version.SCHEMA_VERSION);
        o.put("picker", config.allowPicker && FolderPicker.available());
        o.put("desktop", config.token != null);
        sendJson(ex, 200, o);
    }

    private JsonNode body(HttpExchange ex) throws IOException, BadRequest {
        byte[] body = ex.getRequestBody().readNBytes((int) Math.min(MAX_BODY + 1, Integer.MAX_VALUE - 8));
        if (body.length > MAX_BODY) throw new BadRequest("Request too large");
        try {
            JsonNode n = Json.MAPPER.readTree(body);
            if (n == null || !n.isObject()) throw new BadRequest("Expected a JSON object");
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequest("Invalid JSON: " + e.getOriginalMessage());
        }
    }

    /** Validated project folder + options from a request body. */
    private record Target(Path root, AnalysisOptions options) {}

    private Target target(JsonNode req) throws BadRequest {
        if (!req.hasNonNull("path") || req.get("path").asText().isBlank()) throw new BadRequest("Provide { \"path\": \"/absolute/project/folder\" }");
        Path root;
        try {
            root = Path.of(req.get("path").asText().trim());
        } catch (Exception e) {
            throw new BadRequest("Invalid path");
        }
        if (!root.isAbsolute()) throw new BadRequest("Path must be absolute: " + root);
        if (!Files.isDirectory(root)) throw new BadRequest("Folder does not exist: " + root);
        AnalysisOptions o = new AnalysisOptions();
        o.includeTests = req.path("includeTests").asBoolean(false);
        if (req.has("modules") && req.get("modules").isArray()) req.get("modules").forEach(m -> o.modules.add(m.asText()));
        if (req.hasNonNull("ref") && !req.get("ref").asText().isBlank()) {
            String ref = req.get("ref").asText().trim();
            if (ref.startsWith("-") || ref.contains("..") && ref.contains(" ")) throw new BadRequest("Invalid git ref");
            o.ref = ref;
        }
        return new Target(root, o);
    }

    private void analyzeSync(HttpExchange ex) throws Exception {
        Target t = target(body(ex));
        Graph g = jobs.analyzeNow(t.root(), t.options());
        send(ex, 200, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(g));
    }

    private void startJob(HttpExchange ex) throws Exception {
        Target t = target(body(ex));
        Jobs.Job job = jobs.start(t.root(), t.options());
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("id", job.id);
        sendJson(ex, 202, o);
    }

    private void jobStatus(HttpExchange ex, String id) throws IOException {
        Jobs.Job job = jobs.get(id);
        if (job == null) { sendError(ex, 404, "Unknown job"); return; }
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("id", job.id);
        o.put("state", job.state);
        o.put("phase", job.phase);
        o.put("done", job.done);
        o.put("total", job.total);
        if (job.error != null) o.put("error", job.error);
        if ("done".equals(job.state)) o.set("graph", Json.MAPPER.valueToTree(job.graph));
        sendJson(ex, 200, o);
    }

    private void cancelJob(HttpExchange ex, String id) throws IOException {
        if (!jobs.cancel(id)) { sendError(ex, 404, "Unknown job"); return; }
        sendJson(ex, 200, Json.MAPPER.createObjectNode().put("cancelled", true));
    }

    private void pick(HttpExchange ex) throws Exception {
        if (!config.allowPicker || !FolderPicker.available()) {
            sendError(ex, 400, "No folder chooser available here — paste a path instead.");
            return;
        }
        String chosen = FolderPicker.choose();
        ObjectNode o = Json.MAPPER.createObjectNode();
        if (chosen == null) o.put("cancelled", true);
        else o.put("path", chosen);
        sendJson(ex, 200, o);
    }

    /** Cheap change detector for watch mode: hash of (path, mtime, size) of all sources. */
    private void fingerprint(HttpExchange ex) throws Exception {
        Target t = target(body(ex));
        if (t.options().ref != null) {
            sendJson(ex, 200, Json.MAPPER.createObjectNode().put("fingerprint", "ref:" + t.options().ref));
            return;
        }
        DiskTree tree = new DiskTree(t.root());
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        List<String> files = tree.list();
        int count = 0;
        for (String f : files) {
            String lower = f.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".java") && !lower.endsWith(".xml")) continue;
            try {
                md.update((f + "|" + tree.fingerprint(f) + "\n").getBytes(StandardCharsets.UTF_8));
                count++;
            } catch (IOException ignored) { /* deleted while walking */ }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("fingerprint", sb.toString());
        o.put("files", count);
        sendJson(ex, 200, o);
    }

    private void diff(HttpExchange ex) throws Exception {
        JsonNode req = body(ex);
        if (!req.has("base") || !req.has("current")) throw new BadRequest("Provide { base: graph, current: graph }");
        Graph base = Json.MAPPER.treeToValue(req.get("base"), Graph.class).reindex();
        Graph current = Json.MAPPER.treeToValue(req.get("current"), Graph.class).reindex();
        String label = req.path("label").asText("baseline");
        Graph d = GraphDiff.diff(base, current, label);
        send(ex, 200, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(d));
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    private static void sendJson(HttpExchange ex, int code, JsonNode body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(body));
    }

    static void sendError(HttpExchange ex, int code, String message) throws IOException {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("error", message == null ? "error" : message);
        sendJson(ex, code, o);
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
        ex.sendResponseHeaders(code, head ? -1 : body.length);
        if (!head) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }
}

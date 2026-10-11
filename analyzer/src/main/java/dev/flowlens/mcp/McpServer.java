package dev.flowlens.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.flowlens.FlowAnalyzer;
import dev.flowlens.GraphDiff;
import dev.flowlens.GraphQuery;
import dev.flowlens.Json;
import dev.flowlens.Version;
import dev.flowlens.model.Graph;
import dev.flowlens.model.Node;
import dev.flowlens.source.AnalysisOptions;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Model Context Protocol server over stdio, so AI coding agents can ask FlowLens about a
 * codebase: "what writes to the account table?", "which endpoints does changing
 * AccountService.create affect?", "show me the flow of POST /api/accounts".
 *
 * <p>Newline-delimited JSON-RPC 2.0 on stdin/stdout; all logging goes to stderr.
 */
public class McpServer {

    private static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private final PrintStream out;
    private final Path defaultProject;
    private Path indexedPath;
    private AnalysisOptions indexedOptions;
    private Graph graph;
    private final FlowAnalyzer.ParseCache cache = new FlowAnalyzer.ParseCache();

    public McpServer(Path defaultProject, PrintStream out) {
        this.defaultProject = defaultProject;
        this.out = out;
    }

    public void run(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try {
                msg = Json.MAPPER.readTree(line);
            } catch (Exception e) {
                write(error(null, -32700, "Parse error"));
                continue;
            }
            JsonNode response = handle(msg);
            if (response != null) write(response);
        }
    }

    private synchronized void write(JsonNode n) throws Exception {
        out.println(Json.MAPPER.writeValueAsString(n));
        out.flush();
    }

    JsonNode handle(JsonNode msg) {
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText("");
        if (id == null || id.isNull()) return null; // notification
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(msg.path("params")));
                case "ping" -> result(id, Json.MAPPER.createObjectNode());
                case "tools/list" -> result(id, toolsList());
                case "tools/call" -> result(id, callTool(msg.path("params")));
                case "resources/list" -> result(id, Json.MAPPER.createObjectNode().set("resources", Json.MAPPER.createArrayNode()));
                case "prompts/list" -> result(id, Json.MAPPER.createObjectNode().set("prompts", Json.MAPPER.createArrayNode()));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            return error(id, -32603, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        ObjectNode r = Json.MAPPER.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : DEFAULT_PROTOCOL);
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "flowlens");
        info.put("title", "FlowLens");
        info.put("version", Version.VERSION);
        r.put("instructions", "FlowLens statically maps Java/Spring request flows: entry point -> services -> database tables, "
                + "outbound HTTP and message brokers. Call index_project first (or rely on the default project), then "
                + "list_endpoints / endpoint_flow / table_access / impact_of_change / search / compare_with_ref.");
        return r;
    }

    // ------------------------------------------------------------------------------------------
    // tools
    // ------------------------------------------------------------------------------------------

    private record Tool(String name, String description, Map<String, String[]> props, Set<String> required) {}

    private static final List<Tool> TOOLS = List.of(
            new Tool("index_project", "Index (or re-index) a Java/Spring project folder. Returns counts of endpoints, sinks and skipped files.",
                    Map.of("path", new String[]{"string", "Absolute path to the project folder"},
                            "includeTests", new String[]{"boolean", "Also index src/test"}), Set.of("path")),
            new Tool("list_endpoints", "List entry points (HTTP endpoints, message listeners, scheduled jobs), optionally filtered by a substring.",
                    Map.of("filter", new String[]{"string", "Substring of the HTTP verb/path or handler name"}), Set.of()),
            new Tool("endpoint_flow", "Show the full flow of one entry point: call chain, branch conditions, DB tables, outbound calls and request-field lineage.",
                    Map.of("endpoint", new String[]{"string", "Endpoint id, \"POST /api/accounts\", or a unique substring"}), Set.of("endpoint")),
            new Tool("table_access", "Which entry points read or write a database table / collection / topic, grouped by operation.",
                    Map.of("table", new String[]{"string", "Table, collection, topic or URL fragment"}), Set.of("table")),
            new Tool("impact_of_change", "Which entry points reach a class or method — the blast radius of changing it.",
                    Map.of("symbol", new String[]{"string", "Class or Class.method, e.g. AccountService.create"}), Set.of("symbol")),
            new Tool("search", "Find nodes (endpoints, methods, tables, external calls) whose name, path, table or file matches.",
                    Map.of("query", new String[]{"string", "Search text"}), Set.of("query")),
            new Tool("compare_with_ref", "Diff the current working tree against a git ref (branch/tag/commit): changed methods and affected endpoints.",
                    Map.of("ref", new String[]{"string", "Git ref to compare against, e.g. main"}), Set.of("ref")));

    private ObjectNode toolsList() {
        ObjectNode r = Json.MAPPER.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (Tool t : TOOLS) {
            ObjectNode tn = arr.addObject();
            tn.put("name", t.name());
            tn.put("description", t.description());
            ObjectNode schema = tn.putObject("inputSchema");
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            t.props().forEach((k, v) -> props.putObject(k).put("type", v[0]).put("description", v[1]));
            ArrayNode req = schema.putArray("required");
            t.required().forEach(req::add);
            tn.putObject("annotations").put("readOnlyHint", true).put("openWorldHint", false);
        }
        return r;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText();
        JsonNode args = params.path("arguments");
        String text;
        try {
            text = switch (name) {
                case "index_project" -> indexProject(args);
                case "list_endpoints" -> listEndpoints(args.path("filter").asText(""));
                case "endpoint_flow" -> endpointFlow(args.path("endpoint").asText());
                case "table_access" -> tableAccess(args.path("table").asText());
                case "impact_of_change" -> impact(args.path("symbol").asText());
                case "search" -> search(args.path("query").asText());
                case "compare_with_ref" -> compare(args.path("ref").asText());
                default -> throw new IllegalArgumentException("Unknown tool: " + name);
            };
        } catch (Exception e) {
            // tool failures (bad input, git errors, unreadable folders) go back to the agent, not the protocol
            return toolResult(e.getMessage() == null ? e.toString() : e.getMessage(), true);
        }
        return toolResult(text, false);
    }

    private static ObjectNode toolResult(String text, boolean isError) {
        ObjectNode r = Json.MAPPER.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", isError);
        return r;
    }

    private Graph graph() throws Exception {
        if (graph == null) {
            if (defaultProject == null) throw new IllegalStateException("No project indexed yet. Call index_project with an absolute path first.");
            index(defaultProject, new AnalysisOptions());
        }
        return graph;
    }

    private void index(Path path, AnalysisOptions options) throws Exception {
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Folder does not exist: " + path);
        graph = new FlowAnalyzer().analyze(path, options, FlowAnalyzer.Progress.NONE, cache);
        indexedPath = path;
        indexedOptions = options;
    }

    private String indexProject(JsonNode args) throws Exception {
        String p = args.path("path").asText("");
        if (p.isBlank()) throw new IllegalArgumentException("path is required");
        Path path = Path.of(p);
        if (!path.isAbsolute()) throw new IllegalArgumentException("path must be absolute");
        AnalysisOptions o = new AnalysisOptions();
        o.includeTests = args.path("includeTests").asBoolean(false);
        index(path, o);
        return summary();
    }

    private String summary() {
        long eps = graph.nodes.stream().filter(n -> "endpoint".equals(n.type)).count();
        long dbs = graph.nodes.stream().filter(n -> "db".equals(n.type)).count();
        long ext = graph.nodes.stream().filter(n -> "external".equals(n.type)).count();
        StringBuilder sb = new StringBuilder();
        sb.append("Indexed ").append(indexedPath).append('\n')
                .append(eps).append(" entry points, ").append(dbs).append(" database sinks, ").append(ext).append(" external calls, ")
                .append(graph.nodes.size()).append(" nodes, ").append(graph.edges.size()).append(" edges.\n")
                .append(graph.stats.filesParsed).append(" files parsed, ").append(graph.stats.filesSkipped).append(" skipped.");
        if (!graph.modules.isEmpty()) sb.append("\nModules: ").append(String.join(", ", graph.modules));
        graph.diagnostics.stream().filter(d -> "warning".equals(d.severity)).limit(10)
                .forEach(d -> sb.append("\n  warning: ").append(d.file).append(": ").append(d.message));
        return sb.toString();
    }

    private String listEndpoints(String filter) throws Exception {
        GraphQuery q = new GraphQuery(graph());
        String f = filter.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Node ep : q.endpoints()) {
            String line = ep.http + "  ->  " + ep.label + (ep.file == null ? "" : "  (" + ep.file + ":" + ep.line + ")");
            if (!f.isEmpty() && !line.toLowerCase().contains(f)) continue;
            sb.append(line).append('\n');
            n++;
        }
        return n == 0 ? "No entry points match." : n + " entry points:\n" + sb;
    }

    private String endpointFlow(String query) throws Exception {
        GraphQuery q = new GraphQuery(graph());
        Node ep = q.findEndpoint(query);
        if (ep == null) throw new IllegalArgumentException("No entry point matches '" + query + "'. Use list_endpoints.");
        return q.outline(ep);
    }

    private String tableAccess(String table) throws Exception {
        var res = new GraphQuery(graph()).tableAccess(table);
        if (res.isEmpty()) return "No entry point reaches a sink matching '" + table + "'.";
        StringBuilder sb = new StringBuilder();
        res.forEach((t, ops) -> {
            sb.append(t).append('\n');
            ops.forEach((op, eps) -> {
                sb.append("  ").append(op).append(":\n");
                eps.forEach(e -> sb.append("    ").append(e).append('\n'));
            });
        });
        return sb.toString();
    }

    private String impact(String symbol) throws Exception {
        GraphQuery q = new GraphQuery(graph());
        List<Node> nodes = q.findMethods(symbol);
        if (nodes.isEmpty()) return "No method or class matching '" + symbol + "' is on any analyzed flow.";
        var eps = q.impactedEndpoints(nodes);
        StringBuilder sb = new StringBuilder("Matched: ");
        nodes.stream().limit(10).forEach(n -> sb.append(n.label).append("  "));
        sb.append("\n").append(eps.size()).append(" entry points reach it:\n");
        eps.forEach(e -> sb.append("  ").append(e.http).append("  (").append(e.label).append(")\n"));
        return sb.toString();
    }

    private String search(String query) throws Exception {
        List<Node> res = new GraphQuery(graph()).search(query, 50);
        if (res.isEmpty()) return "Nothing matches '" + query + "'.";
        StringBuilder sb = new StringBuilder();
        for (Node n : res) sb.append(n.type).append(": ").append(GraphQuery.describe(n)).append('\n');
        return sb.toString();
    }

    private String compare(String ref) throws Exception {
        graph();
        if (indexedPath == null) throw new IllegalStateException("Index a project first.");
        index(indexedPath, indexedOptions);   // the working tree as it is now (cheap with the parse cache)
        Graph current = graph;
        AnalysisOptions o = indexedOptions.copy();
        o.ref = ref;
        Graph base = new FlowAnalyzer().analyze(indexedPath, o, FlowAnalyzer.Progress.NONE, null);
        Graph d = GraphDiff.diff(base, current, ref);
        StringBuilder sb = new StringBuilder("Compared working tree with " + ref + ":\n");
        for (String kind : List.of("added", "removed", "changed")) {
            List<Node> ns = d.nodes.stream().filter(n -> kind.equals(n.diff) && !"branch".equals(n.type) && !"throw".equals(n.type)).toList();
            if (ns.isEmpty()) continue;
            sb.append('\n').append(kind.toUpperCase()).append(" (").append(ns.size()).append("):\n");
            ns.stream().limit(60).forEach(n -> sb.append("  ").append(n.type).append(": ").append(GraphQuery.describe(n)).append('\n'));
        }
        List<Node> affected = d.nodes.stream().filter(n -> "endpoint".equals(n.type) && n.diff != null).toList();
        sb.append("\nEntry points affected (").append(affected.size()).append("):\n");
        affected.forEach(n -> sb.append("  ").append(n.http).append("  [").append(n.diff).append("]\n"));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = Json.MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = Json.MAPPER.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.putObject("error").put("code", code).put("message", message);
        return r;
    }
}

package dev.flowlens;

import dev.flowlens.model.Edge;
import dev.flowlens.model.Graph;
import dev.flowlens.model.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Read-only questions over a graph: reachability, table access, impact. Used by MCP, CLI and diff. */
public class GraphQuery {
    private final Graph graph;
    private final Map<String, List<Edge>> out = new HashMap<>();
    private final Map<String, List<Edge>> in = new HashMap<>();

    public GraphQuery(Graph graph) {
        this.graph = graph.reindex();
        for (Edge e : graph.edges) {
            out.computeIfAbsent(e.source, k -> new ArrayList<>()).add(e);
            in.computeIfAbsent(e.target, k -> new ArrayList<>()).add(e);
        }
    }

    public List<Node> endpoints() {
        return graph.nodes.stream().filter(n -> "endpoint".equals(n.type)).toList();
    }

    public List<Edge> outgoing(String id) {
        return out.getOrDefault(id, List.of());
    }

    /** Ids reachable from {@code id} (inclusive). */
    public Set<String> reachableFrom(String id) {
        return walk(id, true);
    }

    /** Ids that can reach {@code id} (inclusive). */
    public Set<String> reaching(String id) {
        return walk(id, false);
    }

    private Set<String> walk(String id, boolean forward) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(id);
        seen.add(id);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            for (Edge e : (forward ? out : in).getOrDefault(cur, List.of())) {
                String next = forward ? e.target : e.source;
                if (seen.add(next)) stack.push(next);
            }
        }
        return seen;
    }

    /**
     * Endpoint by id, exact "VERB /path", or a unique substring of its http/label.
     * @throws IllegalArgumentException listing the candidates when the substring is ambiguous
     */
    public Node findEndpoint(String query) {
        if (query == null) return null;
        Node byId = graph.get(query);
        if (byId != null) return byId;
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Node> matches = new ArrayList<>();
        for (Node n : endpoints()) {
            String http = n.http == null ? "" : n.http.toLowerCase(Locale.ROOT);
            if (http.equals(q)) return n;
            if (http.contains(q) || n.label.toLowerCase(Locale.ROOT).contains(q)) matches.add(n);
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder("'" + query + "' matches " + matches.size() + " entry points; be more specific:");
            matches.stream().limit(30).forEach(n -> sb.append("\n  ").append(n.http));
            throw new IllegalArgumentException(sb.toString());
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    public List<Node> search(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Node> res = new ArrayList<>();
        for (Node n : graph.nodes) {
            String hay = (n.label + " " + (n.http == null ? "" : n.http) + " " + (n.table == null ? "" : n.table) + " "
                    + (n.file == null ? "" : n.file)).toLowerCase(Locale.ROOT);
            if (hay.contains(q)) res.add(n);
            if (res.size() >= limit) break;
        }
        return res;
    }

    /** Endpoints reaching a db/external node whose table/destination matches, grouped by op. */
    public Map<String, Map<String, Set<String>>> tableAccess(String table) {
        String t = table.toLowerCase(Locale.ROOT);
        Map<String, Map<String, Set<String>>> res = new LinkedHashMap<>(); // table -> op -> endpoints
        List<Node> sinks = graph.nodes.stream()
                .filter(n -> ("db".equals(n.type) || "external".equals(n.type)) && n.table != null).toList();
        // exact (or schema-qualified) matches win; substring matching only when there are none
        List<Node> matched = sinks.stream().filter(n -> {
            String nt = n.table.toLowerCase(Locale.ROOT);
            return nt.equals(t) || nt.endsWith("." + t);
        }).toList();
        if (matched.isEmpty()) matched = sinks.stream().filter(n -> n.table.toLowerCase(Locale.ROOT).contains(t)).toList();
        for (Node n : matched) {
            for (String id : reaching(n.id)) {
                Node ep = graph.get(id);
                if (ep != null && "endpoint".equals(ep.type)) {
                    res.computeIfAbsent(n.table, k -> new LinkedHashMap<>())
                            .computeIfAbsent(n.op, k -> new LinkedHashSet<>()).add(ep.http);
                }
            }
        }
        return res;
    }

    /** Nodes whose label/id mentions {@code symbol} (e.g. "AccountService.create" or "AccountService"). */
    public List<Node> findMethods(String symbol) {
        String s = symbol.replace("()", "").toLowerCase(Locale.ROOT);
        List<Node> res = new ArrayList<>();
        for (Node n : graph.nodes) {
            if (!("method".equals(n.type) || "endpoint".equals(n.type))) continue;
            String label = n.label.replace("()", "").toLowerCase(Locale.ROOT);
            String id = n.id.toLowerCase(Locale.ROOT);
            if (label.equals(s) || label.startsWith(s + ".") || label.endsWith("." + s) || id.contains(s)) res.add(n);
        }
        return res;
    }

    /** Endpoints whose flow includes any of the given nodes. */
    public Set<Node> impactedEndpoints(List<Node> nodes) {
        Set<Node> eps = new LinkedHashSet<>();
        for (Node n : nodes) {
            for (String id : reaching(n.id)) {
                Node ep = graph.get(id);
                if (ep != null && "endpoint".equals(ep.type)) eps.add(ep);
            }
        }
        return eps;
    }

    /** A readable indented outline of one endpoint's flow. */
    public String outline(Node ep) {
        StringBuilder sb = new StringBuilder();
        sb.append(ep.http == null ? ep.label : ep.http + "  (" + ep.label + ")").append('\n');
        outline(ep.id, 1, new LinkedHashSet<>(List.of(ep.id)), sb);
        if (!ep.lineage.isEmpty()) {
            sb.append("\nField lineage:\n");
            ep.lineage.forEach(l -> sb.append("  ").append(l.from).append(" -> ").append(l.to).append("   (").append(l.via).append(")\n"));
        }
        return sb.toString();
    }

    private void outline(String id, int depth, Set<String> path, StringBuilder sb) {
        if (depth > 30) return;
        for (Edge e : outgoing(id)) {
            Node t = graph.get(e.target);
            if (t == null) continue;
            sb.append("  ".repeat(depth));
            if (e.label != null && !e.label.isEmpty()) sb.append('[').append(e.label).append("] ");
            sb.append(describe(t));
            if (e.confidence != null && !"high".equals(e.confidence)) sb.append("  (").append(e.confidence).append(" confidence)");
            if (!path.add(t.id)) {
                sb.append("  (cycle)\n");
                continue;
            }
            sb.append('\n');
            outline(t.id, depth + 1, path, sb);
            path.remove(t.id);
        }
    }

    public static String describe(Node n) {
        return switch (n.type) {
            case "db" -> "DB " + n.op + " " + n.table + (n.tech == null ? "" : " [" + n.tech + "]");
            case "external" -> "EXTERNAL " + n.label;
            case "branch" -> "? " + n.label;
            case "throw" -> "! " + n.label;
            default -> n.label + (n.file == null ? "" : "  " + n.file + (n.line == null ? "" : ":" + n.line));
        };
    }
}

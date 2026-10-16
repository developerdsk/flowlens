package dev.flowlens.model;

import dev.flowlens.Version;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The whole data-flow graph. Serialized to graph.json for the viewer. */
public class Graph {
    public int schemaVersion = Version.SCHEMA_VERSION;
    public String generatedBy = Version.label();
    /** The folder that was indexed. */
    public String sourcePath;
    /** Git ref the sources were read from, when not the working tree. */
    public String ref;
    /** Modules found (multi-module Maven/Gradle builds); empty for single-module projects. */
    public List<String> modules = new ArrayList<>();
    public List<Node> nodes = new ArrayList<>();
    public List<Edge> edges = new ArrayList<>();
    public List<Diagnostic> diagnostics = new ArrayList<>();
    public Stats stats = new Stats();
    /** Diff mode: label of the baseline this graph was compared against. */
    public String diffBase;

    private final transient Map<String, Node> nodeIndex = new LinkedHashMap<>();
    private final transient Set<String> edgeKeys = new HashSet<>();

    /** Whether a node with this id exists. */
    public boolean has(String id) {
        return nodeIndex.containsKey(id);
    }

    /** Get an existing node by id, or register a freshly built one. */
    public Node upsert(Node node) {
        Node existing = nodeIndex.get(node.id);
        if (existing != null) return existing;
        nodeIndex.put(node.id, node);
        nodes.add(node);
        return node;
    }

    public Node get(String id) {
        return nodeIndex.get(id);
    }

    public Edge addEdge(String source, String target, String label) {
        return addEdge(source, target, label, "call", "high");
    }

    public Edge addEdge(String source, String target, String label, String kind, String confidence) {
        Edge e = new Edge(source, target, label);
        e.kind = kind;
        e.confidence = confidence;
        if (edgeKeys.add(e.key())) {
            edges.add(e);
            return e;
        }
        return null;
    }

    /** Rebuild the transient indexes (after deserializing from JSON). */
    public Graph reindex() {
        nodeIndex.clear();
        edgeKeys.clear();
        for (Node n : nodes) nodeIndex.put(n.id, n);
        for (Edge e : edges) edgeKeys.add(e.key());
        return this;
    }
}

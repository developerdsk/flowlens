package dev.flowlens.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The whole data-flow graph. Serialized to graph.json for the viewer. */
public class Graph {
    public String generatedBy = "FlowLens 0.1.0";
    public String sourcePath;   // the folder that was indexed (set by the server)
    public List<Node> nodes = new ArrayList<>();
    public List<Edge> edges = new ArrayList<>();

    private final transient Map<String, Node> nodeIndex = new LinkedHashMap<>();
    private final transient java.util.Set<String> edgeKeys = new java.util.HashSet<>();

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

    public void addEdge(String source, String target, String label) {
        Edge e = new Edge(source, target, label);
        if (edgeKeys.add(e.key())) {
            edges.add(e);
        }
    }
}

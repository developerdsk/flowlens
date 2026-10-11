package dev.flowlens;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flowlens.model.Edge;
import dev.flowlens.model.Graph;
import dev.flowlens.model.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares two graphs (e.g. main vs. a feature branch). The result is the current graph plus
 * ghost copies of removed nodes/edges, each marked {@code added | removed | changed}; endpoints
 * whose flow touches any change are marked {@code affected}.
 */
public final class GraphDiff {

    private GraphDiff() {}

    public static Graph diff(Graph base, Graph current, String baseLabel) {
        ObjectMapper m = Json.MAPPER;
        Graph out = m.convertValue(m.convertValue(current, Map.class), Graph.class).reindex();
        out.diffBase = baseLabel;
        Map<String, Node> baseNodes = new HashMap<>();
        for (Node n : base.nodes) baseNodes.put(n.id, n);
        Set<String> baseEdges = new HashSet<>();
        for (Edge e : base.edges) baseEdges.add(e.key());

        for (Node n : out.nodes) {
            Node b = baseNodes.get(n.id);
            if (b == null) n.diff = "added";
            else if (!Objects.equals(b.hash, n.hash) || !Objects.equals(b.op, n.op) || !Objects.equals(b.http, n.http)) n.diff = "changed";
            else n.diff = null;
        }
        Set<String> currentIds = new HashSet<>();
        for (Node n : out.nodes) currentIds.add(n.id);
        for (Node b : base.nodes) {
            if (currentIds.contains(b.id)) continue;
            Node ghost = m.convertValue(m.convertValue(b, Map.class), Node.class);
            ghost.diff = "removed";
            out.upsert(ghost);
        }
        Set<String> currentEdges = new HashSet<>();
        for (Edge e : out.edges) {
            currentEdges.add(e.key());
            e.diff = baseEdges.contains(e.key()) ? null : "added";
        }
        for (Edge b : base.edges) {
            if (currentEdges.contains(b.key())) continue;
            Edge ghost = out.addEdge(b.source, b.target, b.label, b.kind, b.confidence);
            if (ghost != null) ghost.diff = "removed";
        }

        // endpoints affected by any change in their (old or new) flow
        GraphQuery q = new GraphQuery(out);
        for (Node ep : q.endpoints()) {
            if (ep.diff != null) continue;
            for (String id : q.reachableFrom(ep.id)) {
                Node n = out.get(id);
                if (n != null && n.diff != null) { ep.diff = "affected"; break; }
            }
            if (ep.diff == null) {
                for (String id : q.reachableFrom(ep.id)) {
                    for (Edge e : q.outgoing(id)) if (e.diff != null) { ep.diff = "affected"; break; }
                    if (ep.diff != null) break;
                }
            }
        }
        return out;
    }
}

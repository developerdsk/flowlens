package dev.flowlens.model;

/** A directed edge: caller invokes callee (or writes to a db sink). */
public class Edge {
    public String source;
    public String target;
    public String label;

    public Edge(String source, String target, String label) {
        this.source = source;
        this.target = target;
        this.label = label;
    }

    public String key() {
        return source + "->" + target + ":" + label;
    }
}

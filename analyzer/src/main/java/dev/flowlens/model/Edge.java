package dev.flowlens.model;

/** A directed edge: caller invokes callee, a guard leads to a call, or a method hits a sink. */
public class Edge {
    public String source;
    public String target;
    public String label;
    /** high | medium | low */
    public String confidence;
    /** call | branch | sink | event | dispatch */
    public String kind;
    /** Diff mode: added | removed (null = unchanged or no diff). */
    public String diff;

    public Edge() {}

    public Edge(String source, String target, String label) {
        this.source = source;
        this.target = target;
        this.label = label;
    }

    public String key() {
        // empty labels are omitted in JSON, so a graph read back has null where the live one had ""
        return source + "->" + target + ":" + (label == null ? "" : label);
    }
}

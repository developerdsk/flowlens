package dev.flowlens.model;

import java.util.ArrayList;
import java.util.List;

/** A vertex in the data-flow graph: an endpoint, a method, or a database sink. */
public class Node {
    public String id;
    public String label;
    /** endpoint | method | db */
    public String type;
    /** controller | service | repository | entity | db | other */
    public String layer;

    // endpoint-only
    public String http;      // e.g. "POST /api/createAccount"

    // db-only
    public String op;        // INSERT | UPDATE | DELETE | SELECT | UPSERT | CALL
    public String table;

    // source location
    public String file;
    public Integer line;

    // flow detail (shown in the viewer's side panel)
    public List<String> conditions = new ArrayList<>();
    public List<String> mutations = new ArrayList<>();
    public String source;    // method source snippet

    public Node(String id, String label, String type) {
        this.id = id;
        this.label = label;
        this.type = type;
    }
}

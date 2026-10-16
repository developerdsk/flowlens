package dev.flowlens.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A vertex in the data-flow graph.
 *
 * <ul>
 *   <li>{@code endpoint} – an entry point (HTTP handler, message listener, scheduled job)</li>
 *   <li>{@code method}   – a project method on the call chain</li>
 *   <li>{@code branch}   – an if/else/switch/loop guard that a call sits under</li>
 *   <li>{@code throw}    – a {@code throw} reached on the flow</li>
 *   <li>{@code db}       – a persistence sink (table / collection / cache)</li>
 *   <li>{@code external} – an outbound HTTP call or message published to a broker</li>
 * </ul>
 */
public class Node {
    public String id;
    public String label;
    /** endpoint | method | branch | throw | db | external */
    public String type;
    /** controller | service | repository | entity | flow | db | external | other */
    public String layer;
    /** Maven/Gradle module the code lives in (multi-module projects). */
    public String module;

    // endpoint-only
    /** http | message | scheduled | event */
    public String kind;
    /** e.g. "POST /api/accounts", "KAFKA orders", "SCHEDULED cron=0 * * * * *" */
    public String http;
    /** HTTP verb (GET, POST, ...) or the broker for message endpoints. */
    public String verb;
    /** URL path, topic/queue, or schedule expression. */
    public String path;
    /** Framework the entry point was detected for: spring | jaxrs | micronaut | spring-webflux. */
    public String framework;

    // db / external
    /** INSERT | UPDATE | DELETE | SELECT | UPSERT | CALL (db); GET/POST/... or SEND (external) */
    public String op;
    public String table;
    /** jpa | jdbc | mybatis | jooq | mongo | redis | http | kafka | rabbit | jms | stream */
    public String tech;
    /** SQL / JPQL text for db sinks, URL for outbound HTTP. */
    public String detail;

    // branch-only
    /** then | else | case X | loop | catch */
    public String branch;

    // source location
    public String file;
    public Integer line;

    /** high | medium | low — how sure the analyzer is that this node is accurate. */
    public String confidence;

    // flow detail (shown in the viewer's side panel)
    public List<String> conditions = new ArrayList<>();
    public List<String> mutations = new ArrayList<>();
    /** Calls on this method whose receiver type could not be resolved. */
    public List<String> unresolved = new ArrayList<>();
    /** Request-field to column lineage (endpoint nodes only). */
    public List<Lineage> lineage = new ArrayList<>();
    /** Method source snippet. */
    public String source;
    /** Short hash of the source, used by diff mode to detect changed methods. */
    public String hash;

    /** Diff mode: added | removed | changed (null = unchanged or no diff). */
    public String diff;

    public Node() {}

    public Node(String id, String label, String type) {
        this.id = id;
        this.label = label;
        this.type = type;
    }
}

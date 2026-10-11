package dev.flowlens.model;

import java.util.Objects;

/** One request-field to database-column mapping found along an endpoint's flow. */
public class Lineage {
    /** e.g. "request.email" */
    public String from;
    /** e.g. "account.email" (table.column) */
    public String to;
    /** The method where the value is written onto the entity, e.g. "AccountService.create()". */
    public String via;

    public Lineage() {}

    public Lineage(String from, String to, String via) {
        this.from = from;
        this.to = to;
        this.via = via;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Lineage l && Objects.equals(from, l.from) && Objects.equals(to, l.to);
    }

    @Override public int hashCode() { return Objects.hash(from, to); }
}

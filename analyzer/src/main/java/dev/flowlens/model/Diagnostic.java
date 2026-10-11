package dev.flowlens.model;

/** Something the analyzer could not do, surfaced in the UI instead of only on stderr. */
public class Diagnostic {
    /** warning | info */
    public String severity;
    public String file;
    public String message;

    public Diagnostic() {}

    public Diagnostic(String severity, String file, String message) {
        this.severity = severity;
        this.file = file;
        this.message = message;
    }
}

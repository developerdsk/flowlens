package dev.flowlens.source;

import java.util.LinkedHashSet;
import java.util.Set;

/** What to index. Defaults: main sources of every module, working tree. */
public class AnalysisOptions {
    /** Also index src/test (test controllers, fixtures). */
    public boolean includeTests;
    /** Only these modules (by name); empty = all. */
    public Set<String> modules = new LinkedHashSet<>();
    /** Read sources from this git ref (branch, tag, commit) instead of the working tree. */
    public String ref;

    public AnalysisOptions copy() {
        AnalysisOptions o = new AnalysisOptions();
        o.includeTests = includeTests;
        o.modules = new LinkedHashSet<>(modules);
        o.ref = ref;
        return o;
    }

    /** Stable key for caching sessions per (path, options). */
    public String key() {
        return includeTests + "|" + String.join(",", modules) + "|" + (ref == null ? "" : ref);
    }
}

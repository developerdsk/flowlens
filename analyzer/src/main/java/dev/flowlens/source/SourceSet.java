package dev.flowlens.source;

import dev.flowlens.model.Diagnostic;

import java.util.ArrayList;
import java.util.List;

/** Everything collected from a project tree, ready for analysis. */
public class SourceSet {
    public final List<SourceFile> javaFiles = new ArrayList<>();
    /** MyBatis mapper XML files. */
    public final List<SourceFile> mapperXml = new ArrayList<>();
    /** Module names, when the build has more than one module with sources. */
    public final List<String> modules = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    /** .java files seen before module/test filtering. */
    public int javaFilesFound;
}

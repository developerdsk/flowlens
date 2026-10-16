package dev.flowlens.source;

/** One source file: a display/relative path (always '/'-separated) and its full text. */
public class SourceFile {
    public String path;
    public String content;
    /** Module the file belongs to (multi-module builds), or null. */
    public String module;

    public SourceFile() {}

    public SourceFile(String path, String content) {
        this.path = path;
        this.content = content;
    }

    public SourceFile(String path, String content, String module) {
        this(path, content);
        this.module = module;
    }
}

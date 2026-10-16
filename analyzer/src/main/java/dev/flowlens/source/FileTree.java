package dev.flowlens.source;

import java.io.IOException;
import java.util.List;

/** A read-only view of a project's files: the working tree on disk, or a git ref. */
public interface FileTree {
    /** All file paths, relative to the project root, '/'-separated. */
    List<String> list() throws IOException;

    byte[] read(String relativePath) throws IOException;

    /** Size in bytes, or -1 when unknown. */
    long size(String relativePath) throws IOException;
}

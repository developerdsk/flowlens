package dev.flowlens.source;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** The working tree on disk. Never descends into build output or tool folders. */
public class DiskTree implements FileTree {
    private final Path root;

    public DiskTree(Path root) {
        this.root = root;
    }

    @Override
    public List<String> list() throws IOException {
        List<String> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && SourceCollector.isExcludedDir(root.relativize(dir).toString().replace('\\', '/'))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) out.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // unreadable dir/file: skip, don't abort the walk
            }
        });
        return out;
    }

    @Override
    public byte[] read(String relativePath) throws IOException {
        return Files.readAllBytes(root.resolve(relativePath));
    }

    @Override
    public long size(String relativePath) throws IOException {
        return Files.size(root.resolve(relativePath));
    }

    /** Last-modified time + size, used as a cheap change fingerprint by watch mode. */
    public String fingerprint(String relativePath) throws IOException {
        Path p = root.resolve(relativePath);
        return Files.getLastModifiedTime(p).toMillis() + ":" + Files.size(p);
    }
}

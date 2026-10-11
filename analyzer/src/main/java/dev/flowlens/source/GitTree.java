package dev.flowlens.source;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The project as it was at a git ref (branch, tag, commit), read with the user's own
 * {@code git} binary — nothing is checked out or modified. Used by diff mode.
 */
public class GitTree implements FileTree {
    private final Path root;
    private final String ref;
    private String prefix;          // project dir relative to the repo top level ("" or "a/b/")
    private Path top;
    private Map<String, Long> sizes;

    public GitTree(Path root, String ref) {
        this.root = root;
        this.ref = ref;
    }

    private void init() throws IOException {
        if (top != null) return;
        if (ref.startsWith("-")) throw new IOException("Invalid git ref: " + ref);
        String topLevel = run(List.of("git", "-C", root.toString(), "rev-parse", "--show-toplevel")).trim();
        top = Path.of(topLevel);
        String rel = top.toRealPath().relativize(root.toRealPath()).toString().replace('\\', '/');
        prefix = rel.isEmpty() ? "" : rel + "/";
        // Validate the ref early so the user gets a clear message.
        run(List.of("git", "-C", top.toString(), "rev-parse", "--verify", "--quiet", ref + "^{commit}"));
    }

    @Override
    public List<String> list() throws IOException {
        init();
        String out = run(List.of("git", "-C", top.toString(), "ls-tree", "-r", "-l", "-z", "--full-tree", ref));
        List<String> files = new ArrayList<>();
        sizes = new HashMap<>();
        for (String entry : out.split("\0")) {
            if (entry.isEmpty()) continue;
            // "<mode> <type> <object> <size>\t<path>"
            int tab = entry.indexOf('\t');
            if (tab < 0) continue;
            String[] meta = entry.substring(0, tab).trim().split("\\s+");
            String path = entry.substring(tab + 1);
            if (meta.length < 4 || !"blob".equals(meta[1]) || !path.startsWith(prefix)) continue;
            String rel = path.substring(prefix.length());
            int slash = rel.lastIndexOf('/');
            if (slash > 0 && SourceCollector.isExcludedDir(rel.substring(0, slash))) continue;
            files.add(rel);
            try { sizes.put(rel, Long.parseLong(meta[3])); } catch (NumberFormatException ignored) { }
        }
        return files;
    }

    @Override
    public byte[] read(String relativePath) throws IOException {
        init();
        return runBytes(List.of("git", "-C", top.toString(), "show", ref + ":" + prefix + relativePath));
    }

    /** Read many files with a single {@code git cat-file --batch} process. */
    public Map<String, byte[]> readAll(List<String> relativePaths) throws IOException {
        init();
        Map<String, byte[]> result = new HashMap<>();
        ProcessBuilder pb = new ProcessBuilder("git", "-C", top.toString(), "cat-file", "--batch");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        Thread writer = new Thread(() -> {
            try (OutputStream os = p.getOutputStream()) {
                for (String rel : relativePaths) {
                    os.write((ref + ":" + prefix + rel + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) { }
        }, "git-cat-file-writer");
        writer.setDaemon(true);
        writer.start();
        try (InputStream in = new BufferedInputStream(p.getInputStream())) {
            for (String rel : relativePaths) {
                String header = readLine(in);
                if (header == null) break;
                if (header.endsWith(" missing")) continue;
                String[] h = header.split(" ");
                int size = Integer.parseInt(h[h.length - 1]);
                result.put(rel, in.readNBytes(size));
                in.read(); // trailing LF
            }
        }
        try { p.waitFor(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') buf.write(b);
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Override
    public long size(String relativePath) {
        Long s = sizes == null ? null : sizes.get(relativePath);
        return s == null ? -1 : s;
    }

    private static String run(List<String> cmd) throws IOException {
        return new String(runBytes(cmd), StandardCharsets.UTF_8);
    }

    private static byte[] runBytes(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("git is not installed or not on PATH (needed to compare with a git ref)", e);
        }
        byte[] out;
        byte[] err;
        try (InputStream o = p.getInputStream(); InputStream e = p.getErrorStream()) {
            out = o.readAllBytes();
            err = e.readAllBytes();
        }
        try {
            if (p.waitFor() != 0) {
                String msg = new String(err, StandardCharsets.UTF_8).trim();
                throw new IOException("git failed: " + (msg.isEmpty() ? String.join(" ", cmd.subList(3, cmd.size())) : msg));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        return out;
    }
}

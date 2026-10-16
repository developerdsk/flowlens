package dev.flowlens.source;

import dev.flowlens.model.Diagnostic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the sources to analyze in a project tree:
 * <ul>
 *   <li>skips build output and tool folders ({@code target/}, {@code build/}, {@code .git/}, …)</li>
 *   <li>skips test sources unless asked ({@code src/test/…})</li>
 *   <li>detects Maven/Gradle modules and can restrict to some of them</li>
 *   <li>decodes non-UTF-8 files instead of dropping them</li>
 *   <li>picks up MyBatis mapper XML</li>
 * </ul>
 */
public class SourceCollector {

    /** Folders that never hold hand-written sources, wherever they appear. */
    private static final Set<String> ALWAYS_SKIPPED = Set.of(
            "node_modules", "bower_components", "__pycache__");

    /** Build-output folders. Skipped unless under a {@code src/} folder (then they are packages). */
    private static final Set<String> OUTPUT_DIRS = Set.of("target", "build", "out", "bin", "buildSrc");

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");

    /** Larger files are almost always generated code; parsing them only costs time. */
    static final long MAX_JAVA_BYTES = 2 * 1024 * 1024;
    static final long MAX_XML_BYTES = 2 * 1024 * 1024;

    /**
     * Whether a directory (relative path, '/'-separated) should not be indexed.
     * Hidden folders, node_modules and build output are excluded — except generated sources
     * inside build output ({@code target/generated-sources/…}, {@code build/generated/…}), which
     * hold e.g. OpenAPI-generated API interfaces whose mappings controllers inherit.
     */
    public static boolean isExcludedDir(String relDir) {
        if (relDir == null || relDir.isEmpty()) return false;
        String[] seg = relDir.split("/");
        boolean underSrc = false;
        for (int i = 0; i < seg.length; i++) {
            String s = seg[i];
            if (s.isEmpty()) continue;
            if (s.startsWith(".") || ALWAYS_SKIPPED.contains(s)) return true;
            if (s.equals("src")) underSrc = true;
            if (!underSrc && OUTPUT_DIRS.contains(s)) {
                if (i == seg.length - 1) return false;           // descend, to reach generated sources
                return !seg[i + 1].startsWith("generated");
            }
        }
        return false;
    }

    /** True for files inside build output (generated sources); real sources win on name clashes. */
    public static boolean isGeneratedPath(String rel) {
        String[] seg = rel.split("/");
        for (int i = 0; i < seg.length - 1; i++) {
            if (seg[i].equals("src")) return false;
            if (OUTPUT_DIRS.contains(seg[i])) return true;
        }
        return false;
    }

    public static boolean isTestPath(String rel) {
        String p = "/" + rel;
        return p.contains("/src/test/") || p.contains("/src/it/") || p.contains("/src/integrationTest/")
                || p.contains("/src/testFixtures/") || p.contains("/src/intTest/")
                || p.contains("/generated-test-sources/") || p.contains("/generated/sources/annotationProcessor/java/test/");
    }

    public SourceSet collect(Path root, AnalysisOptions options) throws IOException {
        FileTree tree = options.ref == null || options.ref.isBlank()
                ? new DiskTree(root) : new GitTree(root, options.ref.trim());
        return collect(tree, root.getFileName() == null ? "project" : root.getFileName().toString(), options);
    }

    public SourceSet collect(FileTree tree, String rootName, AnalysisOptions options) throws IOException {
        SourceSet set = new SourceSet();
        List<String> all = tree.list();

        // Module directories = dirs holding a build file.
        Set<String> moduleDirs = new TreeSet<>();
        for (String f : all) {
            String name = f.substring(f.lastIndexOf('/') + 1);
            if (BUILD_FILES.contains(name)) {
                int slash = f.lastIndexOf('/');
                moduleDirs.add(slash < 0 ? "" : f.substring(0, slash));
            }
        }

        List<String> javaPaths = new ArrayList<>();
        List<String> xmlPaths = new ArrayList<>();
        Map<String, String> moduleOf = new HashMap<>();
        Set<String> modulesWithSources = new TreeSet<>();
        for (String f : all) {
            String lower = f.toLowerCase(Locale.ROOT);
            boolean java = lower.endsWith(".java");
            boolean xml = lower.endsWith(".xml");
            if (!java && !xml) continue;
            if (java) set.javaFilesFound++;
            if (!options.includeTests && isTestPath(f)) continue;
            String module = moduleFor(f, moduleDirs, rootName);
            if (java) {
                if (f.endsWith("package-info.java") || f.endsWith("module-info.java")) continue;
                modulesWithSources.add(module);
            }
            moduleOf.put(f, module);
            (java ? javaPaths : xmlPaths).add(f);
        }

        boolean multiModule = modulesWithSources.size() > 1;
        if (multiModule) set.modules.addAll(modulesWithSources);
        Set<String> wanted = options.modules;

        List<String> javaToRead = new ArrayList<>();
        for (String f : javaPaths) {
            if (multiModule && !wanted.isEmpty() && !wanted.contains(moduleOf.get(f))) continue;
            long size = tree.size(f);
            if (size > MAX_JAVA_BYTES) {
                set.diagnostics.add(new Diagnostic("warning", f, "skipped: file is larger than 2 MB (likely generated)"));
                continue;
            }
            javaToRead.add(f);
        }
        List<String> xmlToRead = new ArrayList<>();
        for (String f : xmlPaths) {
            if (multiModule && !wanted.isEmpty() && !wanted.contains(moduleOf.get(f))) continue;
            if (BUILD_FILES.contains(f.substring(f.lastIndexOf('/') + 1))) continue;
            if (tree.size(f) > MAX_XML_BYTES) continue;
            xmlToRead.add(f);
        }

        Map<String, byte[]> bulk = tree instanceof GitTree g ? g.readAll(concat(javaToRead, xmlToRead)) : null;

        for (String f : javaToRead) {
            String text = readText(tree, bulk, f, set);
            if (text != null) set.javaFiles.add(new SourceFile(f, text, multiModule ? moduleOf.get(f) : null));
        }
        for (String f : xmlToRead) {
            String text = readText(tree, bulk, f, set);
            if (text != null && text.contains("<mapper") && text.contains("namespace")) {
                set.mapperXml.add(new SourceFile(f, text, multiModule ? moduleOf.get(f) : null));
            }
        }
        return set;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String readText(FileTree tree, Map<String, byte[]> bulk, String f, SourceSet set) {
        try {
            byte[] bytes = bulk != null ? bulk.get(f) : tree.read(f);
            if (bytes == null) {
                set.diagnostics.add(new Diagnostic("warning", f, "could not read file"));
                return null;
            }
            TextDecoder.Decoded d = TextDecoder.decode(bytes);
            if (d.isFallback() && f.endsWith(".java")) {
                set.diagnostics.add(new Diagnostic("info", f, "not valid UTF-8; decoded as " + d.charset().name()));
            }
            return d.text();
        } catch (IOException e) {
            set.diagnostics.add(new Diagnostic("warning", f, "could not read file: " + e.getMessage()));
            return null;
        }
    }

    /** The nearest enclosing module directory; the root module is named after the project folder. */
    static String moduleFor(String file, Set<String> moduleDirs, String rootName) {
        String best = null;
        for (String dir : moduleDirs) {
            if (dir.isEmpty()) {
                if (best == null) best = dir;
            } else if (file.startsWith(dir + "/") && (best == null || dir.length() > best.length())) {
                best = dir;
            }
        }
        if (best == null || best.isEmpty()) return rootName;
        return best;
    }
}

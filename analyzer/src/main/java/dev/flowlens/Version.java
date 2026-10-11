package dev.flowlens;

import java.io.InputStream;
import java.util.Properties;

/** The engine version, read from the Maven-filtered flowlens.properties (single source: pom.xml). */
public final class Version {
    public static final String VERSION = load();
    /** Bumped whenever graph.json changes shape in a way the viewer must know about. */
    public static final int SCHEMA_VERSION = 2;

    private Version() {}

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/flowlens.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.startsWith("${")) return v;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "dev";
    }

    public static String label() {
        return "FlowLens " + VERSION;
    }
}

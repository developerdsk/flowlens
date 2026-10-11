package dev.flowlens;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies persistence calls and SQL/JPQL text into a concrete operation + target table. */
public final class SinkClassifier {

    public record Sink(String op, String table) {}

    /** A table name, optionally schema-qualified, each part optionally quoted: `s`.`t`, "s"."t", [s].[t]. */
    private static final String NAME = "((?:[`\"\\[]?[\\w$]+[`\"\\]]?)(?:\\.[`\"\\[]?[\\w$]+[`\"\\]]?)*)";
    private static final Pattern INSERT = Pattern.compile("(?is)\\binsert\\s+(?:ignore\\s+)?(?:into\\s+)?" + NAME);
    private static final Pattern REPLACE = Pattern.compile("(?is)\\breplace\\s+into\\s+" + NAME);
    private static final Pattern MERGE = Pattern.compile("(?is)\\bmerge\\s+into\\s+" + NAME);
    private static final Pattern UPDATE = Pattern.compile("(?is)\\bupdate\\s+" + NAME + "(?:\\s+\\w+)?\\s+set\\b");
    private static final Pattern DELETE = Pattern.compile("(?is)\\bdelete\\s+(?:from\\s+)?" + NAME);
    private static final Pattern SELECT = Pattern.compile("(?is)\\bselect\\b.*?\\bfrom\\s+" + NAME);
    private static final Pattern FROM = Pattern.compile("(?is)^\\s*from\\s+" + NAME);
    private static final Pattern CALL = Pattern.compile("(?is)^\\s*\\{?\\s*(?:call|exec|execute)\\s+" + NAME);
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\n]*");

    private SinkClassifier() {}

    /** Op inferred from Spring Data / MyBatis / DAO naming conventions. */
    public static String opFromMethodName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("saveorupdate") || n.startsWith("upsert") || n.startsWith("merge")) return "UPSERT";
        if (n.startsWith("save") || n.startsWith("persist") || n.startsWith("store")) return "UPSERT";
        if (n.startsWith("insert") || n.startsWith("add") || n.startsWith("create")) return "INSERT";
        if (n.startsWith("update") || n.startsWith("modify")) return "UPDATE";
        if (n.startsWith("delete") || n.startsWith("remove") || n.startsWith("purge")) return "DELETE";
        if (n.startsWith("find") || n.startsWith("get") || n.startsWith("read") || n.startsWith("query")
                || n.startsWith("count") || n.startsWith("exists") || n.startsWith("stream")
                || n.startsWith("search") || n.startsWith("select") || n.startsWith("list")
                || n.startsWith("load") || n.startsWith("fetch")) return "SELECT";
        return "CALL";
    }

    /**
     * Parse SQL or JPQL into op + table. The statement verb that appears first wins (so
     * {@code INSERT ... SELECT ... FROM x} is an INSERT). For JPQL the token after the verb is
     * an entity name, which is mapped to its physical table when the entity is known.
     */
    public static Sink parseQuery(String query, ProjectModel model) {
        if (query == null || query.isBlank()) return null;
        String q = COMMENTS.matcher(query).replaceAll(" ");
        q = stripLeadingCte(q);
        Sink best = null;
        int bestPos = Integer.MAX_VALUE;
        Object[][] candidates = {
                {INSERT, "INSERT"}, {REPLACE, "UPSERT"}, {MERGE, "UPSERT"}, {UPDATE, "UPDATE"},
                {DELETE, "DELETE"}, {SELECT, "SELECT"}, {CALL, "CALL"}};
        for (Object[] c : candidates) {
            Matcher m = ((Pattern) c[0]).matcher(q);
            if (m.find() && m.start() < bestPos) {
                String table = m.group(1);
                if (c[1].equals("DELETE") && table.equalsIgnoreCase("from")) continue;
                bestPos = m.start();
                best = new Sink((String) c[1], tableFor(table, model));
            }
        }
        if (best == null) {
            Matcher m = FROM.matcher(q); // JPQL shorthand: "from Account a where ..."
            if (m.find()) best = new Sink("SELECT", tableFor(m.group(1), model));
        }
        return best;
    }

    /** {@code WITH x AS (...) SELECT ...} -> the main statement. */
    private static String stripLeadingCte(String q) {
        String t = q.stripLeading();
        if (!t.regionMatches(true, 0, "with", 0, 4)) return q;
        int depth = 0;
        for (int i = 4; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    int j = i + 1;
                    while (j < t.length() && (Character.isWhitespace(t.charAt(j)) || t.charAt(j) == ',')) j++;
                    String rest = t.substring(j);
                    // another CTE follows ("name AS (")?
                    if (rest.matches("(?is)^\\w+\\s+as\\s*\\(.*")) continue;
                    return rest;
                }
            }
        }
        return q;
    }

    private static String tableFor(String token, ProjectModel model) {
        if (token == null) return "?";
        if (token.startsWith("#{") || token.startsWith("${") || token.startsWith(":")) return "?";
        return model == null ? ProjectModel.normalizeTable(token) : model.tableFor(token);
    }
}

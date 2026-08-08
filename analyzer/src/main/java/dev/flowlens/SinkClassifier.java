package dev.flowlens;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classifies a persistence call into a concrete DB operation + target table. */
public class SinkClassifier {

    public static class Sink {
        public final String op;
        public final String table;
        public Sink(String op, String table) {
            this.op = op;
            this.table = table;
        }
    }

    private static final Pattern INSERT = Pattern.compile("(?is)\\binsert\\s+into\\s+[`\"]?([\\w.]+)");
    private static final Pattern UPDATE = Pattern.compile("(?is)\\bupdate\\s+[`\"]?([\\w.]+)");
    private static final Pattern DELETE = Pattern.compile("(?is)\\bdelete\\s+from\\s+[`\"]?([\\w.]+)");
    private static final Pattern SELECT = Pattern.compile("(?is)\\bfrom\\s+[`\"]?([\\w.]+)");

    /** Classify a Spring Data repository call by method name (and @Query if present). */
    public static Sink classifyRepoMethod(String methodName,
                                          MethodDeclaration repoMethod,
                                          String entitySimpleName,
                                          ProjectModel model) {
        // A @Query annotation is authoritative over the derived-name heuristic.
        if (repoMethod != null) {
            String q = repoMethod.getAnnotationByName("Query")
                    .flatMap(a -> a.findFirst(StringLiteralExpr.class))
                    .map(StringLiteralExpr::asString)
                    .orElse(null);
            if (q != null) {
                Sink s = parseQuery(q, model);
                if (s != null) return s;
            }
        }
        String table = tableFor(entitySimpleName, model);
        return new Sink(opFromMethodName(methodName), table);
    }

    /** Op inferred from Spring Data derived-query naming conventions. */
    public static String opFromMethodName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("save") || n.startsWith("persist")) return "UPSERT";
        if (n.startsWith("insert")) return "INSERT";
        if (n.startsWith("update")) return "UPDATE";
        if (n.startsWith("delete") || n.startsWith("remove")) return "DELETE";
        if (n.startsWith("find") || n.startsWith("get") || n.startsWith("read")
                || n.startsWith("query") || n.startsWith("count") || n.startsWith("exists")
                || n.startsWith("stream") || n.startsWith("search")) return "SELECT";
        return "CALL";
    }

    /**
     * Parse a raw SQL or JPQL string into op + table. For JPQL the token after the
     * verb is an entity name, which we map to its physical table when known.
     */
    public static Sink parseQuery(String query, ProjectModel model) {
        Matcher m;
        if ((m = INSERT.matcher(query)).find()) return new Sink("INSERT", tableFor(m.group(1), model));
        if ((m = DELETE.matcher(query)).find()) return new Sink("DELETE", tableFor(m.group(1), model));
        if ((m = UPDATE.matcher(query)).find()) return new Sink("UPDATE", tableFor(m.group(1), model));
        if ((m = SELECT.matcher(query)).find()) return new Sink("SELECT", tableFor(m.group(1), model));
        return null;
    }

    /** Map an entity name to its table; leave a real table name untouched. */
    private static String tableFor(String token, ProjectModel model) {
        if (token == null) return "?";
        String simple = token.contains(".") ? token.substring(token.lastIndexOf('.') + 1) : token;
        String mapped = model.entityTable.get(simple);
        return mapped != null ? mapped : token;
    }
}

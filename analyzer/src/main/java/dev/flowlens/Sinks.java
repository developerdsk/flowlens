package dev.flowlens;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.flowlens.ProjectModel.TypeInfo;
import dev.flowlens.TypeResolver.RType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Recognizes calls that leave the application: database access and outbound HTTP/messaging. */
public class Sinks {

    /** A detected sink. {@code type} is "db" or "external". */
    public record Hit(String type, String op, String table, String tech, String detail, String edgeLabel, String confidence) {
        public String nodeId() {
            return "db".equals(type) ? "DB:" + op + ":" + table : "EXT:" + tech + ":" + op + ":" + table;
        }

        public String nodeLabel() {
            if ("db".equals(type)) return op + " " + table;
            if ("http".equals(tech)) return op + " " + table;
            return tech.toUpperCase(Locale.ROOT) + " → " + table;
        }
    }

    private static final Set<String> JDBC = Set.of("JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcOperations",
            "NamedParameterJdbcOperations", "SimpleJdbcInsert", "SimpleJdbcCall");
    private static final Set<String> JPA = Set.of("EntityManager", "Session", "StatelessSession", "HibernateTemplate");
    private static final Set<String> MONGO = Set.of("MongoTemplate", "ReactiveMongoTemplate", "MongoOperations", "ReactiveMongoOperations");
    private static final Set<String> REDIS = Set.of("RedisTemplate", "StringRedisTemplate", "ReactiveRedisTemplate",
            "ReactiveStringRedisTemplate", "RedisOperations", "ValueOperations", "HashOperations", "ListOperations",
            "SetOperations", "ZSetOperations", "RedissonClient", "Jedis", "JedisPool", "RedisCommands");
    private static final Set<String> KAFKA = Set.of("KafkaTemplate", "ReactiveKafkaProducerTemplate", "KafkaProducer", "Producer", "KafkaOperations");
    private static final Set<String> RABBIT = Set.of("RabbitTemplate", "AmqpTemplate", "RabbitOperations", "RabbitMessagingTemplate");
    private static final Set<String> JMS = Set.of("JmsTemplate", "JmsMessagingTemplate", "JmsOperations");
    private static final Set<String> SQS = Set.of("SqsTemplate", "SnsTemplate", "QueueMessagingTemplate", "NotificationMessagingTemplate", "SqsClient", "SnsClient");
    private static final Set<String> REST_TEMPLATE = Set.of("RestTemplate", "RestOperations", "TestRestTemplate", "OAuth2RestTemplate");
    private static final Set<String> FLUENT_HTTP = Set.of("WebClient", "RestClient", "Builder");
    private static final Set<String> HTTP_VERBS = Set.of("get", "post", "put", "delete", "patch", "head", "options");
    /** Method annotations of declarative HTTP clients -> verb. */
    private static final Map<String, String> CLIENT_VERBS = Map.ofEntries(Map.entry("GetMapping", "GET"), Map.entry("PostMapping", "POST"),
            Map.entry("PutMapping", "PUT"), Map.entry("DeleteMapping", "DELETE"), Map.entry("PatchMapping", "PATCH"),
            Map.entry("GetExchange", "GET"), Map.entry("PostExchange", "POST"), Map.entry("PutExchange", "PUT"),
            Map.entry("DeleteExchange", "DELETE"), Map.entry("PatchExchange", "PATCH"),
            Map.entry("GET", "GET"), Map.entry("POST", "POST"), Map.entry("PUT", "PUT"), Map.entry("DELETE", "DELETE"));

    private final ProjectModel model;
    private final TypeResolver types;

    public Sinks(ProjectModel model, TypeResolver types) {
        this.model = model;
        this.types = types;
    }

    // ------------------------------------------------------------------------------------------
    // project-declared sinks
    // ------------------------------------------------------------------------------------------

    /** Spring Data / DAO repository call. */
    public Hit repository(TypeInfo repo, String name, Expression call) {
        if (repo.isMyBatisMapper) return mybatis(repo, name);
        MethodDeclaration m = firstDeclared(repo, name);
        String query = null;
        SinkClassifier.Sink s = null;
        if (m != null) {
            Optional<AnnotationExpr> q = Annotations.get(m, "Query");
            if (q.isPresent()) {
                query = Annotations.firstString(q.get(), model, repo, "value").orElse(null);
                s = SinkClassifier.parseQuery(query, model);
            }
            if (s == null) {
                Hit annotated = sqlAnnotation(m, repo, repo.repoTech);
                if (annotated != null) return annotated;
            }
        }
        if (s == null) {
            String table = repo.repoEntity != null ? model.tableFor(repo.repoEntity) : guessTable(repo.simpleName);
            s = new SinkClassifier.Sink(SinkClassifier.opFromMethodName(name), table);
        }
        String tech = repo.repoTech == null ? "jpa" : repo.repoTech;
        return new Hit("db", s.op(), s.table(), tech, oneLine(query), repo.simpleName + "." + name + "()", confidence(s.table()));
    }

    private Hit mybatis(TypeInfo mapper, String name) {
        for (TypeInfo t : model.hierarchy(mapper)) {
            ProjectModel.MapperStatement st = model.mapperStatements.get(t.qualifiedName + "." + name);
            if (st != null) {
                return new Hit("db", st.op(), st.table(), "mybatis", oneLine(st.sql()), mapper.simpleName + "." + name + "()", confidence(st.table()));
            }
        }
        MethodDeclaration m = firstDeclared(mapper, name);
        if (m != null) {
            Hit h = sqlAnnotation(m, mapper, "mybatis");
            if (h != null) return h;
        }
        String table = mapper.repoEntity != null ? model.tableFor(mapper.repoEntity) : guessTable(mapper.simpleName);
        return new Hit("db", SinkClassifier.opFromMethodName(name), table, "mybatis", null,
                mapper.simpleName + "." + name + "()", "low");
    }

    /** MyBatis-style @Select/@Insert/@Update/@Delete (also used by some DAO libraries). */
    private Hit sqlAnnotation(MethodDeclaration m, TypeInfo owner, String tech) {
        for (String a : List.of("Select", "Insert", "Update", "Delete", "SqlQuery", "SqlUpdate")) {
            Optional<AnnotationExpr> anno = Annotations.get(m, a);
            if (anno.isEmpty()) continue;
            String sql = String.join(" ", Annotations.stringValues(anno.get(), model, owner, "value"));
            SinkClassifier.Sink s = SinkClassifier.parseQuery(sql, model);
            String op = s != null ? s.op() : a.startsWith("Sql") ? (a.equals("SqlQuery") ? "SELECT" : "UPDATE") : a.toUpperCase(Locale.ROOT);
            String table = s != null ? s.table() : "?";
            return new Hit("db", op, table, tech == null ? "jdbc" : tech, oneLine(sql),
                    owner.simpleName + "." + m.getNameAsString() + "()", confidence(table));
        }
        for (String a : List.of("SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider")) {
            if (Annotations.get(m, a).isPresent()) {
                String op = a.replace("Provider", "").toUpperCase(Locale.ROOT);
                String table = owner.repoEntity != null ? model.tableFor(owner.repoEntity) : guessTable(owner.simpleName);
                return new Hit("db", op, table, tech, null, owner.simpleName + "." + m.getNameAsString() + "()", "low");
            }
        }
        return null;
    }

    /** Declarative HTTP client (OpenFeign, Spring @HttpExchange, MicroProfile REST client). */
    public Hit httpClient(TypeInfo client, String name) {
        MethodDeclaration m = firstDeclared(client, name);
        String verb = "GET";
        String path = "";
        if (m != null) {
            for (AnnotationExpr a : m.getAnnotations()) {
                String an = Annotations.name(a);
                if (CLIENT_VERBS.containsKey(an)) {
                    verb = CLIENT_VERBS.get(an);
                    path = Annotations.firstString(a, model, client, "value", "path", "url").orElse(path);
                } else if (an.equals("RequestMapping") || an.equals("HttpExchange")) {
                    List<String> v = Annotations.enumValues(a, "method");
                    if (v.isEmpty()) v = Annotations.stringValues(a, model, client, "method"); // @HttpExchange(method = "GET")
                    if (!v.isEmpty()) verb = v.get(0).toUpperCase(Locale.ROOT);
                    path = Annotations.firstString(a, model, client, "value", "path", "url").orElse(path);
                } else if (an.equals("RequestLine")) { // Feign core: @RequestLine("GET /users/{id}")
                    String line = Annotations.firstString(a, model, client, "value").orElse("GET /");
                    String[] parts = line.trim().split("\\s+", 2);
                    verb = parts[0];
                    path = parts.length > 1 ? parts[1] : "";
                } else if (an.equals("Path")) {
                    path = Annotations.firstString(a, model, client, "value").orElse(path);
                }
            }
        }
        String base = client.feignName == null ? client.simpleName : client.feignName;
        String target = base + EntryPoints.joinPath(client.feignPath, path);
        if (target.endsWith("/") && target.length() > 1) target = target.substring(0, target.length() - 1);
        return new Hit("external", verb, target, "http", verb + " " + target, client.simpleName + "." + name + "()", "high");
    }

    // ------------------------------------------------------------------------------------------
    // library sinks, detected on the outermost call of a fluent chain
    // ------------------------------------------------------------------------------------------

    /** Detect a sink for the whole call chain ending at {@code outer}. */
    public Hit library(MethodCallExpr outer) {
        List<MethodCallExpr> chain = new ArrayList<>();
        Expression cur = outer;
        while (cur instanceof MethodCallExpr c) {
            chain.add(c);
            cur = c.getScope().orElse(null);
        }
        Collections.reverse(chain); // root-first
        if (cur == null) return null;
        RType root = types.typeOf(cur);
        if (root == null || root.project != null) return null;
        String rt = root.name;
        MethodCallExpr first = chain.get(0);
        String fn = first.getNameAsString();

        if (JDBC.contains(rt)) return jdbc(first, fn);
        if (rt.equals("JdbcClient")) return jdbcClient(chain);
        if (JPA.contains(rt)) return jpa(first, fn);
        if (MONGO.contains(rt)) return mongo(first, fn);
        if (rt.equals("DSLContext")) return jooq(chain);
        if (REDIS.contains(rt)) return redis(chain);
        if (KAFKA.contains(rt)) return messaging(chain, "kafka", Set.of("send", "sendDefault", "sendAndReceive"));
        if (RABBIT.contains(rt)) return messaging(chain, "rabbit", Set.of("convertAndSend", "send", "convertSendAndReceive", "sendAndReceive"));
        if (JMS.contains(rt)) return messaging(chain, "jms", Set.of("convertAndSend", "send", "sendAndReceive", "convertSendAndReceive"));
        if (rt.equals("StreamBridge")) return messaging(chain, "stream", Set.of("send"));
        if (SQS.contains(rt)) return messaging(chain, rt.startsWith("Sns") || rt.startsWith("Notification") ? "sns" : "sqs",
                Set.of("send", "convertAndSend", "sendMessage", "publish", "sendAsync"));
        if (REST_TEMPLATE.contains(rt)) return restTemplate(first, fn);
        if (FLUENT_HTTP.contains(rt)) return fluentHttp(chain);
        return null;
    }

    private Hit jdbc(MethodCallExpr call, String fn) {
        if (call.getArguments().isEmpty()) return null;
        if (!(fn.startsWith("query") || fn.startsWith("update") || fn.startsWith("batchUpdate") || fn.startsWith("execute")
                || fn.startsWith("call"))) return null;
        String sql = firstString(call);
        SinkClassifier.Sink s = SinkClassifier.parseQuery(sql, model);
        String op = s != null ? s.op() : fn.startsWith("query") ? "SELECT" : "UPDATE";
        String table = s != null ? s.table() : "?";
        return new Hit("db", op, table, "jdbc", oneLine(sql), "jdbc." + fn + "()", confidence(table));
    }

    private Hit jdbcClient(List<MethodCallExpr> chain) {
        String sql = null;
        String terminal = chain.get(chain.size() - 1).getNameAsString();
        for (MethodCallExpr c : chain) if (c.getNameAsString().equals("sql") && !c.getArguments().isEmpty()) sql = firstString(c);
        if (sql == null) return null;
        SinkClassifier.Sink s = SinkClassifier.parseQuery(sql, model);
        String op = s != null ? s.op() : terminal.equals("update") ? "UPDATE" : "SELECT";
        String table = s != null ? s.table() : "?";
        return new Hit("db", op, table, "jdbc", oneLine(sql), "jdbcClient." + terminal + "()", confidence(table));
    }

    private Hit jpa(MethodCallExpr call, String fn) {
        String op;
        String table = "?";
        String detail = null;
        switch (fn) {
            case "persist", "save", "insert" -> op = "INSERT";
            case "merge", "saveOrUpdate", "update" -> op = "UPSERT";
            case "remove", "delete" -> op = "DELETE";
            case "find", "get", "getReference", "load", "byId" -> op = "SELECT";
            case "createQuery", "createNativeQuery", "createSQLQuery", "createMutationQuery", "createSelectionQuery",
                 "createNativeMutationQuery" -> {
                detail = firstString(call);
                SinkClassifier.Sink s = SinkClassifier.parseQuery(detail, model);
                if (s == null) {
                    op = fn.contains("Mutation") ? "UPDATE" : "SELECT";
                } else {
                    op = s.op();
                    table = s.table();
                }
                return new Hit("db", op, table, "jpa", oneLine(detail), "em." + fn + "()", confidence(table));
            }
            case "createNamedQuery" -> op = "SELECT";
            default -> { return null; }
        }
        for (Expression a : call.getArguments()) {
            String t = entityTableOf(a);
            if (t != null) { table = t; break; }
        }
        return new Hit("db", op, table, "jpa", detail, "em." + fn + "()", confidence(table));
    }

    private Hit mongo(MethodCallExpr call, String fn) {
        String op;
        String f = fn.toLowerCase(Locale.ROOT);
        if (f.startsWith("findandremove") || f.startsWith("findallandremove") || f.startsWith("remove")) op = "DELETE";
        else if (f.startsWith("findandmodify") || f.startsWith("findandreplace") || f.startsWith("update")) op = "UPDATE";
        else if (f.startsWith("upsert") || f.equals("save")) op = "UPSERT";
        else if (f.startsWith("insert")) op = "INSERT";
        else if (f.startsWith("find") || f.startsWith("count") || f.startsWith("exists") || f.startsWith("aggregate")
                || f.startsWith("query") || f.startsWith("stream") || f.startsWith("estimatedcount")) op = "SELECT";
        else return null;
        String table = null;
        for (Expression a : call.getArguments()) {
            table = entityTableOf(a);
            if (table != null) break;
        }
        if (table == null && !call.getArguments().isEmpty()) {
            List<String> s = Annotations.strings(call.getArgument(call.getArguments().size() - 1), model, model.enclosingType(call));
            if (!s.isEmpty()) table = ProjectModel.normalizeTable(s.get(0));
        }
        if (table == null) table = "?";
        return new Hit("db", op, table, "mongo", null, "mongo." + fn + "()", confidence(table));
    }

    private Hit jooq(List<MethodCallExpr> chain) {
        for (MethodCallExpr c : chain) {
            String op = switch (c.getNameAsString()) {
                case "insertInto" -> "INSERT";
                case "mergeInto" -> "UPSERT";
                case "update" -> "UPDATE";
                case "deleteFrom", "delete" -> "DELETE";
                case "selectFrom", "from", "fetchCount", "fetchExists", "fetch" -> "SELECT";
                default -> null;
            };
            if (op == null || c.getArguments().isEmpty()) continue;
            String table = jooqTable(c.getArgument(0));
            if (table == null) continue;
            return new Hit("db", op, table, "jooq", null, "dsl." + c.getNameAsString() + "()", "high");
        }
        return null;
    }

    private static String jooqTable(Expression e) {
        if (e instanceof MethodCallExpr m && m.getScope().isPresent() && (m.getNameAsString().equals("as"))) {
            return jooqTable(m.getScope().get());
        }
        if (e instanceof FieldAccessExpr f) return f.getNameAsString().toLowerCase(Locale.ROOT);
        if (e instanceof NameExpr n && n.getNameAsString().equals(n.getNameAsString().toUpperCase(Locale.ROOT))) {
            return n.getNameAsString().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private Hit redis(List<MethodCallExpr> chain) {
        MethodCallExpr terminal = chain.get(chain.size() - 1);
        String fn = terminal.getNameAsString().toLowerCase(Locale.ROOT);
        if (fn.startsWith("opsfor") || fn.startsWith("bound") || fn.equals("execute") && chain.size() == 1) return null;
        String op;
        if (fn.startsWith("delete") || fn.startsWith("remove") || fn.startsWith("pop") || fn.startsWith("leftpop")
                || fn.startsWith("rightpop") || fn.equals("del") || fn.equals("unlink") || fn.startsWith("hdel")) op = "DELETE";
        else if (fn.startsWith("get") || fn.startsWith("has") || fn.startsWith("members") || fn.startsWith("range")
                || fn.startsWith("size") || fn.startsWith("keys") || fn.startsWith("entries") || fn.startsWith("multiget")
                || fn.startsWith("ismember") || fn.startsWith("score") || fn.startsWith("rank") || fn.equals("hget")
                || fn.equals("exists") || fn.startsWith("scan")) op = "SELECT";
        else if (fn.startsWith("set") || fn.startsWith("put") || fn.startsWith("add") || fn.startsWith("push")
                || fn.startsWith("leftpush") || fn.startsWith("rightpush") || fn.startsWith("increment")
                || fn.startsWith("decrement") || fn.startsWith("append") || fn.startsWith("expire") || fn.equals("hset")
                || fn.startsWith("multiset") || fn.startsWith("convertandsend") || fn.equals("publish")) op = "UPSERT";
        else return null;
        String keyspace = "redis";
        if (!terminal.getArguments().isEmpty()) {
            List<String> k = Annotations.strings(terminal.getArgument(0), model, model.enclosingType(terminal));
            if (!k.isEmpty()) {
                String key = k.get(0);
                int cut = indexOfAny(key, ":{");
                String prefix = cut > 0 ? key.substring(0, cut) : key;
                if (!prefix.isBlank() && prefix.length() <= 40) keyspace = "redis:" + prefix;
            }
        }
        return new Hit("db", op, keyspace, "redis", null, "redis." + terminal.getNameAsString() + "()", "medium");
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (char c : chars.toCharArray()) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private Hit messaging(List<MethodCallExpr> chain, String tech, Set<String> sendNames) {
        for (MethodCallExpr c : chain) {
            String fn = c.getNameAsString();
            if (!sendNames.contains(fn)) continue;
            String dest = "?";
            if (fn.equals("sendDefault")) {
                dest = "default";
            } else if (!c.getArguments().isEmpty()) {
                Expression a0 = c.getArgument(0);
                if (a0 instanceof ObjectCreationExpr oc && !oc.getArguments().isEmpty()) a0 = oc.getArgument(0); // new ProducerRecord<>(topic, ...)
                List<String> s = Annotations.strings(a0, model, model.enclosingType(c));
                if ("rabbit".equals(tech) && c.getArguments().size() >= 3) {
                    List<String> rk = Annotations.strings(c.getArgument(1), model, model.enclosingType(c));
                    dest = (s.isEmpty() ? "?" : s.get(0)) + "/" + (rk.isEmpty() ? "?" : rk.get(0));
                } else if (!s.isEmpty()) {
                    dest = s.get(0);
                } else if (c.getArguments().size() == 1 && ("rabbit".equals(tech) || "jms".equals(tech))) {
                    dest = "default"; // convertAndSend(message): the template's default destination
                }
            }
            return new Hit("external", "SEND", dest, tech, null, tech + "." + fn + "()", "?".equals(dest) ? "low" : "high");
        }
        return null;
    }

    private Hit restTemplate(MethodCallExpr call, String fn) {
        String verb;
        if (fn.startsWith("getFor")) verb = "GET";
        else if (fn.startsWith("postFor")) verb = "POST";
        else if (fn.equals("put")) verb = "PUT";
        else if (fn.equals("delete")) verb = "DELETE";
        else if (fn.startsWith("patchFor")) verb = "PATCH";
        else if (fn.equals("exchange") || fn.equals("execute")) {
            verb = call.getArguments().size() > 1 && call.getArgument(1) instanceof FieldAccessExpr f ? f.getNameAsString()
                    : call.getArguments().size() > 1 && call.getArgument(1) instanceof NameExpr n ? n.getNameAsString() : "?";
        } else if (fn.equals("headForHeaders")) verb = "HEAD";
        else if (fn.equals("optionsForAllow")) verb = "OPTIONS";
        else return null;
        String url = call.getArguments().isEmpty() ? null : firstStringOf(call.getArgument(0), call);
        String target = url == null ? "?" : url;
        return new Hit("external", verb, target, "http", verb + " " + target, "rest." + fn + "()", url == null ? "low" : "high");
    }

    private Hit fluentHttp(List<MethodCallExpr> chain) {
        String verb = null;
        String uri = null;
        for (MethodCallExpr c : chain) {
            String fn = c.getNameAsString();
            if (verb == null && HTTP_VERBS.contains(fn) && c.getArguments().isEmpty()) verb = fn.toUpperCase(Locale.ROOT);
            if (verb == null && fn.equals("method") && !c.getArguments().isEmpty()) {
                Expression a = c.getArgument(0);
                verb = a instanceof FieldAccessExpr f ? f.getNameAsString() : a instanceof NameExpr n ? n.getNameAsString() : "?";
            }
            if (uri == null && (fn.equals("uri") || fn.equals("baseUrl")) && !c.getArguments().isEmpty()) {
                uri = firstStringOf(c.getArgument(0), c);
            }
        }
        if (verb == null) return null;
        boolean looksHttp = false;
        for (MethodCallExpr c : chain) {
            if (Set.of("uri", "retrieve", "exchange", "exchangeToMono", "exchangeToFlux", "toEntity", "body",
                    "bodyValue", "contentType", "accept", "header", "headers").contains(c.getNameAsString())) looksHttp = true;
        }
        if (!looksHttp) return null;
        String target = uri == null ? "?" : uri;
        return new Hit("external", verb, target, "http", verb + " " + target, "http." + verb.toLowerCase(Locale.ROOT) + "()",
                uri == null ? "low" : "high");
    }

    // ------------------------------------------------------------------------------------------

    private MethodDeclaration firstDeclared(TypeInfo t, String name) {
        List<ProjectModel.MethodRef> ms = model.methodsNamed(t, name);
        return ms.isEmpty() ? null : ms.get(0).method();
    }

    /** First argument that evaluates to a string (SQL text, URL, ...). */
    private String firstString(MethodCallExpr call) {
        for (Expression a : call.getArguments()) {
            String s = firstStringOf(a, call);
            if (s != null) return s;
        }
        return null;
    }

    private String firstStringOf(Expression e, MethodCallExpr site) {
        List<String> s = Annotations.strings(e, model, model.enclosingType(site));
        return s.isEmpty() ? null : s.get(0);
    }

    /** Table of an entity passed as an argument: {@code Foo.class}, an entity instance, or {@code new Foo()}. */
    private String entityTableOf(Expression a) {
        if (a instanceof ClassExpr c) {
            TypeInfo t = model.resolve(ProjectModel.rawName(c.getType()), model.enclosingType(a));
            if (t != null && t.isEntity) return t.table;
            return null;
        }
        RType r = types.typeOf(a);
        if (r != null && r.project != null && r.project.isEntity) return r.project.table;
        if (r != null && (r.name.equals("List") || r.name.equals("Collection") || r.name.equals("Set")) && r.arg(0) != null
                && r.arg(0).project != null && r.arg(0).project.isEntity) return r.arg(0).project.table;
        return null;
    }

    /** {@code UserMapper} / {@code UserDao} / {@code UserRepository} -> {@code user}. */
    private static String guessTable(String typeName) {
        String base = typeName.replaceAll("(Mapper|Dao|DAO|Repository|Repo|Impl)+$", "");
        return base.isEmpty() ? "?" : ProjectModel.snake(base);
    }

    private static String confidence(String table) {
        return table == null || "?".equals(table) ? "low" : "high";
    }

    static String oneLine(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 500 ? t.substring(0, 500) + "…" : t;
    }
}

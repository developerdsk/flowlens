package dev.flowlens;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.type.Type;
import dev.flowlens.model.Graph;
import dev.flowlens.model.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Builds a per-endpoint data-flow graph from a Java source tree:
 * controller entry -> service/repository call chain -> database sinks,
 * capturing branch conditions and object mutations along the way.
 */
public class FlowAnalyzer {

    private static final Set<String> MAPPING_ANNOS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping");
    private static final Set<String> JDBC_TYPES = Set.of("JdbcTemplate", "NamedParameterJdbcTemplate");

    private static final DataKey<String> FILE_PATH = new DataKey<>() {};

    private final ProjectModel model = new ProjectModel();
    private final Graph graph = new Graph();

    /** A single Java source file: a display/relative path and its full text. */
    public static class SourceFile {
        public String path;
        public String content;
        public SourceFile() {}
        public SourceFile(String path, String content) { this.path = path; this.content = content; }
    }

    /** Analyze a source tree on disk. */
    public Graph analyze(Path sourceRoot) throws IOException {
        return analyzeSources(collectSources(sourceRoot));
    }

    /** Analyze source files provided in memory (e.g. uploaded from the browser). */
    public Graph analyzeSources(List<SourceFile> sources) {
        List<CompilationUnit> units = new ArrayList<>();
        for (SourceFile sf : sources) {
            if (sf == null || sf.content == null) continue;
            try {
                CompilationUnit cu = StaticJavaParser.parse(sf.content);
                if (sf.path != null) cu.setData(FILE_PATH, sf.path);
                units.add(cu);
            } catch (Exception e) {
                System.err.println("[warn] skipped (parse error): " + sf.path + " — " + e.getMessage());
            }
        }
        model.index(units);
        buildGraph();
        return graph;
    }

    private void buildGraph() {
        for (ProjectModel.TypeInfo type : model.byQualifiedName.values()) {
            if (!ProjectModel.CONTROLLER.equals(type.layer)) continue;
            String classPath = classLevelPath(type);
            for (MethodDeclaration method : type.decl.getMethods()) {
                findMapping(method).ifPresent(anno -> {
                    String endpointId = "EP:" + type.qualifiedName + "#" + method.getNameAsString();
                    Node ep = graph.upsert(new Node(endpointId,
                            type.simpleName + "." + method.getNameAsString() + "()", "endpoint"));
                    ep.layer = ProjectModel.CONTROLLER;
                    ep.http = httpMethod(anno) + " " + joinPath(classPath, annoPath(anno));
                    fillDetails(method, ep);

                    Set<String> visited = new HashSet<>();
                    visited.add(endpointId);
                    walk(method, endpointId, type, visited, 0);
                });
            }
        }
    }

    // --- the call-chain walk -------------------------------------------------

    private void walk(MethodDeclaration md, String nodeId, ProjectModel.TypeInfo owner,
                      Set<String> visited, int depth) {
        if (depth > 40 || md.getBody().isEmpty()) return;

        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            String recvSimple = resolveReceiverType(call.getScope().orElse(null), md, owner);
            ProjectModel.TypeInfo recvType = model.bySimple(recvSimple);

            // 1. Spring Data repository sink
            if (recvType != null && recvType.isRepository) {
                MethodDeclaration repoMethod = firstMethod(recvType, name);
                SinkClassifier.Sink s = SinkClassifier.classifyRepoMethod(
                        name, repoMethod, recvType.repoEntitySimpleName, model);
                linkDb(nodeId, s.op, s.table, recvType.simpleName + "." + name + "()");
                continue;
            }
            // 2. JdbcTemplate raw SQL sink
            if (recvSimple != null && JDBC_TYPES.contains(recvSimple)) {
                String sql = firstStringArg(call);
                SinkClassifier.Sink s = sql == null ? null : SinkClassifier.parseQuery(sql, model);
                if (s != null) linkDb(nodeId, s.op, s.table, "jdbc." + name + "()");
                else linkDb(nodeId, "CALL", "?", "jdbc." + name + "()");
                continue;
            }
            // 3. JPA EntityManager sink
            if ("EntityManager".equals(recvSimple)) {
                String op = SinkClassifier.opFromMethodName(name);
                String table = entityArgTable(call);
                linkDb(nodeId, op, table, "em." + name + "()");
                continue;
            }
            // 4. Project method call -> recurse (skip data plumbing: entity/DTO accessors)
            boolean plumbing = recvType != null
                    && (ProjectModel.ENTITY.equals(recvType.layer) || ProjectModel.OTHER.equals(recvType.layer))
                    && isAccessor(name);
            if (recvType != null && !ProjectModel.ENTITY.equals(recvType.layer) && !plumbing) {
                MethodDeclaration callee = pickOverload(recvType, name, call.getArguments().size());
                if (callee != null) {
                    String calleeId = methodNodeId(recvType, callee);
                    graph.addEdge(nodeId, calleeId, name);
                    if (visited.add(calleeId)) {
                        Node cn = graph.upsert(new Node(calleeId,
                                recvType.simpleName + "." + callee.getNameAsString() + "()", "method"));
                        cn.layer = recvType.layer;
                        fillDetails(callee, cn);
                        walk(callee, calleeId, recvType, visited, depth + 1);
                    }
                }
            }
        }
    }

    private void linkDb(String fromId, String op, String table, String edgeLabel) {
        String dbId = "DB:" + op + ":" + table;
        Node db = graph.upsert(new Node(dbId, op + " " + table, "db"));
        db.layer = "db";
        db.op = op;
        db.table = table;
        graph.addEdge(fromId, dbId, edgeLabel);
    }

    // --- receiver type resolution (heuristic, no external jars needed) -------

    private String resolveReceiverType(Expression scope, MethodDeclaration method,
                                       ProjectModel.TypeInfo owner) {
        if (scope == null || scope.isThisExpr()) return owner.simpleName;
        if (scope.isEnclosedExpr()) return resolveReceiverType(scope.asEnclosedExpr().getInner(), method, owner);
        if (scope.isCastExpr()) return simpleTypeName(scope.asCastExpr().getType());
        if (scope.isObjectCreationExpr()) return scope.asObjectCreationExpr().getType().getNameAsString();
        if (scope.isFieldAccessExpr()) {
            return lookupName(scope.asFieldAccessExpr().getNameAsString(), method, owner);
        }
        if (scope.isNameExpr()) {
            return lookupName(scope.asNameExpr().getNameAsString(), method, owner);
        }
        return null; // chained calls, array access, etc. — not resolved in the MVP
    }

    /** Resolve a bare name to its declared type: parameter, local var, then field. */
    private String lookupName(String name, MethodDeclaration method, ProjectModel.TypeInfo owner) {
        for (var p : method.getParameters()) {
            if (p.getNameAsString().equals(name)) return simpleTypeName(p.getType());
        }
        for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(name)) return simpleTypeName(v.getType());
        }
        for (var field : owner.decl.getFields()) {
            for (VariableDeclarator v : field.getVariables()) {
                if (v.getNameAsString().equals(name)) return simpleTypeName(v.getType());
            }
        }
        return null;
    }

    private static String simpleTypeName(Type t) {
        return t.isClassOrInterfaceType() ? t.asClassOrInterfaceType().getNameAsString() : t.asString();
    }

    // --- flow detail: conditions, mutations, source snippet -----------------

    private void fillDetails(MethodDeclaration md, Node node) {
        for (IfStmt is : md.findAll(IfStmt.class)) {
            node.conditions.add("if (" + oneLine(is.getCondition().toString()) + ")");
        }
        for (SwitchStmt sw : md.findAll(SwitchStmt.class)) {
            node.conditions.add("switch (" + oneLine(sw.getSelector().toString()) + ")");
        }
        for (AssignExpr a : md.findAll(AssignExpr.class)) {
            node.mutations.add(oneLine(a.getTarget().toString() + " " + a.getOperator().asString()
                    + " " + truncate(a.getValue().toString(), 60)));
        }
        for (MethodCallExpr c : md.findAll(MethodCallExpr.class)) {
            String n = c.getNameAsString();
            if (n.startsWith("set") && n.length() > 3 && !c.getArguments().isEmpty()) {
                node.mutations.add(oneLine(truncate(c.toString(), 90)));
            }
        }
        md.getBegin().ifPresent(pos -> node.line = pos.line);
        md.findCompilationUnit().ifPresent(cu -> {
            if (cu.containsData(FILE_PATH)) node.file = cu.getData(FILE_PATH);
            else cu.getStorage().ifPresent(s -> node.file = s.getPath().getFileName().toString());
        });
        node.source = truncate(md.toString(), 4000);
    }

    // --- endpoint metadata helpers ------------------------------------------

    private Optional<AnnotationExpr> findMapping(MethodDeclaration m) {
        return m.getAnnotations().stream()
                .filter(a -> MAPPING_ANNOS.contains(a.getNameAsString()))
                .findFirst();
    }

    private String classLevelPath(ProjectModel.TypeInfo type) {
        return type.decl.getAnnotationByName("RequestMapping")
                .flatMap(a -> a.findFirst(StringLiteralExpr.class))
                .map(StringLiteralExpr::asString)
                .orElse("");
    }

    private String annoPath(AnnotationExpr anno) {
        return anno.findFirst(StringLiteralExpr.class).map(StringLiteralExpr::asString).orElse("");
    }

    private String httpMethod(AnnotationExpr anno) {
        String n = anno.getNameAsString();
        switch (n) {
            case "GetMapping": return "GET";
            case "PostMapping": return "POST";
            case "PutMapping": return "PUT";
            case "DeleteMapping": return "DELETE";
            case "PatchMapping": return "PATCH";
            default: // RequestMapping — look for RequestMethod.XXX
                String txt = anno.toString();
                for (String verb : new String[]{"GET", "POST", "PUT", "DELETE", "PATCH"}) {
                    if (txt.contains("RequestMethod." + verb)) return verb;
                }
                return "ANY";
        }
    }

    // --- small utilities -----------------------------------------------------

    private MethodDeclaration firstMethod(ProjectModel.TypeInfo type, String name) {
        List<MethodDeclaration> ms = type.methodsByName.get(name);
        return ms == null || ms.isEmpty() ? null : ms.get(0);
    }

    private MethodDeclaration pickOverload(ProjectModel.TypeInfo type, String name, int argCount) {
        List<MethodDeclaration> ms = type.methodsByName.get(name);
        if (ms == null || ms.isEmpty()) return null;
        for (MethodDeclaration m : ms) {
            if (m.getParameters().size() == argCount) return m;
        }
        return ms.get(0);
    }

    private String methodNodeId(ProjectModel.TypeInfo type, MethodDeclaration md) {
        return "M:" + type.qualifiedName + "#" + md.getNameAsString() + "/" + md.getParameters().size();
    }

    private String firstStringArg(MethodCallExpr call) {
        for (Expression a : call.getArguments()) {
            if (a.isStringLiteralExpr()) return a.asStringLiteralExpr().asString();
            if (a.isBinaryExpr()) { // concatenated SQL — grab literal fragments
                StringBuilder sb = new StringBuilder();
                a.findAll(StringLiteralExpr.class).forEach(s -> sb.append(s.asString()).append(' '));
                if (sb.length() > 0) return sb.toString();
            }
        }
        return null;
    }

    private String entityArgTable(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) return "?";
        Expression a = call.getArgument(0);
        if (a.isObjectCreationExpr()) {
            return tableFromSimple(a.asObjectCreationExpr().getType().getNameAsString());
        }
        return "?";
    }

    private String tableFromSimple(String simple) {
        return model.entityTable.getOrDefault(simple, simple);
    }

    private static boolean isAccessor(String name) {
        if (name.length() > 3 && (name.startsWith("get") || name.startsWith("set"))
                && Character.isUpperCase(name.charAt(3))) return true;
        return name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
    }

    private static String joinPath(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        if (left.isEmpty()) return right.isEmpty() ? "/" : ensureLeadingSlash(right);
        if (right.isEmpty()) return ensureLeadingSlash(left);
        String l = left.endsWith("/") ? left.substring(0, left.length() - 1) : left;
        String r = right.startsWith("/") ? right : "/" + right;
        return ensureLeadingSlash(l + r);
    }

    private static String ensureLeadingSlash(String s) {
        return s.startsWith("/") ? s : "/" + s;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private List<SourceFile> collectSources(Path root) throws IOException {
        List<SourceFile> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.filter(x -> x.toString().endsWith(".java"))::iterator) {
                try {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    sources.add(new SourceFile(rel, Files.readString(p)));
                } catch (Exception e) {
                    System.err.println("[warn] could not read: " + p + " — " + e.getMessage());
                }
            }
        }
        return sources;
    }
}

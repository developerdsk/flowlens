package dev.flowlens;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.expr.SwitchExpr;
import dev.flowlens.ProjectModel.TypeInfo;
import dev.flowlens.TypeResolver.RType;
import dev.flowlens.model.Diagnostic;
import dev.flowlens.model.Graph;
import dev.flowlens.source.AnalysisOptions;
import dev.flowlens.source.SourceCollector;
import dev.flowlens.source.SourceFile;
import dev.flowlens.source.SourceSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds a data-flow graph from a Java source tree: entry points (HTTP, messaging, schedules)
 * -> service/repository call chain (through interfaces, with branch guards) -> sinks
 * (database tables, outbound HTTP, message brokers), plus request-field lineage.
 *
 * <p>Each method is walked once for the whole project; per-endpoint views are reachability
 * slices of the global graph, computed by the viewer / MCP server.
 */
public class FlowAnalyzer {

    /** Progress reporting and cooperative cancellation. */
    public interface Progress {
        Progress NONE = new Progress() {};

        default void update(String phase, int done, int total) {}

        default boolean cancelled() { return false; }
    }

    /** Thrown when {@link Progress#cancelled()} becomes true. */
    public static class CancelledException extends RuntimeException {
        public CancelledException() { super("cancelled"); }
    }

    /** Parsed compilation units keyed by path + content hash; lets re-indexing skip unchanged files. */
    public static class ParseCache {
        record Entry(String hash, CompilationUnit cu, String error) {}
        final Map<String, Entry> entries = new ConcurrentHashMap<>();

        public int size() { return entries.size(); }
    }

    private static final int MAX_LIST = 40;
    private static final int MAX_UNRESOLVED = 20;

    private final ProjectModel model = new ProjectModel();
    private final Graph graph = new Graph();
    private TypeResolver types;
    private Sinks sinks;
    private EntryPoints entryPoints;
    private Progress progress = Progress.NONE;

    private final Deque<WalkItem> work = new ArrayDeque<>();
    private final Set<String> walked = new HashSet<>();

    private record WalkItem(String nodeId, Node body, TypeInfo owner) {}

    // ------------------------------------------------------------------------------------------
    // entry points
    // ------------------------------------------------------------------------------------------

    /** Analyze a source tree on disk with default options. */
    public Graph analyze(Path sourceRoot) throws IOException {
        return analyze(sourceRoot, new AnalysisOptions(), Progress.NONE, null);
    }

    public Graph analyze(Path sourceRoot, AnalysisOptions options, Progress progress, ParseCache cache) throws IOException {
        long start = System.nanoTime();
        this.progress = progress == null ? Progress.NONE : progress;
        this.progress.update("collect", 0, 0);
        SourceSet set = new SourceCollector().collect(sourceRoot, options);
        checkCancelled();
        Graph g = analyzeSet(set, cache);
        g.sourcePath = sourceRoot.toAbsolutePath().toString();
        g.ref = options.ref == null || options.ref.isBlank() ? null : options.ref.trim();
        g.stats.durationMs = (System.nanoTime() - start) / 1_000_000;
        return g;
    }

    /** Analyze source files provided in memory. */
    public Graph analyzeSources(List<SourceFile> sources) {
        SourceSet set = new SourceSet();
        for (SourceFile sf : sources) {
            if (sf == null || sf.content == null) continue;
            if (sf.path != null && sf.path.endsWith(".xml")) set.mapperXml.add(sf);
            else set.javaFiles.add(sf);
        }
        set.javaFilesFound = set.javaFiles.size();
        return analyzeSet(set, null);
    }

    public Graph analyzeSet(SourceSet set, ParseCache cache) {
        graph.modules.addAll(set.modules);
        graph.diagnostics.addAll(set.diagnostics);
        graph.stats.filesFound = set.javaFilesFound;
        graph.stats.xmlMappers = set.mapperXml.size();

        Map<CompilationUnit, SourceFile> origins = parseAll(set.javaFiles, cache);
        List<CompilationUnit> units = new ArrayList<>(origins.keySet());
        units.sort(Comparator.comparing(cu -> origins.get(cu).path == null ? "" : origins.get(cu).path));
        graph.stats.filesParsed = units.size();
        graph.stats.filesSkipped = set.javaFiles.size() - units.size();
        checkCancelled();

        progress.update("index", 0, 0);
        model.index(units, origins, set.mapperXml);
        graph.diagnostics.addAll(model.diagnostics);
        types = new TypeResolver(model);
        sinks = new Sinks(model, types);
        entryPoints = new EntryPoints(model, types);
        checkCancelled();

        buildGraph();
        return graph;
    }

    public ProjectModel model() {
        return model;
    }

    private void checkCancelled() {
        if (progress.cancelled()) throw new CancelledException();
    }

    // ------------------------------------------------------------------------------------------
    // parsing (parallel, cached)
    // ------------------------------------------------------------------------------------------

    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                    .setAttributeComments(false)
                    .setStoreTokens(true)));   // positions (line numbers, scoping) need tokens

    private Map<CompilationUnit, SourceFile> parseAll(List<SourceFile> files, ParseCache cache) {
        int total = files.size();
        AtomicInteger done = new AtomicInteger();
        List<SourceFile> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(f -> f.path == null ? "" : f.path));
        CompilationUnit[] results = new CompilationUnit[sorted.size()];
        String[] errors = new String[sorted.size()];
        progress.update("parse", 0, total);

        java.util.stream.IntStream.range(0, sorted.size()).parallel().forEach(i -> {
            if (progress.cancelled()) return;
            SourceFile sf = sorted.get(i);
            String key = sf.path == null ? "#" + i : sf.path;
            String hash = sha1(sf.content);
            ParseCache.Entry cached = cache == null ? null : cache.entries.get(key);
            if (cached != null && cached.hash().equals(hash)) {
                results[i] = cached.cu();
                errors[i] = cached.error();
            } else {
                try {
                    ParseResult<CompilationUnit> r = PARSER.get().parse(sf.content);
                    if (r.isSuccessful() && r.getResult().isPresent()) {
                        results[i] = r.getResult().get();
                    } else {
                        errors[i] = r.getProblems().isEmpty() ? "parse error" : r.getProblem(0).getVerboseMessage().lines().findFirst().orElse("parse error");
                    }
                } catch (Exception | StackOverflowError e) {
                    errors[i] = "parse error: " + e.getClass().getSimpleName();
                }
                if (cache != null) cache.entries.put(key, new ParseCache.Entry(hash, results[i], errors[i]));
            }
            int d = done.incrementAndGet();
            if (d % 25 == 0 || d == total) progress.update("parse", d, total);
        });
        checkCancelled();

        if (cache != null) {
            Set<String> live = new HashSet<>();
            for (int i = 0; i < sorted.size(); i++) live.add(sorted.get(i).path == null ? "#" + i : sorted.get(i).path);
            cache.entries.keySet().retainAll(live);
        }

        // identity map: CompilationUnit.equals/hashCode are structural (and expensive)
        Map<CompilationUnit, SourceFile> out = new IdentityHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (results[i] != null) {
                out.put(results[i], sorted.get(i));
            } else {
                String msg = errors[i] == null ? "parse error" : errors[i];
                graph.diagnostics.add(new Diagnostic("warning", sorted.get(i).path, "skipped (" + msg + ")"));
                System.err.println("[warn] skipped: " + sorted.get(i).path + " - " + msg);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // graph construction
    // ------------------------------------------------------------------------------------------

    private void buildGraph() {
        List<EntryPoints.Entry> entries = entryPoints.find();
        int i = 0;
        for (EntryPoints.Entry e : entries) {
            checkCancelled();
            dev.flowlens.model.Node ep = graph.upsert(new dev.flowlens.model.Node(e.id, e.label, "endpoint"));
            ep.layer = ProjectModel.CONTROLLER;
            ep.kind = e.kind;
            ep.framework = e.framework;
            ep.verb = e.verb;
            ep.path = e.path;
            ep.http = e.http();
            ep.module = e.owner.module;
            ep.confidence = "high";
            fillDetails(e.body, e.owner, ep);
            work.push(new WalkItem(e.id, e.body, e.owner));
            drain();
            progress.update("analyze", ++i, entries.size());
        }
        progress.update("lineage", 0, entries.size());
        new LineageAnalyzer(model, types, this).apply(entries, graph);
    }

    private void drain() {
        while (!work.isEmpty()) {
            WalkItem item = work.pop();
            if (!walked.add(item.nodeId())) continue;
            walkBody(item.nodeId(), item.body(), item.owner());
        }
    }

    private void walkBody(String nodeId, Node body, TypeInfo owner) {
        dev.flowlens.model.Node self = graph.get(nodeId);
        // calls in source order
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (!belongsTo(call, body)) continue;
            handleCall(nodeId, self, body, call, owner);
        }
        for (MethodReferenceExpr ref : body.findAll(MethodReferenceExpr.class)) {
            if (!belongsTo(ref, body)) continue;
            // userRepository::findByLogin, inventoryClient::reserve -> sinks
            RType scope = types.typeOf(ref.getScope());
            if (scope != null && scope.project != null && (scope.project.isRepository || scope.project.isFeignClient)) {
                TypeInfo st = scope.project;
                sink(nodeId, body, ref, st.isFeignClient ? sinks.httpClient(st, ref.getIdentifier())
                        : sinks.repository(st, ref.getIdentifier(), null));
                continue;
            }
            EntryPoints.MethodTarget t = entryPoints.methodRefTarget(ref);
            if (t != null && t.method().getBody().isPresent()) {
                String target = methodNode(t.owner(), t.method());
                link(nodeId, body, ref, target, ref.getIdentifier(), "call", "medium");
            }
        }
        for (ThrowStmt th : body.findAll(ThrowStmt.class)) {
            if (!belongsTo(th, body)) continue;
            String exc = th.getExpression() instanceof ObjectCreationExpr oc ? oc.getType().getNameAsString()
                    : oneLine(truncate(th.getExpression().toString(), 50));
            String id = "T:" + nodeId + "#" + ordinal(body, th);
            dev.flowlens.model.Node tn = graph.upsert(new dev.flowlens.model.Node(id, "throw " + exc, "throw"));
            tn.layer = "flow";
            tn.line = th.getBegin().map(p -> p.line).orElse(null);
            tn.file = self == null ? null : self.file;
            tn.source = oneLine(truncate(th.toString(), 300));
            link(nodeId, body, th, id, "throws", "flow", "high");
        }
    }

    /** True if {@code n} is directly inside {@code body}, not inside a nested/local type declaration. */
    private static boolean belongsTo(Node n, Node body) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null && cur != body) {
            if (cur instanceof TypeDeclaration<?>) return false;
            cur = cur.getParentNode().orElse(null);
        }
        return true;
    }

    private void handleCall(String nodeId, dev.flowlens.model.Node self, Node body, MethodCallExpr call, TypeInfo owner) {
        String name = call.getNameAsString();
        boolean outermost = !(call.getParentNode().orElse(null) instanceof MethodCallExpr p
                && p.getScope().isPresent() && p.getScope().get() == call);

        // 1. events: publisher.publishEvent(new OrderCreated(...))
        if ((name.equals("publishEvent") || name.equals("fire") || name.equals("fireAsync") || name.equals("multicastEvent"))
                && call.getArguments().size() >= 1) {
            if (linkEvent(nodeId, body, call)) return;
        }

        RType recv;
        if (call.getScope().isPresent()) {
            recv = types.typeOf(call.getScope().get());
        } else {
            recv = owner == null ? null : types.ofTypeInfo(owner);
        }

        if (recv != null && recv.project != null) {
            TypeInfo t = recv.project;
            if (t.isFeignClient) {
                sink(nodeId, body, call, sinks.httpClient(t, name));
                return;
            }
            if (t.isRepository) {
                List<Target> custom = dispatch(t, name, call, recv.qualifier, true);
                if (!custom.isEmpty()) {
                    for (Target tg : custom) link(nodeId, body, call, methodNode(tg.owner, tg.method), name, tg.kind, tg.confidence);
                } else {
                    sink(nodeId, body, call, sinks.repository(t, name, call));
                }
                return;
            }
            if (ProjectModel.ENTITY.equals(t.layer) || recv.isStatic && isAccessor(name) || isPlumbing(t, name)) return;
            List<Target> targets = dispatch(t, name, call, recv.qualifier, false);
            for (Target tg : targets) link(nodeId, body, call, methodNode(tg.owner, tg.method), name, tg.kind, tg.confidence);
            if (targets.isEmpty() && !t.isConcrete() && model.methodsNamed(t, name).stream().allMatch(r -> r.method().getBody().isEmpty())
                    && !model.methodsNamed(t, name).isEmpty()) {
                addUnresolved(self, "no implementation found for " + t.simpleName + "." + name + "()");
            }
            return;
        }

        if (outermost) {
            Sinks.Hit hit = sinks.library(call);
            if (hit != null) {
                sink(nodeId, body, call, hit);
                return;
            }
        }

        if (recv == null && call.getScope().isPresent() && outermost && !isAccessor(name)) {
            Expression root = call.getScope().get();
            while (root instanceof MethodCallExpr m && m.getScope().isPresent()) root = m.getScope().get();
            while (root instanceof FieldAccessExpr f) root = f.getScope();
            if (root instanceof NameExpr ne && !isNoise(ne) && types.typeOf(root) == null) {
                addUnresolved(self, oneLine(truncate(call.toString(), 90)));
            }
        }
    }

    private static final Set<String> LOGGERS = Set.of("log", "logger", "LOG", "LOGGER", "Log", "Logger");

    /** Names whose type we can't know and that never lead anywhere interesting. */
    private static boolean isNoise(NameExpr root) {
        String n = root.getNameAsString();
        if (LOGGERS.contains(n) || Character.isUpperCase(n.charAt(0))) return true; // loggers, static/type access
        // implicitly-typed lambda parameter (x -> x.foo())
        Node cur = root.getParentNode().orElse(null);
        while (cur != null && !(cur instanceof CallableDeclaration<?>)) {
            if (cur instanceof com.github.javaparser.ast.expr.LambdaExpr l
                    && l.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(n) && p.getType().isUnknownType())) return true;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean isPlumbing(TypeInfo t, String name) {
        if (!ProjectModel.OTHER.equals(t.layer)) return false;
        // DTO/value objects: getters/setters on types that aren't Spring beans
        if (isAccessor(name)) return true;
        // MyBatis Generator query builders (FooExample, its Criteria / GeneratedCriteria / Criterion)
        for (TypeInfo c = t; c != null; c = c.outer) {
            String n = c.simpleName;
            if (n.endsWith("Example") || n.equals("Criteria") || n.equals("GeneratedCriteria") || n.equals("Criterion")) return true;
        }
        return false;
    }

    private record Target(TypeInfo owner, MethodDeclaration method, String kind, String confidence) {}

    /**
     * The concrete method(s) a call on type {@code t} can run: the declared body, or the
     * implementation(s) of an interface/abstract method (honouring @Qualifier and @Primary).
     */
    private List<Target> dispatch(TypeInfo t, String name, MethodCallExpr call, String qualifier, boolean bodiesOnly) {
        List<String> argTypes = types.argTypes(call);
        ProjectModel.MethodRef m = model.findMethod(t, name, argTypes);
        if (m == null) return List.of();
        String conf = m.ambiguous() ? "medium" : "high";
        boolean hasBody = m.method().getBody().isPresent();
        if (t.isConcrete() && hasBody) return List.of(new Target(m.owner(), m.method(), "call", conf));

        List<Target> impls = new ArrayList<>();
        for (TypeInfo impl : model.implementations(t)) {
            ProjectModel.MethodRef im = model.findMethod(impl, name, argTypes);
            if (im != null && im.method().getBody().isPresent()) impls.add(new Target(im.owner(), im.method(), "dispatch", conf));
        }
        // de-duplicate (two subclasses inheriting the same body)
        // (AST nodes have structural equals/hashCode: compare by identity)
        Set<MethodDeclaration> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        impls = impls.stream().filter(x -> seen.add(x.method)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        if (impls.size() > 1 && qualifier != null) {
            List<Target> q = impls.stream().filter(x -> qualifier.equals(x.owner.beanName)
                    || qualifier.equals(ProjectModel.decapitalize(x.owner.simpleName))).toList();
            if (!q.isEmpty()) impls = q;
        }
        if (impls.size() > 1) {
            List<Target> primary = impls.stream().filter(x -> x.owner.primary).toList();
            if (!primary.isEmpty()) impls = primary;
        }
        if (impls.size() == 1) {
            Target only = impls.get(0);
            return List.of(new Target(only.owner, only.method, "call", conf));
        }
        if (impls.size() > 1) {
            return impls.stream().map(x -> new Target(x.owner, x.method, "dispatch", "medium")).toList();
        }
        if (hasBody && !bodiesOnly) return List.of(new Target(m.owner(), m.method(), "call", conf));
        return List.of();
    }

    /** Project methods a call can run (used by lineage). Repositories/clients are sinks, not callees. */
    List<ProjectModel.MethodRef> calleesOf(MethodCallExpr call, TypeInfo owner) {
        RType recv = call.getScope().isPresent() ? types.typeOf(call.getScope().get())
                : owner == null ? null : types.ofTypeInfo(owner);
        if (recv == null || recv.project == null || recv.project.isRepository || recv.project.isFeignClient
                || ProjectModel.ENTITY.equals(recv.project.layer)) return List.of();
        List<ProjectModel.MethodRef> out = new ArrayList<>();
        for (Target t : dispatch(recv.project, call.getNameAsString(), call, recv.qualifier, false)) {
            out.add(new ProjectModel.MethodRef(t.owner, t.method, false));
        }
        return out;
    }

    private boolean linkEvent(String nodeId, Node body, MethodCallExpr call) {
        RType ev = types.typeOf(call.getArgument(0));
        if (ev == null) return false;
        boolean any = false;
        for (ProjectModel.EventListenerInfo l : model.eventListeners) {
            boolean match = l.eventType().equals(ev.name)
                    || ev.project != null && model.isSubtypeOf(ev.project, l.eventType())
                    || l.eventType().equals("PayloadApplicationEvent");
            if (!match || l.method().getBody().isEmpty()) continue;
            String target = methodNode(l.owner(), l.method());
            link(nodeId, body, call, target, "event " + ev.name, "event", "medium");
            any = true;
        }
        return any;
    }

    private void sink(String nodeId, Node body, Node site, Sinks.Hit hit) {
        if (hit == null) return;
        String id = hit.nodeId();
        dev.flowlens.model.Node n = graph.upsert(new dev.flowlens.model.Node(id, hit.nodeLabel(), hit.type()));
        n.layer = hit.type();
        n.op = hit.op();
        n.table = hit.table();
        n.tech = hit.tech();
        if (n.detail == null) n.detail = hit.detail();
        if (n.confidence == null || rank(hit.confidence()) > rank(n.confidence)) n.confidence = hit.confidence();
        link(nodeId, body, site, id, hit.edgeLabel(), "sink", hit.confidence());
    }

    private static int rank(String c) {
        return "high".equals(c) ? 2 : "medium".equals(c) ? 1 : 0;
    }

    /** Ensure a node for a project method exists (filled once) and is queued for walking. */
    String methodNode(TypeInfo owner, MethodDeclaration md) {
        String id = "M:" + owner.key + "#" + ProjectModel.signature(md);
        if (!graph.has(id)) {
            dev.flowlens.model.Node n = graph.upsert(new dev.flowlens.model.Node(id,
                    owner.simpleName + "." + md.getNameAsString() + "()", "method"));
            n.layer = owner.layer;
            n.module = owner.module;
            n.confidence = "high";
            fillDetails(md, owner, n);
            work.push(new WalkItem(id, md, owner));
        }
        return id;
    }

    // ------------------------------------------------------------------------------------------
    // branch guards: the if/else/switch/loop/catch a call sits under become graph nodes
    // ------------------------------------------------------------------------------------------

    private record Guard(Node node, String label, String side) {}

    private void link(String fromId, Node body, Node site, String targetId, String callLabel, String kind, String confidence) {
        List<Guard> guards = guardsFor(site, body);
        String prev = fromId;
        String prevSide = null;
        for (Guard g : guards) {
            String gid = "BR:" + fromId + "#" + ordinal(body, g.node());
            dev.flowlens.model.Node gn = graph.upsert(new dev.flowlens.model.Node(gid, g.label(), "branch"));
            gn.layer = "flow";
            gn.line = g.node().getBegin().map(p -> p.line).orElse(null);
            dev.flowlens.model.Node from = graph.get(fromId);
            if (from != null) gn.file = from.file;
            graph.addEdge(prev, gid, prevSide == null ? "" : prevSide, "branch", "high");
            prev = gid;
            prevSide = g.side();
        }
        String label = prevSide == null ? callLabel : prevSide + " · " + callLabel;
        graph.addEdge(prev, targetId, label, kind, confidence);
    }

    private static List<Guard> guardsFor(Node site, Node body) {
        List<Guard> out = new ArrayList<>();
        Node child = site;
        Node cur = site.getParentNode().orElse(null);
        while (cur != null && cur != body) {
            if (cur instanceof IfStmt is) {
                String cond = "if (" + oneLine(truncate(is.getCondition().toString(), 80)) + ")";
                if (child == is.getThenStmt()) out.add(new Guard(is, cond, "then"));
                else if (is.getElseStmt().isPresent() && child == is.getElseStmt().get()) out.add(new Guard(is, cond, "else"));
            } else if (cur instanceof ConditionalExpr ce) {
                String cond = oneLine(truncate(ce.getCondition().toString(), 80)) + " ?";
                if (child == ce.getThenExpr()) out.add(new Guard(ce, cond, "then"));
                else if (child == ce.getElseExpr()) out.add(new Guard(ce, cond, "else"));
            } else if (cur instanceof SwitchEntry se) {
                Node sw = se.getParentNode().orElse(null);
                Expression selector = sw instanceof SwitchStmt ss ? ss.getSelector() : sw instanceof SwitchExpr sx ? sx.getSelector() : null;
                if (selector != null && child != selector) {
                    String side = se.getLabels().isEmpty() ? "default"
                            : "case " + oneLine(truncate(se.getLabels().toString().replaceAll("^\\[|]$", ""), 40));
                    out.add(new Guard(sw, "switch (" + oneLine(truncate(selector.toString(), 60)) + ")", side));
                }
            } else if (cur instanceof ForEachStmt fe && child == fe.getBody()) {
                out.add(new Guard(fe, "for (" + fe.getVariableDeclarator().getNameAsString() + " : "
                        + oneLine(truncate(fe.getIterable().toString(), 50)) + ")", "each"));
            } else if (cur instanceof ForStmt fs && child == fs.getBody()) {
                out.add(new Guard(fs, "for (" + fs.getCompare().map(c -> oneLine(truncate(c.toString(), 50))).orElse("") + ")", "loop"));
            } else if (cur instanceof WhileStmt ws && child == ws.getBody()) {
                out.add(new Guard(ws, "while (" + oneLine(truncate(ws.getCondition().toString(), 60)) + ")", "loop"));
            } else if (cur instanceof DoStmt ds && child == ds.getBody()) {
                out.add(new Guard(ds, "do … while (" + oneLine(truncate(ds.getCondition().toString(), 50)) + ")", "loop"));
            } else if (cur instanceof CatchClause cc && child == cc.getBody()) {
                out.add(new Guard(cc, "catch (" + cc.getParameter().getType() + ")", "on error"));
            } else if (cur instanceof CallableDeclaration<?> || cur instanceof TypeDeclaration<?>) {
                break;
            }
            child = cur;
            cur = cur.getParentNode().orElse(null);
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * A position-independent id for a guard/throw inside a method: its kind and index among nodes of
     * that kind. Unlike line:column it does not change when code above the method moves, so diff
     * mode does not report unchanged branches as removed and re-added.
     */
    private static String ordinal(Node body, Node n) {
        List<? extends Node> same = body.findAll(n.getClass());
        int i = 0;
        for (Node x : same) {
            if (x == n) break;
            i++;
        }
        return n.getClass().getSimpleName() + i;
    }

    // ------------------------------------------------------------------------------------------
    // node details: conditions, mutations, source snippet, location, hash
    // ------------------------------------------------------------------------------------------

    private void fillDetails(Node md, TypeInfo owner, dev.flowlens.model.Node node) {
        if (md == null) return;
        for (IfStmt is : md.findAll(IfStmt.class)) {
            add(node.conditions, "if (" + oneLine(is.getCondition().toString()) + ")");
        }
        for (SwitchStmt sw : md.findAll(SwitchStmt.class)) {
            add(node.conditions, "switch (" + oneLine(sw.getSelector().toString()) + ")");
        }
        for (SwitchExpr sw : md.findAll(SwitchExpr.class)) {
            add(node.conditions, "switch (" + oneLine(sw.getSelector().toString()) + ")");
        }
        for (ConditionalExpr ce : md.findAll(ConditionalExpr.class)) {
            add(node.conditions, oneLine(truncate(ce.getCondition().toString(), 80)) + " ? … : …");
        }
        for (AssignExpr a : md.findAll(AssignExpr.class)) {
            add(node.mutations, oneLine(a.getTarget().toString() + " " + a.getOperator().asString()
                    + " " + truncate(a.getValue().toString(), 60)));
        }
        for (MethodCallExpr c : md.findAll(MethodCallExpr.class)) {
            String n = c.getNameAsString();
            if (n.startsWith("set") && n.length() > 3 && Character.isUpperCase(n.charAt(3)) && !c.getArguments().isEmpty()) {
                add(node.mutations, oneLine(truncate(c.toString(), 90)));
            }
        }
        node.line = md.getBegin().map(p -> p.line).orElse(null);
        node.file = owner != null && owner.file != null ? owner.file
                : md.findCompilationUnit().flatMap(CompilationUnit::getStorage).map(s -> s.getFileName()).orElse(null);
        if (node.module == null && owner != null) node.module = owner.module;
        String src = md.toString();
        node.source = truncate(src, 4000);
        node.hash = sha1(src).substring(0, 12);
    }

    private static void add(List<String> list, String v) {
        if (list.size() < MAX_LIST && !list.contains(v)) list.add(v);
    }

    private static void addUnresolved(dev.flowlens.model.Node n, String v) {
        if (n != null && n.unresolved.size() < MAX_UNRESOLVED && !n.unresolved.contains(v)) n.unresolved.add(v);
    }

    // ------------------------------------------------------------------------------------------
    // small utilities
    // ------------------------------------------------------------------------------------------

    static boolean isAccessor(String name) {
        if (name.length() > 3 && (name.startsWith("get") || name.startsWith("set"))
                && Character.isUpperCase(name.charAt(3))) return true;
        return name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
    }

    static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}

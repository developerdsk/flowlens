package dev.flowlens;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import dev.flowlens.ProjectModel.TypeInfo;
import dev.flowlens.TypeResolver.RType;
import dev.flowlens.model.Graph;
import dev.flowlens.model.Lineage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Field-level lineage: which request field ends up in which column.
 *
 * <p>Per method, a summary maps (parameter, field path) to the columns it is written to — via
 * entity setters or builders followed by a save/insert/persist — and composes summaries up the
 * call chain when a parameter (or one of its getters) is passed on. Heuristic and intentionally
 * conservative: it reports only flows it can see end to end in the source.
 */
class LineageAnalyzer {

    private record Src(int param, String field) {}

    private record Col(String table, String column, String via) {}

    /** param index -> field path ("" = the parameter itself) -> columns */
    private static final class Summary {
        final Map<Integer, Map<String, Set<Col>>> byParam = new HashMap<>();

        void add(int param, String field, Col c) {
            byParam.computeIfAbsent(param, k -> new LinkedHashMap<>()).computeIfAbsent(field, k -> new LinkedHashSet<>()).add(c);
        }
    }

    private static final class EntityVar {
        final TypeInfo type;
        final Map<String, Set<Src>> props = new LinkedHashMap<>();

        EntityVar(TypeInfo type) { this.type = type; }
    }

    private static final Set<String> SAVE_LIKE = Set.of("save", "saveAndFlush", "saveAll", "saveAllAndFlush", "insert",
            "insertSelective", "insertBatch", "persist", "merge", "update", "updateById", "updateByPrimaryKey",
            "updateByPrimaryKeySelective", "insertOrUpdate", "saveOrUpdate", "upsert", "create", "add");
    private static final int MAX_LINEAGE = 200;

    private final ProjectModel model;
    private final TypeResolver types;
    private final FlowAnalyzer flow;
    private final Map<MethodDeclaration, Summary> memo = new IdentityHashMap<>();
    private final Set<MethodDeclaration> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());

    LineageAnalyzer(ProjectModel model, TypeResolver types, FlowAnalyzer flow) {
        this.model = model;
        this.types = types;
        this.flow = flow;
    }

    void apply(List<EntryPoints.Entry> entries, Graph graph) {
        for (EntryPoints.Entry e : entries) {
            if (e.method == null || e.method.getBody().isEmpty()) continue;
            dev.flowlens.model.Node node = graph.get(e.id);
            if (node == null) continue;
            Summary s;
            try {
                s = summarize(e.method, e.owner);
            } catch (RuntimeException ex) {
                continue; // lineage is best-effort
            }
            for (var byParam : s.byParam.entrySet()) {
                int idx = byParam.getKey();
                if (idx >= e.method.getParameters().size()) continue;
                String pname = e.method.getParameter(idx).getNameAsString();
                for (var f : byParam.getValue().entrySet()) {
                    String from = f.getKey().isEmpty() ? pname : pname + "." + f.getKey();
                    for (Col c : f.getValue()) {
                        Lineage l = new Lineage(from, c.table + "." + c.column, c.via);
                        if (node.lineage.size() < MAX_LINEAGE && !node.lineage.contains(l)) node.lineage.add(l);
                    }
                }
            }
        }
    }

    private Summary summarize(MethodDeclaration md, TypeInfo owner) {
        Summary cached = memo.get(md);
        if (cached != null) return cached;
        Summary out = new Summary();
        if (md.getBody().isEmpty() || !inProgress.add(md)) return out;
        try {
            Map<String, Integer> params = new HashMap<>();
            for (int i = 0; i < md.getParameters().size(); i++) params.put(md.getParameter(i).getNameAsString(), i);
            String via = owner.simpleName + "." + md.getNameAsString() + "()";
            Map<String, EntityVar> evars = new HashMap<>();

            // 1. entity setters: account.setEmail(request.getEmail())
            for (MethodCallExpr c : md.findAll(MethodCallExpr.class)) {
                String n = c.getNameAsString();
                if (!n.startsWith("set") || n.length() < 4 || c.getArguments().size() != 1) continue;
                if (!(c.getScope().orElse(null) instanceof NameExpr x)) continue;
                RType rt = types.typeOf(x);
                if (rt == null || rt.project == null || !rt.project.isEntity) continue;
                Set<Src> src = sources(c.getArgument(0), params);
                if (src.isEmpty()) continue;
                evars.computeIfAbsent(x.getNameAsString(), k -> new EntityVar(rt.project))
                        .props.computeIfAbsent(ProjectModel.decapitalize(n.substring(3)), k -> new LinkedHashSet<>()).addAll(src);
            }
            // 2. builders: Account a = Account.builder().email(req.getEmail()).build();
            for (VariableDeclarator v : md.findAll(VariableDeclarator.class)) {
                if (v.getInitializer().isEmpty()) continue;
                EntityVar ev = builder(v.getInitializer().get(), params);
                if (ev != null) evars.merge(v.getNameAsString(), ev, (a, b) -> { b.props.forEach((k, s) -> a.props.computeIfAbsent(k, z -> new LinkedHashSet<>()).addAll(s)); return a; });
            }
            // 3. save-like sinks
            for (MethodCallExpr c : md.findAll(MethodCallExpr.class)) {
                if (!SAVE_LIKE.contains(c.getNameAsString()) || c.getArguments().isEmpty() || !isPersistenceReceiver(c)) continue;
                Expression arg = c.getArgument(0);
                EntityVar ev = arg instanceof NameExpr ne ? evars.get(ne.getNameAsString()) : builder(arg, params);
                if (ev == null) continue;
                for (var p : ev.props.entrySet()) {
                    String column = ev.type.columns.getOrDefault(p.getKey(), ProjectModel.snake(p.getKey()));
                    for (Src s : p.getValue()) out.add(s.param, s.field, new Col(ev.type.table, column, via));
                }
            }
            // 4. pass-through to callees: service.create(request) / service.create(request.getAccount())
            for (MethodCallExpr c : md.findAll(MethodCallExpr.class)) {
                if (c.getArguments().isEmpty()) continue;
                List<Src> argSrc = new ArrayList<>();
                boolean any = false;
                for (Expression a : c.getArguments()) {
                    Src s = direct(a, params);
                    argSrc.add(s);
                    any |= s != null;
                }
                if (!any) continue;
                for (ProjectModel.MethodRef callee : flow.calleesOf(c, owner)) {
                    Summary sub = summarize(callee.method(), callee.owner());
                    for (int k = 0; k < argSrc.size(); k++) {
                        Src base = argSrc.get(k);
                        if (base == null) continue;
                        Map<String, Set<Col>> fields = sub.byParam.get(k);
                        if (fields == null) continue;
                        for (var f : fields.entrySet()) {
                            String path = base.field.isEmpty() ? f.getKey()
                                    : f.getKey().isEmpty() ? base.field : base.field + "." + f.getKey();
                            for (Col col : f.getValue()) out.add(base.param, path, col);
                        }
                    }
                }
            }
        } finally {
            inProgress.remove(md);
        }
        memo.put(md, out);
        return out;
    }

    private boolean isPersistenceReceiver(MethodCallExpr c) {
        if (c.getScope().isEmpty()) return false;
        RType r = types.typeOf(c.getScope().get());
        if (r == null) return false;
        if (r.project != null) return r.project.isRepository;
        return Set.of("EntityManager", "Session", "MongoTemplate", "MongoOperations", "ReactiveMongoTemplate").contains(r.name);
    }

    /** A builder chain on an entity: Entity.builder().a(x).b(y).build(). */
    private EntityVar builder(Expression e, Map<String, Integer> params) {
        if (!(e instanceof MethodCallExpr outer)) return null;
        List<MethodCallExpr> chain = new ArrayList<>();
        Expression cur = outer;
        while (cur instanceof MethodCallExpr c) {
            chain.add(c);
            cur = c.getScope().orElse(null);
        }
        Collections.reverse(chain);
        if (chain.isEmpty() || !chain.get(0).getNameAsString().equals("builder") || cur == null) return null;
        TypeInfo t = model.resolve(cur.toString(), model.enclosingType(e));
        if (t == null || !t.isEntity) return null;
        EntityVar ev = new EntityVar(t);
        for (int i = 1; i < chain.size(); i++) {
            MethodCallExpr c = chain.get(i);
            if (c.getArguments().size() != 1) continue;
            Set<Src> src = sources(c.getArgument(0), params);
            if (!src.isEmpty()) ev.props.computeIfAbsent(c.getNameAsString(), k -> new LinkedHashSet<>()).addAll(src);
        }
        return ev.props.isEmpty() ? null : ev;
    }

    /** All parameter-derived values used in an expression. */
    private Set<Src> sources(Expression e, Map<String, Integer> params) {
        Set<Src> out = new LinkedHashSet<>();
        Src d = direct(e, params);
        if (d != null) {
            out.add(d);
            return out;
        }
        for (MethodCallExpr m : e.findAll(MethodCallExpr.class)) {
            Src s = direct(m, params);
            if (s != null) out.add(s);
        }
        for (NameExpr n : e.findAll(NameExpr.class)) {
            Integer i = params.get(n.getNameAsString());
            if (i != null && !(n.getParentNode().orElse(null) instanceof MethodCallExpr mc && mc.getScope().orElse(null) == n)
                    && !(n.getParentNode().orElse(null) instanceof FieldAccessExpr)) {
                out.add(new Src(i, ""));
            }
        }
        return out;
    }

    /** {@code p}, {@code p.getA().getB()}, {@code p.a()} (record), {@code p.a} -> (index(p), "a.b"). */
    private static Src direct(Expression e, Map<String, Integer> params) {
        List<String> path = new ArrayList<>();
        Expression cur = e;
        while (true) {
            if (cur instanceof NameExpr n) {
                Integer i = params.get(n.getNameAsString());
                if (i == null) return null;
                Collections.reverse(path);
                return new Src(i, String.join(".", path));
            }
            if (cur instanceof MethodCallExpr m && m.getArguments().isEmpty() && m.getScope().isPresent()) {
                String prop = property(m.getNameAsString());
                if (prop == null) return null;
                path.add(prop);
                cur = m.getScope().get();
            } else if (cur instanceof FieldAccessExpr f) {
                path.add(f.getNameAsString());
                cur = f.getScope();
            } else if (cur.isEnclosedExpr()) {
                cur = cur.asEnclosedExpr().getInner();
            } else {
                return null;
            }
        }
    }

    private static String property(String method) {
        if (method.length() > 3 && method.startsWith("get") && Character.isUpperCase(method.charAt(3))) {
            return ProjectModel.decapitalize(method.substring(3));
        }
        if (method.length() > 2 && method.startsWith("is") && Character.isUpperCase(method.charAt(2))) {
            return ProjectModel.decapitalize(method.substring(2));
        }
        if (Set.of("toString", "hashCode", "trim", "toLowerCase", "toUpperCase", "strip", "length", "size",
                "isEmpty", "orElseThrow", "get", "clone", "build", "stream", "toList").contains(method)) return null;
        return Character.isLowerCase(method.charAt(0)) ? method : null; // record accessor
    }
}

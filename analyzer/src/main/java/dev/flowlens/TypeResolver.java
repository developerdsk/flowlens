package dev.flowlens;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import dev.flowlens.ProjectModel.TypeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Source-level, best-effort static typing of expressions — enough to know which object a
 * method is called on. Works without the target project's dependencies on the classpath.
 */
public class TypeResolver {

    /** The type of an expression. {@code project} is set when the type is declared in the analyzed sources. */
    public static final class RType {
        public final String name;
        public final TypeInfo project;
        public final List<RType> args;
        /** {@code @Qualifier} on the injection point, used to pick among implementations. */
        public String qualifier;
        /** True when the expression names a type (static access), not a value. */
        public boolean isStatic;

        RType(String name, TypeInfo project, List<RType> args) {
            this.name = name;
            this.project = project;
            this.args = args == null ? List.of() : args;
        }

        public RType arg(int i) {
            return i < args.size() ? args.get(i) : null;
        }

        @Override public String toString() { return name + (args.isEmpty() ? "" : args.toString()); }
    }

    private static final Set<String> OPTIONAL_UNWRAP = Set.of("orElse", "orElseThrow", "get", "orElseGet", "join", "block", "getBody");
    private static final Set<String> COLLECTIONS = Set.of("List", "Set", "Collection", "Iterable", "ArrayList",
            "LinkedList", "HashSet", "TreeSet", "LinkedHashSet", "Page", "Slice", "Flux", "Stream");

    private final ProjectModel model;
    private final Map<VariableDeclarator, Boolean> inProgress = new IdentityHashMap<>();

    public TypeResolver(ProjectModel model) {
        this.model = model;
    }

    // ------------------------------------------------------------------------------------------

    /** A declared type, resolved in the context of the type that contains it. */
    public RType ofType(Type t, TypeInfo ctx) {
        if (t == null || t.isVarType() || t.isUnknownType()) return null;
        if (t.isClassOrInterfaceType()) {
            ClassOrInterfaceType c = t.asClassOrInterfaceType();
            List<RType> args = new ArrayList<>();
            c.getTypeArguments().ifPresent(as -> as.forEach(a -> {
                RType r = ofType(a, ctx);
                args.add(r == null ? new RType("?", null, null) : r);
            }));
            return new RType(c.getNameAsString(), model.resolve(c.getNameWithScope(), ctx), args);
        }
        if (t.isWildcardType()) {
            return t.asWildcardType().getExtendedType().map(e -> ofType(e, ctx)).orElse(null);
        }
        return new RType(t.asString(), null, null);
    }

    public RType ofTypeInfo(TypeInfo t) {
        return new RType(t.simpleName, t, null);
    }

    /** The static type of {@code e}, or null if it can't be determined. */
    public RType typeOf(Expression e) {
        try {
            return doTypeOf(e, 0);
        } catch (RuntimeException ex) {
            return null; // never let a resolver corner case abort the analysis
        }
    }

    private RType doTypeOf(Expression e, int depth) {
        if (e == null || depth > 12) return null;
        TypeInfo owner = model.enclosingType(e);
        if (e instanceof ThisExpr) return owner == null ? null : ofTypeInfo(owner);
        if (e instanceof SuperExpr) {
            if (owner == null) return null;
            return owner.supertypes.stream().filter(s -> !s.isInterface).findFirst().map(this::ofTypeInfo).orElse(null);
        }
        if (e instanceof EnclosedExpr en) return doTypeOf(en.getInner(), depth + 1);
        if (e instanceof CastExpr c) return ofType(c.getType(), owner);
        if (e instanceof ObjectCreationExpr oc) return ofType(oc.getType(), owner);
        if (e instanceof ClassExpr) return new RType("Class", null, null);
        if (e instanceof StringLiteralExpr || e instanceof TextBlockLiteralExpr) return new RType("String", null, null);
        if (e instanceof LiteralExpr) return literalType(e);
        if (e instanceof TypeExpr te) {
            // `handler::get` parses the scope as a type; prefer a variable of that name if one exists
            if (te.getType().isClassOrInterfaceType() && te.getType().asClassOrInterfaceType().getScope().isEmpty()) {
                RType v = nameType(te.getType().asClassOrInterfaceType().getNameAsString(), e, owner, depth);
                if (v != null && !v.isStatic) return v;
            }
            RType r = ofType(te.getType(), owner);
            if (r != null) r.isStatic = true;
            return r;
        }
        if (e instanceof ConditionalExpr ce) {
            RType r = doTypeOf(ce.getThenExpr(), depth + 1);
            return r != null ? r : doTypeOf(ce.getElseExpr(), depth + 1);
        }
        if (e instanceof AssignExpr a) return doTypeOf(a.getTarget(), depth + 1);
        if (e instanceof NameExpr n) return nameType(n.getNameAsString(), e, owner, depth);
        if (e instanceof FieldAccessExpr f) return fieldAccessType(f, owner, depth);
        if (e instanceof MethodCallExpr call) return returnType(call, owner, depth);
        return null;
    }

    private static RType literalType(Expression e) {
        if (e.isIntegerLiteralExpr()) return new RType("int", null, null);
        if (e.isLongLiteralExpr()) return new RType("long", null, null);
        if (e.isBooleanLiteralExpr()) return new RType("boolean", null, null);
        if (e.isDoubleLiteralExpr()) return new RType("double", null, null);
        if (e.isCharLiteralExpr()) return new RType("char", null, null);
        if (e.isNullLiteralExpr()) return new RType("null", null, null);
        return null;
    }

    // --- names -------------------------------------------------------------------------------

    private RType nameType(String name, Node at, TypeInfo owner, int depth) {
        // innermost scope first: lambda params, pattern vars, catch/for-each, locals, method params
        Node cur = at.getParentNode().orElse(null);
        Node child = at;
        while (cur != null) {
            if (cur instanceof LambdaExpr lambda) {
                for (Parameter p : lambda.getParameters()) {
                    if (p.getNameAsString().equals(name)) {
                        RType r = ofType(p.getType(), owner);
                        return r != null ? r : lambdaParamType(lambda, lambda.getParameters().indexOf(p), owner, depth);
                    }
                }
            }
            if (cur instanceof CatchClause cc && cc.getParameter().getNameAsString().equals(name)) {
                return ofType(cc.getParameter().getType(), owner);
            }
            if (cur instanceof ForEachStmt fe && fe.getVariableDeclarator().getNameAsString().equals(name) && child != fe.getIterable()) {
                RType declared = ofType(fe.getVariableDeclarator().getType(), owner);
                if (declared != null) return declared;
                RType it = doTypeOf(fe.getIterable(), depth + 1);
                return it == null ? null : it.arg(0);
            }
            if (cur instanceof CallableDeclaration<?> cd) {
                RType local = localVar(name, at, cd, owner, depth);
                if (local != null) return local;
                for (Parameter p : cd.getParameters()) {
                    if (p.getNameAsString().equals(name)) {
                        RType r = ofType(p.getType(), owner);
                        if (r != null) r.qualifier = qualifierOf(Annotations.get(p, "Qualifier"));
                        return r;
                    }
                }
                break;
            }
            if (cur instanceof FieldDeclaration || cur instanceof TypeDeclaration<?>) {
                RType local = localVar(name, at, cur, owner, depth);
                if (local != null) return local;
                break;
            }
            child = cur;
            cur = cur.getParentNode().orElse(null);
        }
        // fields (owner, its outers, inherited)
        for (TypeInfo t = owner; t != null; t = t.outer) {
            Optional<ProjectModel.FieldMatch> f = model.findField(t, name);
            if (f.isPresent()) return fieldType(f.get(), depth);
        }
        // a type name used for static access
        if (owner != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
            TypeInfo t = model.resolve(name, owner);
            RType r = new RType(name, t, null);
            r.isStatic = true;
            return r;
        }
        return null;
    }

    /** Locals declared before {@code at} in a block that encloses it; {@code var} is inferred. */
    private RType localVar(String name, Node at, Node scope, TypeInfo owner, int depth) {
        VariableDeclarator best = null;
        for (VariableDeclarator v : scope.findAll(VariableDeclarator.class)) {
            if (!v.getNameAsString().equals(name)) continue;
            if (v.getParentNode().orElse(null) instanceof FieldDeclaration) continue;
            if (!before(v, at) || !visibleFrom(v, at)) continue;
            if (best == null || before(best, v)) best = v;
        }
        // pattern variables: `if (x instanceof Foo foo)`
        for (TypePatternExpr p : scope.findAll(TypePatternExpr.class)) {
            if (p.getNameAsString().equals(name) && before(p, at)) return ofType(p.getType(), owner);
        }
        if (best == null) return null;
        RType declared = ofType(best.getType(), owner);
        if (declared != null) return declared;
        // `var`: infer from the initializer
        if (best.getInitializer().isEmpty() || inProgress.containsKey(best)) return null;
        inProgress.put(best, true);
        try {
            return doTypeOf(best.getInitializer().get(), depth + 1);
        } finally {
            inProgress.remove(best);
        }
    }

    private static boolean before(Node a, Node b) {
        return a.getBegin().isPresent() && b.getBegin().isPresent() && a.getBegin().get().isBefore(b.getBegin().get());
    }

    /** The declaration's enclosing block also encloses the use site. */
    private static boolean visibleFrom(VariableDeclarator v, Node use) {
        Node block = v.getParentNode().flatMap(Node::getParentNode).orElse(null); // VariableDeclarationExpr -> stmt/for
        while (block != null && !(block instanceof com.github.javaparser.ast.stmt.BlockStmt)
                && !(block instanceof com.github.javaparser.ast.stmt.ForStmt)
                && !(block instanceof com.github.javaparser.ast.stmt.ForEachStmt)
                && !(block instanceof com.github.javaparser.ast.stmt.TryStmt)
                && !(block instanceof com.github.javaparser.ast.stmt.SwitchEntry)
                && !(block instanceof LambdaExpr)) {
            block = block.getParentNode().orElse(null);
        }
        if (block == null) return true;
        Node cur = use;
        while (cur != null) {
            if (cur == block) return true;
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    /** Implicitly-typed lambda parameter: element type of the stream/collection/optional it's applied to. */
    private RType lambdaParamType(LambdaExpr lambda, int index, TypeInfo owner, int depth) {
        if (index != 0) return null;
        Node parent = lambda.getParentNode().orElse(null);
        if (!(parent instanceof MethodCallExpr call) || call.getScope().isEmpty()) return null;
        String m = call.getNameAsString();
        if (!Set.of("forEach", "map", "flatMap", "filter", "ifPresent", "peek", "anyMatch", "allMatch", "noneMatch",
                "ifPresentOrElse", "doOnNext", "subscribe", "thenAccept", "thenApply", "removeIf").contains(m)) return null;
        RType scope = doTypeOf(call.getScope().get(), depth + 1);
        return scope == null ? null : scope.arg(0);
    }

    private RType fieldType(ProjectModel.FieldMatch f, int depth) {
        Type t = f.type();
        if (t == null) return ofTypeInfo(f.owner()); // enum constant
        RType r = ofType(t, f.owner());
        if (r == null && f.var() != null && f.var().getInitializer().isPresent()) {
            r = doTypeOf(f.var().getInitializer().get(), depth + 1);
        }
        if (r != null && f.field() != null) {
            r.qualifier = qualifierOf(Annotations.get(f.field(), "Qualifier"));
            if (r.qualifier == null) r.qualifier = constructorQualifier(f);
            if (r.qualifier == null) {
                r.qualifier = Annotations.get(f.field(), "Resource")
                        .flatMap(a -> Annotations.firstString(a, model, f.owner(), "name")).orElse(null);
            }
        }
        return r;
    }

    /** {@code @Qualifier} on a constructor parameter that is assigned to this field. */
    private String constructorQualifier(ProjectModel.FieldMatch f) {
        String fieldName = f.var().getNameAsString();
        for (ConstructorDeclaration c : f.owner().decl.getConstructors()) {
            for (Parameter p : c.getParameters()) {
                Optional<AnnotationExpr> q = Annotations.get(p, "Qualifier");
                if (q.isEmpty()) continue;
                boolean assigned = p.getNameAsString().equals(fieldName)
                        || c.findAll(AssignExpr.class).stream().anyMatch(a ->
                        a.getTarget().toString().endsWith(fieldName) && a.getValue().toString().equals(p.getNameAsString()));
                if (assigned) return qualifierOf(q);
            }
        }
        return null;
    }

    private String qualifierOf(Optional<AnnotationExpr> a) {
        return a.flatMap(x -> Annotations.firstString(x, model, null, "value")).orElse(null);
    }

    private RType fieldAccessType(FieldAccessExpr f, TypeInfo owner, int depth) {
        RType scope = doTypeOf(f.getScope(), depth + 1);
        String name = f.getNameAsString();
        if (scope == null) {
            // fully-qualified type, e.g. com.example.Util
            TypeInfo t = owner == null ? null : model.resolve(f.toString(), owner);
            if (t != null) {
                RType r = ofTypeInfo(t);
                r.isStatic = true;
                return r;
            }
            return null;
        }
        if (scope.project != null) {
            Optional<ProjectModel.FieldMatch> fm = model.findField(scope.project, name);
            if (fm.isPresent()) return fieldType(fm.get(), depth);
            // nested type access: Outer.Inner
            TypeInfo nested = model.qualified(scope.project.qualifiedName + "." + name, scope.project);
            if (nested != null) {
                RType r = ofTypeInfo(nested);
                r.isStatic = true;
                return r;
            }
        }
        if ("length".equals(name) && scope.name.endsWith("[]")) return new RType("int", null, null);
        return null;
    }

    // --- method return types ----------------------------------------------------------------

    private RType returnType(MethodCallExpr call, TypeInfo owner, int depth) {
        String name = call.getNameAsString();
        RType scope = call.getScope().map(s -> doTypeOf(s, depth + 1)).orElse(owner == null ? null : ofTypeInfo(owner));
        if (scope == null) return null;

        if (scope.project != null) {
            TypeInfo t = scope.project;
            if (t.isRepository && t.repoEntity != null) {
                RType repoRet = repositoryReturn(t, name);
                if (repoRet != null && model.methodsNamed(t, name).isEmpty()) return repoRet;
            }
            ProjectModel.MethodRef m = model.findMethod(t, name, argTypes(call));
            if (m != null) {
                RType r = ofType(m.method().getType(), m.owner());
                if (r != null && t.isRepository && t.repoEntity != null) r = substituteEntity(r, t);
                return r;
            }
            return null;
        }

        // library types we know enough about
        if (OPTIONAL_UNWRAP.contains(name) && (scope.name.equals("Optional") || scope.name.equals("CompletableFuture")
                || scope.name.equals("Mono") || scope.name.equals("Future") || scope.name.equals("ResponseEntity"))) {
            return scope.arg(0);
        }
        if (name.equals("get") && COLLECTIONS.contains(scope.name)) return scope.arg(0);
        if (name.equals("get") && scope.name.equals("Map")) return scope.arg(1);
        if ((name.equals("stream") || name.equals("filter") || name.equals("sorted") || name.equals("distinct")
                || name.equals("limit") || name.equals("skip") || name.equals("peek")) && COLLECTIONS.contains(scope.name)) {
            return new RType("Stream", null, scope.args);
        }
        if ((name.equals("findFirst") || name.equals("findAny") || name.equals("max") || name.equals("min"))
                && scope.name.equals("Stream")) {
            return new RType("Optional", null, scope.args);
        }
        if (name.equals("getContent") && (scope.name.equals("Page") || scope.name.equals("Slice"))) {
            return new RType("List", null, scope.args);
        }
        return null;
    }

    /** Return types of inherited Spring Data methods (findById -> Optional<Entity>, save -> Entity, ...). */
    private RType repositoryReturn(TypeInfo repo, String name) {
        TypeInfo entityInfo = model.resolve(repo.repoEntity, repo);
        RType entity = entityInfo != null ? ofTypeInfo(entityInfo) : new RType(repo.repoEntity, null, null);
        switch (name) {
            case "findById": return new RType("Optional", null, List.of(entity));
            case "getById": case "getReferenceById": case "getOne": case "save": case "saveAndFlush": case "insert":
                return entity;
            case "findAll": case "findAllById": case "saveAll": case "saveAllAndFlush":
                return new RType("List", null, List.of(entity));
            default: return null;
        }
    }

    /** Replace a generic {@code T} with the repository's entity type. */
    private RType substituteEntity(RType r, TypeInfo repo) {
        if (r.name.length() == 1 && Character.isUpperCase(r.name.charAt(0))) {
            TypeInfo e = model.resolve(repo.repoEntity, repo);
            return e != null ? ofTypeInfo(e) : new RType(repo.repoEntity, null, null);
        }
        if (r.args.isEmpty()) return r;
        List<RType> args = new ArrayList<>();
        for (RType a : r.args) args.add(substituteEntity(a, repo));
        return new RType(r.name, r.project, args);
    }

    /** Raw simple names of a call's argument types (null where unknown). */
    public List<String> argTypes(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (Expression a : call.getArguments()) {
            RType r = a.isLambdaExpr() || a.isMethodReferenceExpr() ? null : typeOf(a);
            out.add(r == null ? null : r.name);
        }
        return out;
    }

    /** The method/constructor/lambda whose body contains {@code n}. */
    public static Node enclosingCallable(Node n) {
        Node cur = n.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof MethodDeclaration || cur instanceof ConstructorDeclaration) return cur;
            cur = cur.getParentNode().orElse(null);
        }
        return null;
    }
}

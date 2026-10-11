package dev.flowlens;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import dev.flowlens.ProjectModel.TypeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads annotation attributes by name and evaluates string-valued expressions statically:
 * literals, text blocks, {@code +} concatenation, arrays, {@code static final} constants
 * (same class, other classes, static imports) and simple local variables.
 */
public final class Annotations {

    private static final int MAX_DEPTH = 8;

    private Annotations() {}

    /** Annotation by simple name, whether written as {@code @Entity} or {@code @jakarta.persistence.Entity}. */
    public static Optional<AnnotationExpr> get(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> n, String simpleName) {
        for (AnnotationExpr a : n.getAnnotations()) {
            if (a.getName().getIdentifier().equals(simpleName)) return Optional.of(a);
        }
        return Optional.empty();
    }

    public static boolean has(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> n, String simpleName) {
        return get(n, simpleName).isPresent();
    }

    /** Simple name of an annotation ({@code @org.x.GetMapping} -> {@code GetMapping}). */
    public static String name(AnnotationExpr a) {
        return a.getName().getIdentifier();
    }

    /** The expression of attribute {@code name}; "value" also matches the single-member form. */
    public static Optional<Expression> attr(AnnotationExpr a, String name) {
        if (a instanceof SingleMemberAnnotationExpr s) {
            return "value".equals(name) ? Optional.of(s.getMemberValue()) : Optional.empty();
        }
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals(name)) return Optional.of(p.getValue());
            }
        }
        return Optional.empty();
    }

    public static boolean hasAttr(AnnotationExpr a, String name) {
        return attr(a, name).isPresent();
    }

    /** All string values of the first attribute present among {@code names}. */
    public static List<String> stringValues(AnnotationExpr a, ProjectModel model, TypeInfo ctx, String... names) {
        for (String n : names) {
            Optional<Expression> e = attr(a, n);
            if (e.isPresent()) return strings(e.get(), model, ctx);
        }
        return List.of();
    }

    public static Optional<String> firstString(AnnotationExpr a, ProjectModel model, TypeInfo ctx, String... names) {
        List<String> v = stringValues(a, model, ctx, names);
        return v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
    }

    /** {@code Foo.class} values of an attribute, as raw simple names. */
    public static List<String> classValues(AnnotationExpr a, String... names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            attr(a, n).ifPresent(e -> {
                List<Expression> items = e instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(e);
                for (Expression x : items) {
                    if (x instanceof ClassExpr c) out.add(ProjectModel.rawName(c.getType()));
                }
            });
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** Enum-ish values ({@code RequestMethod.GET}, {@code GET}) as their last name segment. */
    public static List<String> enumValues(AnnotationExpr a, String name) {
        List<String> out = new ArrayList<>();
        attr(a, name).ifPresent(e -> {
            List<Expression> items = e instanceof ArrayInitializerExpr arr ? arr.getValues() : List.of(e);
            for (Expression x : items) {
                if (x instanceof FieldAccessExpr f) out.add(f.getNameAsString());
                else if (x instanceof NameExpr ne) out.add(ne.getNameAsString());
            }
        });
        return out;
    }

    /** Source text of an attribute (for numbers, durations, cron strings, ...). */
    public static Optional<String> literalText(AnnotationExpr a, ProjectModel model, TypeInfo ctx, String name) {
        return attr(a, name).map(e -> {
            List<String> s = strings(e, model, ctx);
            return s.isEmpty() ? e.toString() : s.get(0);
        });
    }

    /** Evaluate an expression to its possible string values (empty when not statically known). */
    public static List<String> strings(Expression e, ProjectModel model, TypeInfo ctx) {
        return strings(e, model, ctx, 0);
    }

    private static List<String> strings(Expression e, ProjectModel model, TypeInfo ctx, int depth) {
        List<String> out = new ArrayList<>();
        if (e == null || depth > MAX_DEPTH) return out;
        if (e instanceof StringLiteralExpr s) {
            out.add(s.asString());
        } else if (e instanceof TextBlockLiteralExpr tb) {
            out.add(textBlock(tb));
        } else if (e.isEnclosedExpr()) {
            return strings(e.asEnclosedExpr().getInner(), model, ctx, depth + 1);
        } else if (e instanceof ArrayInitializerExpr arr) {
            for (Expression v : arr.getValues()) out.addAll(strings(v, model, ctx, depth + 1));
        } else if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            List<String> l = strings(b.getLeft(), model, ctx, depth + 1);
            List<String> r = strings(b.getRight(), model, ctx, depth + 1);
            if (l.isEmpty()) l = List.of(placeholder(b.getLeft()));
            if (r.isEmpty()) r = List.of(placeholder(b.getRight()));
            for (String x : l) for (String y : r) out.add(x + y);
        } else if (e instanceof NameExpr n) {
            out.addAll(constant(n.getNameAsString(), e, model, ctx, depth));
        } else if (e instanceof FieldAccessExpr f) {
            if (model != null && ctx != null) {
                TypeInfo owner = model.resolve(f.getScope().toString(), ctx);
                if (owner != null) {
                    model.findField(owner, f.getNameAsString()).flatMap(ProjectModel.FieldMatch::initializer)
                            .ifPresent(init -> out.addAll(strings(init, model, owner, depth + 1)));
                }
            }
        } else if (e instanceof MethodCallExpr call && call.getScope().isPresent()) {
            // "..." .formatted(x) / String.format("...", x) / "...".concat(...)
            String name = call.getNameAsString();
            if (name.equals("formatted") || name.equals("strip") || name.equals("trim") || name.equals("stripIndent")) {
                return strings(call.getScope().get(), model, ctx, depth + 1);
            }
            if (name.equals("format") && call.getScope().get().toString().equals("String") && call.getArguments().size() > 0) {
                return strings(call.getArgument(0), model, ctx, depth + 1);
            }
        }
        return out;
    }

    private static String placeholder(Expression e) {
        return "{" + (e instanceof NameExpr n ? n.getNameAsString() : "?") + "}";
    }

    /** Text block value with incidental indentation removed and escapes processed. */
    public static String textBlock(TextBlockLiteralExpr tb) {
        try {
            return tb.translateEscapes();
        } catch (RuntimeException ex) {
            return tb.getValue();
        }
    }

    /** A bare name: a local String variable, a constant of ctx (or its outers/supertypes), or a static import. */
    private static List<String> constant(String name, Node at, ProjectModel model, TypeInfo ctx, int depth) {
        // local variable in the enclosing method / lambda
        Node cur = at;
        while (cur != null && !(cur instanceof CallableDeclaration<?>) && !(cur instanceof LambdaExpr)) {
            cur = cur.getParentNode().orElse(null);
        }
        if (cur != null) {
            for (VariableDeclarator v : cur.findAll(VariableDeclarator.class)) {
                if (v.getNameAsString().equals(name) && v.getInitializer().isPresent()
                        && v.getBegin().isPresent() && at.getBegin().isPresent()
                        && v.getBegin().get().isBefore(at.getBegin().get())) {
                    return strings(v.getInitializer().get(), model, ctx, depth + 1);
                }
            }
        }
        if (model == null || ctx == null) return List.of();
        for (TypeInfo t = ctx; t != null; t = t.outer) {
            Optional<ProjectModel.FieldMatch> f = model.findField(t, name);
            if (f.isPresent() && f.get().initializer().isPresent()) {
                return strings(f.get().initializer().get(), model, f.get().owner(), depth + 1);
            }
        }
        for (ImportDeclaration imp : ctx.cu.getImports()) {
            if (!imp.isStatic()) continue;
            String in = imp.getNameAsString();
            String typeName = imp.isAsterisk() ? in : in.endsWith("." + name) ? in.substring(0, in.lastIndexOf('.')) : null;
            if (typeName == null) continue;
            TypeInfo owner = model.byQualifiedName.get(typeName);
            if (owner == null) continue;
            Optional<ProjectModel.FieldMatch> f = model.findField(owner, name);
            if (f.isPresent() && f.get().initializer().isPresent()) {
                return strings(f.get().initializer().get(), model, owner, depth + 1);
            }
        }
        return List.of();
    }
}

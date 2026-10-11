package dev.flowlens;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import dev.flowlens.ProjectModel.TypeInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the entry points of an application: HTTP handlers (Spring MVC / WebFlux annotated and
 * functional, JAX-RS / Quarkus, Micronaut), message listeners (Kafka, RabbitMQ, JMS, SQS,
 * Spring Cloud Stream) and scheduled jobs (@Scheduled, Quartz).
 */
public class EntryPoints {

    /** One entry point. {@code body} is what the walk starts from (a method or a lambda). */
    public static final class Entry {
        public String id;
        public String kind;       // http | message | scheduled
        public String framework;  // spring | spring-webflux | jaxrs | micronaut | kafka | ...
        public String verb;       // GET/POST/... | KAFKA/RABBIT/... | SCHEDULED
        public String path;
        public String label;
        public TypeInfo owner;
        public MethodDeclaration method;  // handler method (null for lambda handlers)
        public Node body;                 // method or lambda to walk
        public int requestBodyParam = -1;

        public String http() {
            return verb + " " + path;
        }
    }

    private static final Map<String, String> SPRING_VERBS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> JAXRS_VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final Map<String, String> MICRONAUT_VERBS = Map.of(
            "Get", "GET", "Post", "POST", "Put", "PUT", "Delete", "DELETE", "Patch", "PATCH", "Head", "HEAD", "Options", "OPTIONS");
    private static final Set<String> ROUTE_VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private final ProjectModel model;
    private final TypeResolver types;

    public EntryPoints(ProjectModel model, TypeResolver types) {
        this.model = model;
        this.types = types;
    }

    public List<Entry> find() {
        List<Entry> out = new ArrayList<>();
        List<TypeInfo> all = new ArrayList<>(model.byQualifiedName.values());
        all.sort((a, b) -> a.qualifiedName.compareTo(b.qualifiedName));
        for (TypeInfo t : all) {
            if (ProjectModel.CONTROLLER.equals(t.layer) && t.isConcrete()) {
                if (model.isMicronaut(t)) micronaut(t, out);
                else if (Annotations.get(t.decl, "Path").isPresent() || inheritsJaxRs(t)) jaxrs(t, out);
                else springMvc(t, out);
            } else if (t.isConcrete() && !t.isInterface && inheritsJaxRs(t)) {
                jaxrs(t, out);
            }
            listeners(t, out);
            routerFunctions(t, out);
            quartz(t, out);
        }
        // unique ids (overloads, multi-path mappings)
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Entry e : out) {
            int n = seen.merge(e.id, 1, Integer::sum);
            if (n > 1) e.id = e.id + "~" + n;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Spring MVC / WebFlux annotated controllers
    // ------------------------------------------------------------------------------------------

    private void springMvc(TypeInfo controller, List<Entry> out) {
        List<String> classPaths = classLevelPaths(controller, "RequestMapping", "value", "path");
        for (Handler h : handlers(controller, m -> springMapping(m))) {
            AnnotationExpr anno = h.mapping;
            String an = Annotations.name(anno);
            List<String> verbs = new ArrayList<>();
            if (SPRING_VERBS.containsKey(an)) verbs.add(SPRING_VERBS.get(an));
            else verbs.addAll(Annotations.enumValues(anno, "method"));
            if (verbs.isEmpty()) verbs.add("ANY");
            List<String> paths = Annotations.stringValues(anno, model, h.declaringType, "value", "path");
            if (paths.isEmpty()) paths = List.of("");
            for (String cp : classPaths) for (String p : paths) for (String v : verbs) {
                Entry e = base(controller, h.impl, "http", "spring", v, joinPath(cp, p));
                e.requestBodyParam = requestBodyIndex(h.impl, Set.of("RequestBody"));
                out.add(e);
            }
        }
    }

    private Optional<AnnotationExpr> springMapping(MethodDeclaration m) {
        for (AnnotationExpr a : m.getAnnotations()) {
            String n = Annotations.name(a);
            if (SPRING_VERBS.containsKey(n) || n.equals("RequestMapping")) return Optional.of(a);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------
    // JAX-RS (Jakarta EE, Quarkus, RESTEasy) and Micronaut
    // ------------------------------------------------------------------------------------------

    private boolean inheritsJaxRs(TypeInfo t) {
        for (TypeInfo s : model.hierarchy(t)) {
            if (s != t && Annotations.get(s.decl, "Path").isPresent()) return true;
        }
        return false;
    }

    private void jaxrs(TypeInfo resource, List<Entry> out) {
        List<String> classPaths = classLevelPaths(resource, "Path", "value");
        for (Handler h : handlers(resource, m -> {
            for (AnnotationExpr a : m.getAnnotations()) if (JAXRS_VERBS.contains(Annotations.name(a))) return Optional.of(a);
            return Optional.empty();
        })) {
            String verb = Annotations.name(h.mapping);
            String methodPath = Annotations.get(h.mappingMethod, "Path")
                    .flatMap(a -> Annotations.firstString(a, model, h.declaringType, "value")).orElse("");
            for (String cp : classPaths) {
                Entry e = base(resource, h.impl, "http", "jaxrs", verb, joinPath(cp, methodPath));
                e.requestBodyParam = unannotatedParam(h.impl);
                out.add(e);
            }
        }
    }

    private void micronaut(TypeInfo controller, List<Entry> out) {
        List<String> classPaths = classLevelPaths(controller, "Controller", "value");
        for (Handler h : handlers(controller, m -> {
            for (AnnotationExpr a : m.getAnnotations()) if (MICRONAUT_VERBS.containsKey(Annotations.name(a))) return Optional.of(a);
            return Optional.empty();
        })) {
            String verb = MICRONAUT_VERBS.get(Annotations.name(h.mapping));
            List<String> paths = Annotations.stringValues(h.mapping, model, h.declaringType, "value", "uri", "uris");
            if (paths.isEmpty()) paths = List.of("");
            for (String cp : classPaths) for (String p : paths) {
                Entry e = base(controller, h.impl, "http", "micronaut", verb, joinPath(cp, p));
                e.requestBodyParam = requestBodyIndex(h.impl, Set.of("Body"));
                out.add(e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // handler discovery with inherited mappings (e.g. OpenAPI-generated API interfaces)
    // ------------------------------------------------------------------------------------------

    private record Handler(MethodDeclaration impl, AnnotationExpr mapping, MethodDeclaration mappingMethod, TypeInfo declaringType) {}

    private interface MappingFinder {
        Optional<AnnotationExpr> find(MethodDeclaration m);
    }

    /**
     * Every handler of {@code type}: methods declared on it or inherited from project supertypes,
     * with the mapping annotation found on the method itself or on the method it overrides.
     */
    private List<Handler> handlers(TypeInfo type, MappingFinder finder) {
        Map<String, Handler> bySig = new LinkedHashMap<>();
        List<TypeInfo> hierarchy = model.hierarchy(type);
        for (TypeInfo t : hierarchy) {
            for (MethodDeclaration m : t.decl.getMethods()) {
                String sig = ProjectModel.signature(m);
                if (bySig.containsKey(sig)) continue;
                // most-derived implementation with a body
                MethodDeclaration impl = m;
                // mapping: on the method, else on an overridden declaration higher up
                Optional<AnnotationExpr> mapping = finder.find(m);
                MethodDeclaration mappingMethod = m;
                TypeInfo declaring = t;
                if (mapping.isEmpty()) {
                    for (TypeInfo sup : hierarchy) {
                        if (sup == t) continue;
                        for (MethodDeclaration sm : sup.methodsByName.getOrDefault(m.getNameAsString(), List.of())) {
                            if (sm.getParameters().size() == m.getParameters().size()) {
                                Optional<AnnotationExpr> ma = finder.find(sm);
                                if (ma.isPresent()) {
                                    mapping = ma;
                                    mappingMethod = sm;
                                    declaring = sup;
                                    break;
                                }
                            }
                        }
                        if (mapping.isPresent()) break;
                    }
                }
                if (mapping.isEmpty()) continue;
                if (impl.getBody().isEmpty() && !type.isConcrete()) continue;
                bySig.put(sig, new Handler(impl, mapping.get(), mappingMethod, declaring));
            }
        }
        return new ArrayList<>(bySig.values());
    }

    private List<String> classLevelPaths(TypeInfo t, String anno, String... attrs) {
        for (TypeInfo s : model.hierarchy(t)) {
            Optional<AnnotationExpr> a = Annotations.get(s.decl, anno);
            if (a.isPresent()) {
                List<String> p = Annotations.stringValues(a.get(), model, s, attrs);
                if (!p.isEmpty()) return p;
            }
        }
        return List.of("");
    }

    private static int requestBodyIndex(MethodDeclaration m, Set<String> annos) {
        for (int i = 0; i < m.getParameters().size(); i++) {
            for (String a : annos) if (Annotations.get(m.getParameter(i), a).isPresent()) return i;
        }
        return -1;
    }

    /** JAX-RS entity parameter: the one without @PathParam/@QueryParam/@Context/... */
    private static int unannotatedParam(MethodDeclaration m) {
        for (int i = 0; i < m.getParameters().size(); i++) {
            if (m.getParameter(i).getAnnotations().isEmpty()) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------
    // messaging + scheduling
    // ------------------------------------------------------------------------------------------

    private void listeners(TypeInfo t, List<Entry> out) {
        // class-level @KafkaListener / @RabbitListener with @KafkaHandler / @RabbitHandler methods
        for (String classAnno : List.of("KafkaListener", "RabbitListener")) {
            Optional<AnnotationExpr> ca = Annotations.get(t.decl, classAnno);
            if (ca.isEmpty()) continue;
            String handlerAnno = classAnno.equals("KafkaListener") ? "KafkaHandler" : "RabbitHandler";
            for (MethodDeclaration m : t.decl.getMethods()) {
                if (Annotations.get(m, handlerAnno).isPresent()) message(t, m, ca.get(), out);
            }
        }
        for (MethodDeclaration m : t.decl.getMethods()) {
            for (String anno : List.of("KafkaListener", "RabbitListener", "JmsListener", "SqsListener",
                    "StreamListener", "Incoming", "NatsListener", "PulsarListener")) {
                Annotations.get(m, anno).ifPresent(a -> message(t, m, a, out));
            }
            Annotations.get(m, "Scheduled").ifPresent(a -> {
                String schedule = null;
                for (String attr : List.of("cron", "fixedRate", "fixedDelay", "fixedRateString", "fixedDelayString", "every")) {
                    Optional<String> v = Annotations.literalText(a, model, t, attr);
                    if (v.isPresent()) { schedule = attr.replace("String", "") + "=" + v.get(); break; }
                }
                out.add(base(t, m, "scheduled", "spring", "SCHEDULED", schedule == null ? "" : schedule));
            });
            // Spring Cloud Stream functional consumers: @Bean Consumer<X> name()
            if (Annotations.get(m, "Bean").isPresent() && Annotations.get(t.decl, "Configuration").isPresent()) {
                String rt = ProjectModel.rawName(m.getType());
                if (rt.equals("Consumer") || rt.equals("Function")) {
                    out.add(base(t, m, "message", "stream", "STREAM", m.getNameAsString() + "-in-0"));
                }
            }
        }
    }

    private void message(TypeInfo t, MethodDeclaration m, AnnotationExpr a, List<Entry> out) {
        String an = Annotations.name(a);
        String broker;
        List<String> dests;
        switch (an) {
            case "KafkaListener" -> {
                broker = "KAFKA";
                dests = Annotations.stringValues(a, model, t, "topics", "topicPattern", "value");
            }
            case "RabbitListener" -> {
                broker = "RABBIT";
                dests = Annotations.stringValues(a, model, t, "queues", "value");
                if (dests.isEmpty() && Annotations.hasAttr(a, "bindings")) dests = List.of(a.toString().replaceAll("\\s+", " "));
            }
            case "JmsListener" -> {
                broker = "JMS";
                dests = Annotations.stringValues(a, model, t, "destination");
            }
            case "SqsListener" -> {
                broker = "SQS";
                dests = Annotations.stringValues(a, model, t, "value", "queueNames");
            }
            case "Incoming" -> {
                broker = "CHANNEL";
                dests = Annotations.stringValues(a, model, t, "value");
            }
            default -> {
                broker = an.replace("Listener", "").toUpperCase(Locale.ROOT);
                dests = Annotations.stringValues(a, model, t, "value", "subject", "topic");
            }
        }
        String dest = dests.isEmpty() ? "?" : String.join(",", dests);
        Entry e = base(t, m, "message", broker.toLowerCase(Locale.ROOT), broker, dest);
        e.requestBodyParam = m.getParameters().isEmpty() ? -1 : 0;
        out.add(e);
    }

    private void quartz(TypeInfo t, List<Entry> out) {
        if (!t.isConcrete()) return;
        boolean job = false;
        for (var ref : t.superRefs) {
            String n = ref.getNameAsString();
            if (n.equals("Job") || n.equals("QuartzJobBean") || n.equals("StatefulJob")) job = true;
        }
        if (!job) return;
        for (String name : List.of("execute", "executeInternal")) {
            for (MethodDeclaration m : t.methodsByName.getOrDefault(name, List.of())) {
                if (m.getBody().isPresent()) out.add(base(t, m, "scheduled", "quartz", "SCHEDULED", "quartz:" + t.simpleName));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // WebFlux functional routes: route().GET("/x", h::m), route(GET("/x"), h::m), nest(path("/api"), ...)
    // ------------------------------------------------------------------------------------------

    private void routerFunctions(TypeInfo t, List<Entry> out) {
        for (MethodCallExpr call : t.decl.findAll(MethodCallExpr.class)) {
            if (model.enclosingType(call) != t) continue;
            String name = call.getNameAsString();
            String verb = null;
            String path = null;
            Expression handler = null;
            if (ROUTE_VERBS.contains(name) && call.getArguments().size() >= 2 && call.getScope().isPresent()) {
                // builder style: .GET("/path", handler)
                List<String> p = Annotations.strings(call.getArgument(0), model, t);
                if (p.isEmpty()) continue;
                verb = name;
                path = p.get(0);
                handler = call.getArgument(call.getArguments().size() - 1);
            } else if ((name.equals("route") || name.equals("andRoute")) && call.getArguments().size() == 2) {
                // predicate style: route(GET("/path"), handler)
                MethodCallExpr predicate = findVerbCall(call.getArgument(0));
                if (predicate == null || predicate.getArguments().isEmpty()) continue;
                List<String> p = Annotations.strings(predicate.getArgument(0), model, t);
                if (p.isEmpty()) continue;
                verb = predicate.getNameAsString();
                path = p.get(0);
                handler = call.getArgument(1);
            }
            if (verb == null) continue;
            String full = joinPath(nestedPrefix(call, t), path);
            Entry e = new Entry();
            e.kind = "http";
            e.framework = "spring-webflux";
            e.verb = verb;
            e.path = full;
            e.owner = t;
            e.id = "EP:" + t.qualifiedName + "#route:" + verb + " " + full;
            if (handler instanceof MethodReferenceExpr mr) {
                MethodTarget target = methodRefTarget(mr);
                if (target != null) {
                    e.method = target.method;
                    e.body = target.method;
                    e.owner = target.owner;
                    e.label = target.owner.simpleName + "." + target.method.getNameAsString() + "()";
                }
            } else if (handler instanceof LambdaExpr lambda) {
                e.body = lambda;
                e.label = t.simpleName + " λ " + verb + " " + full;
            } else {
                e.body = handler;
                e.label = t.simpleName + " route " + verb + " " + full;
            }
            if (e.body == null) {
                e.body = handler;
                e.label = t.simpleName + " route " + verb + " " + full;
            }
            out.add(e);
        }
    }

    private MethodCallExpr findVerbCall(Expression e) {
        for (MethodCallExpr c : e.findAll(MethodCallExpr.class)) {
            if (ROUTE_VERBS.contains(c.getNameAsString())) return c;
        }
        return null;
    }

    /** Prefix from enclosing .path("/api", b -> ...) / nest(path("/api"), ...) calls. */
    private String nestedPrefix(MethodCallExpr call, TypeInfo t) {
        StringBuilder prefix = new StringBuilder();
        Node child = call;
        Node cur = call.getParentNode().orElse(null);
        while (cur != null && !(cur instanceof MethodDeclaration)) {
            if (cur instanceof MethodCallExpr outer && outer.getArguments().contains(child)) {
                String n = outer.getNameAsString();
                if ((n.equals("path") || n.equals("nest") || n.equals("andNest")) && !outer.getArguments().isEmpty()) {
                    Expression first = outer.getArgument(0);
                    List<String> p = Annotations.strings(first, model, t);
                    if (p.isEmpty()) {
                        MethodCallExpr pc = first.findFirst(MethodCallExpr.class, c -> c.getNameAsString().equals("path")).orElse(null);
                        if (pc != null && !pc.getArguments().isEmpty()) p = Annotations.strings(pc.getArgument(0), model, t);
                    }
                    if (!p.isEmpty() && first != child) prefix.insert(0, p.get(0));
                }
            }
            child = cur;
            cur = cur.getParentNode().orElse(null);
        }
        return prefix.toString();
    }

    record MethodTarget(TypeInfo owner, MethodDeclaration method) {}

    /** {@code handler::method}, {@code this::method}, {@code Type::method}. */
    MethodTarget methodRefTarget(MethodReferenceExpr mr) {
        TypeResolver.RType scope = types.typeOf(mr.getScope());
        if (scope == null || scope.project == null) return null;
        List<ProjectModel.MethodRef> ms = model.methodsNamed(scope.project, mr.getIdentifier());
        if (ms.isEmpty()) return null;
        ProjectModel.MethodRef ref = ms.get(0);
        if (ref.method().getBody().isEmpty()) {
            for (TypeInfo impl : model.implementations(scope.project)) {
                for (ProjectModel.MethodRef r : model.methodsNamed(impl, mr.getIdentifier())) {
                    if (r.method().getBody().isPresent()) return new MethodTarget(r.owner(), r.method());
                }
            }
        }
        return new MethodTarget(ref.owner(), ref.method());
    }

    // ------------------------------------------------------------------------------------------

    private Entry base(TypeInfo owner, MethodDeclaration m, String kind, String framework, String verb, String path) {
        Entry e = new Entry();
        e.kind = kind;
        e.framework = framework;
        e.verb = verb;
        e.path = path;
        e.owner = owner;
        e.method = m;
        e.body = m;
        e.label = owner.simpleName + "." + m.getNameAsString() + "()";
        e.id = "EP:" + owner.qualifiedName + "#" + ProjectModel.signature(m);
        if (!"http".equals(kind) || !"ANY".equals(verb)) e.id += "@" + verb + " " + path;
        return e;
    }

    static String joinPath(String a, String b) {
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
}

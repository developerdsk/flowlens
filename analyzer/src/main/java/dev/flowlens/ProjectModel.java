package dev.flowlens;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import dev.flowlens.model.Diagnostic;
import dev.flowlens.source.SourceCollector;
import dev.flowlens.source.SourceFile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An index of every type in the analyzed source tree: architectural layer, supertypes and
 * implementations, Spring beans, repositories, entities (table + columns), constants,
 * event listeners and MyBatis mapper statements. Built once, then queried during the walk.
 */
public class ProjectModel {

    public static final String CONTROLLER = "controller";
    public static final String SERVICE = "service";
    public static final String REPOSITORY = "repository";
    public static final String ENTITY = "entity";
    public static final String OTHER = "other";

    /** Spring Data base interfaces (by simple name) and the store they imply. */
    private static final Map<String, String> REPO_BASES = Map.ofEntries(
            Map.entry("Repository", ""), Map.entry("CrudRepository", ""), Map.entry("ListCrudRepository", ""),
            Map.entry("PagingAndSortingRepository", ""), Map.entry("ListPagingAndSortingRepository", ""),
            Map.entry("JpaRepository", "jpa"), Map.entry("JpaSpecificationExecutor", "jpa"),
            Map.entry("ReactiveCrudRepository", ""), Map.entry("ReactiveSortingRepository", ""),
            Map.entry("MongoRepository", "mongo"), Map.entry("ReactiveMongoRepository", "mongo"),
            Map.entry("R2dbcRepository", "r2dbc"), Map.entry("ElasticsearchRepository", "elasticsearch"),
            Map.entry("CassandraRepository", "cassandra"), Map.entry("Neo4jRepository", "neo4j"),
            Map.entry("KeyValueRepository", "keyvalue"), Map.entry("CouchbaseRepository", "couchbase"),
            Map.entry("BaseMapper", "mybatis"), Map.entry("IService", "mybatis"));

    /** Types every Java file can see without importing — never project types. */
    private static final Set<String> JAVA_LANG = Set.of(
            "String", "Object", "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character",
            "Number", "Math", "System", "Thread", "Runnable", "Exception", "RuntimeException", "Error",
            "Throwable", "Iterable", "Comparable", "CharSequence", "StringBuilder", "Class", "Void", "Enum",
            "Record", "Override", "Deprecated", "FunctionalInterface", "SuppressWarnings");

    public static class TypeInfo {
        public TypeDeclaration<?> decl;
        public CompilationUnit cu;
        public String qualifiedName;
        /** Unique id: the qualified name, or "module:qualifiedName" for a same-named class in another module. */
        public String key;
        public String simpleName;
        public String packageName;
        public String file;
        public String module;
        public boolean generated;
        public TypeInfo outer;

        public String layer = OTHER;
        public boolean isInterface;
        public boolean isAbstract;

        public boolean isRepository;
        /** Entity simple name managed by a repository (from its generic argument). */
        public String repoEntity;
        /** jpa | mongo | jdbc | r2dbc | mybatis | ... */
        public String repoTech;
        public boolean isMyBatisMapper;

        public boolean isFeignClient;
        public String feignName;
        public String feignPath = "";

        public String beanName;
        public boolean primary;

        // entity
        public boolean isEntity;
        public String entityName;
        public String table;
        /** field name -> column name */
        public final Map<String, String> columns = new LinkedHashMap<>();

        public final List<ClassOrInterfaceType> superRefs = new ArrayList<>();
        public final List<TypeInfo> supertypes = new ArrayList<>();
        public final List<TypeInfo> subtypes = new ArrayList<>();
        public final Map<String, List<MethodDeclaration>> methodsByName = new LinkedHashMap<>();

        public boolean hasAnno(String name) {
            return Annotations.get(decl, name).isPresent();
        }

        public boolean isConcrete() {
            return !isInterface && !isAbstract;
        }

        @Override public String toString() { return qualifiedName; }
    }

    /** A method found by name/arity lookup, with the type that declares it. */
    public record MethodRef(TypeInfo owner, MethodDeclaration method, boolean ambiguous) {}

    /** A MyBatis mapper XML statement. */
    public record MapperStatement(String op, String table, String sql, String file) {}

    /** An @EventListener method and the event type it consumes. */
    public record EventListenerInfo(TypeInfo owner, MethodDeclaration method, String eventType) {}

    /** First type registered under each qualified name (see {@link #qualified} for module-aware lookup). */
    public final Map<String, TypeInfo> byQualifiedName = new LinkedHashMap<>();
    /** Every type, including same-named classes from different modules. */
    public final List<TypeInfo> allTypes = new ArrayList<>();
    /** "module|qualifiedName" -> type */
    private final Map<String, TypeInfo> byModuleQualified = new HashMap<>();
    public final Map<String, List<TypeInfo>> bySimpleName = new HashMap<>();
    private final Map<TypeDeclaration<?>, TypeInfo> byDecl = new IdentityHashMap<>();
    /** entity name (JPQL name) or simple name -> entity type */
    public final Map<String, TypeInfo> entities = new HashMap<>();
    public final Map<String, MapperStatement> mapperStatements = new HashMap<>();
    public final List<EventListenerInfo> eventListeners = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();

    private final Map<TypeInfo, Map<String, Optional<TypeInfo>>> resolveCache = new IdentityHashMap<>();

    // ------------------------------------------------------------------------------------------
    // indexing
    // ------------------------------------------------------------------------------------------

    public void index(List<CompilationUnit> units, Map<CompilationUnit, SourceFile> origins, List<SourceFile> mapperXml) {
        for (CompilationUnit cu : units) {
            SourceFile sf = origins.get(cu);
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (TypeDeclaration<?> top : cu.getTypes()) {
                register(top, null, cu, pkg, sf);
            }
        }
        for (TypeInfo t : allTypes) linkSupertypes(t);
        for (TypeInfo t : allTypes) classify(t);
        for (TypeInfo t : allTypes) classifyRepository(t, new LinkedHashSet<>());
        for (TypeInfo t : allTypes) inheritInterfaceLayer(t);
        for (TypeInfo t : allTypes) indexEventListeners(t);
        new MyBatisXml(this).index(mapperXml);
        for (TypeInfo t : allTypes) {
            if (t.isInterface && mapperStatementsFor(t.qualifiedName) && !t.isRepository) {
                t.isRepository = true;
                t.isMyBatisMapper = true;
                t.repoTech = "mybatis";
                t.layer = REPOSITORY;
            }
        }
    }

    private boolean mapperStatementsFor(String namespace) {
        String prefix = namespace + ".";
        for (String k : mapperStatements.keySet()) if (k.startsWith(prefix)) return true;
        return false;
    }

    private void register(TypeDeclaration<?> decl, TypeInfo outer, CompilationUnit cu, String pkg, SourceFile sf) {
        TypeInfo ti = new TypeInfo();
        ti.decl = decl;
        ti.cu = cu;
        ti.outer = outer;
        ti.simpleName = decl.getNameAsString();
        ti.packageName = pkg;
        ti.qualifiedName = outer != null ? outer.qualifiedName + "." + ti.simpleName
                : pkg.isEmpty() ? ti.simpleName : pkg + "." + ti.simpleName;
        ti.file = sf != null ? sf.path : null;
        ti.module = sf != null ? sf.module : null;
        ti.generated = ti.file != null && SourceCollector.isGeneratedPath(ti.file);

        if (decl instanceof ClassOrInterfaceDeclaration c) {
            ti.isInterface = c.isInterface();
            ti.isAbstract = c.isAbstract();
            ti.superRefs.addAll(c.getExtendedTypes());
            ti.superRefs.addAll(c.getImplementedTypes());
        } else if (decl instanceof RecordDeclaration r) {
            ti.superRefs.addAll(r.getImplementedTypes());
        } else if (decl instanceof EnumDeclaration e) {
            ti.superRefs.addAll(e.getImplementedTypes());
        }
        for (MethodDeclaration m : decl.getMethods()) {
            ti.methodsByName.computeIfAbsent(m.getNameAsString(), k -> new ArrayList<>()).add(m);
        }

        ti.key = ti.qualifiedName;
        TypeInfo existing = byQualifiedName.get(ti.qualifiedName);
        if (existing != null && existing.module != null && ti.module != null && !existing.module.equals(ti.module)) {
            // Same class name in another module (e.g. two services sharing a package layout): keep both.
            ti.key = ti.module + ":" + ti.qualifiedName;
        } else if (existing != null) {
            // Duplicate class within one module (a generated copy). Prefer hand-written sources.
            if (existing.generated && !ti.generated) {
                unregister(existing);
            } else {
                diagnostics.add(new Diagnostic("info", ti.file,
                        "duplicate class " + ti.qualifiedName + " (also in " + existing.file + "); using the first"));
                byDecl.put(decl, existing);
                return;
            }
        }
        if (!byQualifiedName.containsKey(ti.qualifiedName)) byQualifiedName.put(ti.qualifiedName, ti);
        if (ti.module != null) byModuleQualified.putIfAbsent(ti.module + "|" + ti.qualifiedName, ti);
        allTypes.add(ti);
        bySimpleName.computeIfAbsent(ti.simpleName, k -> new ArrayList<>()).add(ti);
        byDecl.put(decl, ti);

        for (BodyDeclaration<?> member : decl.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) register(nested, ti, cu, pkg, sf);
        }
    }

    private void unregister(TypeInfo t) {
        byQualifiedName.remove(t.qualifiedName);
        allTypes.remove(t);
        if (t.module != null) byModuleQualified.remove(t.module + "|" + t.qualifiedName, t);
        List<TypeInfo> list = bySimpleName.get(t.simpleName);
        if (list != null) list.remove(t);
        byDecl.remove(t.decl);
    }

    private void linkSupertypes(TypeInfo t) {
        for (ClassOrInterfaceType ref : t.superRefs) {
            TypeInfo sup = resolve(ref.getNameWithScope(), t);
            if (sup != null && sup != t) {
                t.supertypes.add(sup);
                sup.subtypes.add(t);
            }
        }
    }

    private void classify(TypeInfo t) {
        TypeDeclaration<?> d = t.decl;
        if (has(d, "RestController") || has(d, "Controller") && !isMicronaut(t)) {
            t.layer = CONTROLLER;
        } else if (isMicronaut(t) && has(d, "Controller")) {
            t.layer = CONTROLLER;
        } else if (has(d, "Path") && hasJaxRsMethods(t)) {
            t.layer = CONTROLLER;
        }

        if (has(d, "Entity") || has(d, "Document") || has(d, "TableName")
                || has(d, "Table") && !t.isInterface && (has(d, "Id") || hasIdField(t))) {
            t.isEntity = true;
            t.layer = ENTITY;
            indexEntity(t);
        }

        if (has(d, "Repository")) {
            t.isRepository = true;
            t.layer = REPOSITORY;
        }
        if (has(d, "Mapper") && t.isInterface && !isMapStruct(t)) {
            t.isRepository = true;
            t.isMyBatisMapper = true;
            t.repoTech = "mybatis";
            t.layer = REPOSITORY;
        }
        if (has(d, "FeignClient") || has(d, "HttpExchange") && t.isInterface || has(d, "RegisterRestClient")) {
            t.isFeignClient = true;
            t.layer = "external";
            AnnotationExpr a = Annotations.get(d, "FeignClient")
                    .or(() -> Annotations.get(d, "HttpExchange"))
                    .or(() -> Annotations.get(d, "RegisterRestClient")).orElseThrow();
            t.feignName = Annotations.firstString(a, this, t, "name", "value", "configKey", "url", "baseUri")
                    .orElse(t.simpleName);
            t.feignPath = Annotations.firstString(a, this, t, "path").orElse("");
            if (Annotations.name(a).equals("HttpExchange")) {
                t.feignPath = Annotations.firstString(a, this, t, "value", "url").orElse("");
                t.feignName = t.simpleName;
            }
        }

        if (OTHER.equals(t.layer)) {
            if (has(d, "Service") || has(d, "Component") || has(d, "Named") || has(d, "ApplicationScoped")
                    || has(d, "Singleton") || has(d, "RequestScoped") || has(d, "Stateless") || has(d, "Configuration")) {
                t.layer = SERVICE;
            }
        }

        for (String stereo : List.of("Service", "Component", "Repository", "Controller", "RestController", "Named")) {
            Optional<AnnotationExpr> a = Annotations.get(d, stereo);
            if (a.isPresent()) {
                t.beanName = Annotations.firstString(a.get(), this, t, "value").orElse(null);
                break;
            }
        }
        if (t.beanName == null || t.beanName.isEmpty()) t.beanName = decapitalize(t.simpleName);
        t.primary = has(d, "Primary");
        Annotations.get(d, "Qualifier").flatMap(q -> Annotations.firstString(q, this, t, "value"))
                .ifPresent(q -> t.beanName = q);
    }

    private static boolean has(NodeWithAnnotations<?> n, String name) {
        return Annotations.get(n, name).isPresent();
    }

    /** MapStruct's @Mapper (a DTO converter), not MyBatis'. */
    private static boolean isMapStruct(TypeInfo t) {
        AnnotationExpr a = Annotations.get(t.decl, "Mapper").orElseThrow();
        if (a.getNameAsString().startsWith("org.mapstruct")) return true;
        if (Annotations.hasAttr(a, "componentModel") || Annotations.hasAttr(a, "uses") || Annotations.hasAttr(a, "config")
                || Annotations.hasAttr(a, "unmappedTargetPolicy")) return true;
        for (ImportDeclaration imp : t.cu.getImports()) {
            String n = imp.getNameAsString();
            if (n.equals("org.mapstruct.Mapper") || imp.isAsterisk() && n.equals("org.mapstruct")) return true;
        }
        return false;
    }

    boolean isMicronaut(TypeInfo t) {
        for (ImportDeclaration imp : t.cu.getImports()) {
            if (imp.getNameAsString().startsWith("io.micronaut")) return true;
        }
        return false;
    }

    private static boolean hasJaxRsMethods(TypeInfo t) {
        for (MethodDeclaration m : t.decl.getMethods()) {
            for (String v : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Path")) {
                if (Annotations.get(m, v).isPresent()) return true;
            }
        }
        return false;
    }

    private static boolean hasIdField(TypeInfo t) {
        for (FieldDeclaration f : t.decl.getFields()) if (Annotations.get(f, "Id").isPresent()) return true;
        return false;
    }

    private void indexEntity(TypeInfo t) {
        TypeDeclaration<?> d = t.decl;
        t.entityName = Annotations.get(d, "Entity")
                .flatMap(a -> Annotations.firstString(a, this, t, "name"))
                .filter(s -> !s.isBlank()).orElse(t.simpleName);

        String table = null;
        Optional<AnnotationExpr> tableAnno = Annotations.get(d, "Table");
        if (tableAnno.isPresent()) {
            String name = Annotations.firstString(tableAnno.get(), this, t, "name", "value").orElse(null);
            String schema = Annotations.firstString(tableAnno.get(), this, t, "schema").orElse(null);
            if (name != null && !name.isBlank()) {
                table = (schema != null && !schema.isBlank() ? schema + "." : "") + name;
            }
        }
        if (table == null) {
            table = Annotations.get(d, "TableName")
                    .flatMap(a -> Annotations.firstString(a, this, t, "value")).orElse(null);
        }
        if (table == null && has(d, "Document")) {
            table = Annotations.get(d, "Document")
                    .flatMap(a -> Annotations.firstString(a, this, t, "collection", "value"))
                    .filter(s -> !s.isBlank()).orElse(decapitalize(t.simpleName));
        }
        if (table == null) table = snake(t.entityName);
        // SQL identifiers are case-insensitive (normalize); Mongo collection names are not.
        t.table = has(d, "Document") ? table : normalizeTable(table);

        // columns, including those inherited from @MappedSuperclass parents in the project
        for (FieldDeclaration f : d.getFields()) addColumns(t, f);
        for (ClassOrInterfaceType ref : t.superRefs) {
            TypeInfo sup = resolve(ref.getNameWithScope(), t);
            if (sup != null) for (FieldDeclaration f : sup.decl.getFields()) addColumns(t, f);
        }

        entities.putIfAbsent(t.entityName, t);
        entities.putIfAbsent(t.simpleName, t);
    }

    private void addColumns(TypeInfo t, FieldDeclaration f) {
        if (f.isStatic() || f.hasModifier(Modifier.Keyword.TRANSIENT) || has(f, "Transient")
                || has(f, "OneToMany") || has(f, "ManyToMany")) return;
        for (VariableDeclarator v : f.getVariables()) {
            String field = v.getNameAsString();
            String col = Annotations.get(f, "Column")
                    .or(() -> Annotations.get(f, "JoinColumn"))
                    .or(() -> Annotations.get(f, "TableField"))
                    .or(() -> Annotations.get(f, "Field"))
                    .flatMap(a -> Annotations.firstString(a, this, t, "name", "value"))
                    .filter(s -> !s.isBlank())
                    .orElse(has(f, "ManyToOne") || has(f, "OneToOne") ? snake(field) + "_id" : snake(field));
            t.columns.putIfAbsent(field, col.toLowerCase(Locale.ROOT));
        }
    }

    /** Spring Data repository detection, following project base repositories transitively. */
    private boolean classifyRepository(TypeInfo t, Set<TypeInfo> visiting) {
        if (!visiting.add(t)) return t.isRepository;
        boolean repo = t.isRepository;
        String entity = t.repoEntity;
        String tech = t.repoTech;
        for (ClassOrInterfaceType ref : t.superRefs) {
            TypeInfo sup = resolve(ref.getNameWithScope(), t);
            String firstArg = ref.getTypeArguments().filter(a -> !a.isEmpty())
                    .map(a -> rawName(a.get(0))).orElse(null);
            if (sup != null) {
                if (classifyRepository(sup, visiting)) {
                    repo = true;
                    if (firstArg != null && isEntityName(firstArg, t)) entity = firstArg;
                    else if (entity == null) entity = sup.repoEntity;
                    if (tech == null) tech = sup.repoTech;
                }
            } else if (REPO_BASES.containsKey(ref.getNameAsString())) {
                repo = true;
                if (firstArg != null) entity = firstArg;
                String impliedTech = REPO_BASES.get(ref.getNameAsString());
                if (!impliedTech.isEmpty()) tech = impliedTech;
                if ("mybatis".equals(impliedTech)) t.isMyBatisMapper = t.isInterface;
            }
        }
        if (repo) {
            t.isRepository = true;
            t.layer = REPOSITORY;
            t.repoEntity = entity;
            if (tech == null) {
                TypeInfo e = entityType(entity);
                tech = e == null ? "jpa" : e.hasAnno("Document") ? "mongo" : e.hasAnno("Entity") ? "jpa" : "jdbc";
            }
            t.repoTech = tech;
        }
        return repo;
    }

    private boolean isEntityName(String name, TypeInfo ctx) {
        TypeInfo e = resolve(name, ctx);
        return e != null ? e.isEntity || !e.isInterface : !name.equals("T") && !name.equals("E") && name.length() > 1;
    }

    /** Interfaces take the layer of their implementations (service interfaces, etc.). */
    private void inheritInterfaceLayer(TypeInfo t) {
        if (!t.isInterface || !OTHER.equals(t.layer)) return;
        for (TypeInfo impl : implementations(t)) {
            if (!OTHER.equals(impl.layer) && !ENTITY.equals(impl.layer)) {
                t.layer = impl.layer;
                return;
            }
        }
    }

    private void indexEventListeners(TypeInfo t) {
        for (MethodDeclaration m : t.decl.getMethods()) {
            Optional<AnnotationExpr> a = Annotations.get(m, "EventListener")
                    .or(() -> Annotations.get(m, "TransactionalEventListener"))
                    .or(() -> Annotations.get(m, "Observes"));
            String eventType = null;
            if (a.isPresent()) {
                // @EventListener(SomeEvent.class) or the first parameter's type
                eventType = Annotations.classValues(a.get(), "value", "classes").stream().findFirst().orElse(null);
                if (eventType == null && !m.getParameters().isEmpty()) eventType = rawName(m.getParameter(0).getType());
            } else {
                for (Parameter p : m.getParameters()) {
                    if (has(p, "Observes") || has(p, "ObservesAsync")) eventType = rawName(p.getType());
                }
            }
            if (eventType == null && t.supertypes.isEmpty() && "onApplicationEvent".equals(m.getNameAsString())) {
                for (ClassOrInterfaceType ref : t.superRefs) {
                    if (ref.getNameAsString().equals("ApplicationListener")) {
                        eventType = ref.getTypeArguments().filter(x -> !x.isEmpty()).map(x -> rawName(x.get(0))).orElse(null);
                    }
                }
            }
            if (eventType != null) eventListeners.add(new EventListenerInfo(t, m, eventType));
        }
    }

    // ------------------------------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------------------------------

    /** A type by qualified name, preferring the copy in {@code ctx}'s module. */
    public TypeInfo qualified(String qualifiedName, TypeInfo ctx) {
        if (ctx != null && ctx.module != null) {
            TypeInfo t = byModuleQualified.get(ctx.module + "|" + qualifiedName);
            if (t != null) return t;
        }
        return byQualifiedName.get(qualifiedName);
    }

    public TypeInfo typeOfDecl(TypeDeclaration<?> decl) {
        return byDecl.get(decl);
    }

    /** The innermost project type enclosing an AST node. */
    public TypeInfo enclosingType(com.github.javaparser.ast.Node n) {
        com.github.javaparser.ast.Node cur = n;
        while (cur != null) {
            if (cur instanceof TypeDeclaration<?> td) {
                TypeInfo t = byDecl.get(td);
                if (t != null) return t;
            }
            cur = cur.getParentNode().orElse(null);
        }
        return null;
    }

    /**
     * Resolve a type name as written in {@code ctx}'s source: nested types, the same file,
     * single-type imports, the same package, wildcard imports, then a project-wide unique match.
     * Returns null for library types (including ones that share a simple name with a project type).
     */
    public TypeInfo resolve(String name, TypeInfo ctx) {
        if (name == null || name.isEmpty()) return null;
        int lt = name.indexOf('<');
        if (lt >= 0) name = name.substring(0, lt);
        name = name.replace("[]", "").trim();
        if (ctx == null) return uniqueBySimple(name, null);
        Map<String, Optional<TypeInfo>> cache = resolveCache.computeIfAbsent(ctx, k -> new HashMap<>());
        Optional<TypeInfo> cached = cache.get(name);
        if (cached != null) return cached.orElse(null);
        TypeInfo r = doResolve(name, ctx);
        cache.put(name, Optional.ofNullable(r));
        return r;
    }

    private TypeInfo doResolve(String name, TypeInfo ctx) {
        if (name.contains(".")) {
            TypeInfo exact = qualified(name, ctx);
            if (exact != null) return exact;
            int dot = name.indexOf('.');
            TypeInfo head = doResolve(name.substring(0, dot), ctx);
            if (head != null) {
                TypeInfo nested = qualified(head.qualifiedName + name.substring(dot), ctx);
                if (nested != null) return nested;
            }
            // a fully-qualified library type, or unknown
            return null;
        }
        if (JAVA_LANG.contains(name)) return null;
        // 1. nested types of ctx and its outers (and their supertypes)
        for (TypeInfo t = ctx; t != null; t = t.outer) {
            TypeInfo nested = qualified(t.qualifiedName + "." + name, ctx);
            if (nested != null) return nested;
            if (t.simpleName.equals(name)) return t;
            for (TypeInfo sup : t.supertypes) {
                TypeInfo inh = qualified(sup.qualifiedName + "." + name, ctx);
                if (inh != null) return inh;
            }
        }
        // 2. top-level types in the same file
        for (TypeDeclaration<?> td : ctx.cu.getTypes()) {
            if (td.getNameAsString().equals(name)) {
                TypeInfo t = byDecl.get(td);
                if (t != null) return t;
            }
        }
        // 3. single-type imports (a library import wins over a same-named project type)
        for (ImportDeclaration imp : ctx.cu.getImports()) {
            if (imp.isAsterisk() || imp.isStatic()) continue;
            String in = imp.getNameAsString();
            if (in.endsWith("." + name)) return qualified(in, ctx);
        }
        // 4. same package
        TypeInfo samePkg = qualified(ctx.packageName.isEmpty() ? name : ctx.packageName + "." + name, ctx);
        if (samePkg != null) return samePkg;
        // 5. wildcard imports
        boolean libraryWildcards = false;
        for (ImportDeclaration imp : ctx.cu.getImports()) {
            if (!imp.isAsterisk() || imp.isStatic()) continue;
            TypeInfo t = qualified(imp.getNameAsString() + "." + name, ctx);
            if (t != null) return t;
            if (!hasPackage(imp.getNameAsString())) libraryWildcards = true;
        }
        // 6. project-wide unique simple name (handles code with incomplete imports)
        TypeInfo unique = uniqueBySimple(name, ctx);
        if (unique != null && libraryWildcards && isCommonLibraryName(name)) return null;
        return unique;
    }

    private boolean hasPackage(String pkg) {
        String prefix = pkg + ".";
        for (String q : byQualifiedName.keySet()) if (q.startsWith(prefix)) return true;
        return false;
    }

    private static boolean isCommonLibraryName(String name) {
        return Set.of("List", "Map", "Set", "Optional", "Collection", "Date", "Page", "Pageable", "Sort",
                "ResponseEntity", "HttpStatus", "Model", "Logger", "Stream").contains(name);
    }

    private TypeInfo uniqueBySimple(String name, TypeInfo ctx) {
        List<TypeInfo> list = bySimpleName.get(name);
        if (list == null || list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);
        if (ctx != null && ctx.module != null) {
            List<TypeInfo> sameModule = list.stream().filter(t -> ctx.module.equals(t.module)).toList();
            if (sameModule.size() == 1) return sameModule.get(0);
            if (!sameModule.isEmpty()) list = sameModule;
        }
        // prefer top-level, hand-written types
        return list.stream()
                .sorted(Comparator.comparing((TypeInfo t) -> t.generated)
                        .thenComparing(t -> t.outer != null)
                        .thenComparing(t -> t.qualifiedName))
                .findFirst().orElse(null);
    }

    /** All concrete project types implementing/extending {@code t}, transitively. */
    public List<TypeInfo> implementations(TypeInfo t) {
        List<TypeInfo> out = new ArrayList<>();
        Deque<TypeInfo> work = new ArrayDeque<>(t.subtypes);
        Set<TypeInfo> seen = new LinkedHashSet<>();
        while (!work.isEmpty()) {
            TypeInfo s = work.pop();
            if (!seen.add(s)) continue;
            if (s.isConcrete()) out.add(s);
            work.addAll(s.subtypes);
        }
        out.sort(Comparator.comparing(x -> x.qualifiedName));
        return out;
    }

    /** {@code t} and all its project supertypes, nearest first. */
    public List<TypeInfo> hierarchy(TypeInfo t) {
        List<TypeInfo> out = new ArrayList<>();
        Deque<TypeInfo> work = new ArrayDeque<>();
        work.add(t);
        Set<TypeInfo> seen = new LinkedHashSet<>();
        while (!work.isEmpty()) {
            TypeInfo s = work.poll();
            if (!seen.add(s)) continue;
            out.add(s);
            work.addAll(s.supertypes);
        }
        return out;
    }

    public boolean isSubtypeOf(TypeInfo t, String simpleOrQualified) {
        for (TypeInfo s : hierarchy(t)) {
            if (s.simpleName.equals(simpleOrQualified) || s.qualifiedName.equals(simpleOrQualified)) return true;
            for (ClassOrInterfaceType ref : s.superRefs) {
                if (ref.getNameAsString().equals(simpleOrQualified)) return true;
            }
        }
        return false;
    }

    /** All methods named {@code name} visible on {@code t} (declared or inherited from project types). */
    public List<MethodRef> methodsNamed(TypeInfo t, String name) {
        List<MethodRef> out = new ArrayList<>();
        for (TypeInfo s : hierarchy(t)) {
            for (MethodDeclaration m : s.methodsByName.getOrDefault(name, List.of())) out.add(new MethodRef(s, m, false));
        }
        return out;
    }

    /**
     * Pick the method a call resolves to: by name, then arity (varargs aware), then by matching
     * argument type names. {@code argTypes} may contain nulls for unknown argument types.
     */
    public MethodRef findMethod(TypeInfo t, String name, List<String> argTypes) {
        List<MethodRef> all = methodsNamed(t, name);
        if (all.isEmpty()) return null;
        int argc = argTypes.size();
        List<MethodRef> arity = new ArrayList<>();
        for (MethodRef r : all) {
            int n = r.method().getParameters().size();
            boolean varargs = n > 0 && r.method().getParameter(n - 1).isVarArgs();
            if (n == argc || varargs && argc >= n - 1) arity.add(r);
        }
        if (arity.isEmpty()) return null; // e.g. an inherited library method with the same name
        if (arity.size() == 1) return arity.get(0);
        // Prefer the most-derived declaration with matching parameter types.
        MethodRef best = null;
        int bestScore = -1;
        boolean tie = false;
        Set<String> seenSignatures = new LinkedHashSet<>();
        for (MethodRef r : arity) {
            String sig = signature(r.method());
            if (!seenSignatures.add(sig)) continue; // overridden in a subtype: keep the nearest
            int score = 0;
            for (int i = 0; i < argc && i < r.method().getParameters().size(); i++) {
                String at = argTypes.get(i);
                if (at == null) continue;
                String pt = rawName(r.method().getParameter(i).getType());
                if (sameType(at, pt)) score += 2;
                else if ("null".equals(at)) score += 0;
                else score -= 1;
            }
            if (score > bestScore) { best = r; bestScore = score; tie = false; }
            else if (score == bestScore) tie = true;
        }
        return best == null ? null : new MethodRef(best.owner(), best.method(), tie);
    }

    private static boolean sameType(String a, String b) {
        if (a.equals(b)) return true;
        Map<String, String> box = Map.of("int", "Integer", "long", "Long", "double", "Double", "float", "Float",
                "boolean", "Boolean", "char", "Character", "short", "Short", "byte", "Byte");
        return b.equals(box.get(a)) || a.equals(box.get(b));
    }

    public static String signature(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder(m.getNameAsString()).append('(');
        for (int i = 0; i < m.getParameters().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(rawName(m.getParameter(i).getType()));
        }
        return sb.append(')').toString();
    }

    /** Field declared on {@code t} or a project supertype. */
    public Optional<FieldMatch> findField(TypeInfo t, String name) {
        for (TypeInfo s : hierarchy(t)) {
            for (FieldDeclaration f : s.decl.getFields()) {
                for (VariableDeclarator v : f.getVariables()) {
                    if (v.getNameAsString().equals(name)) return Optional.of(new FieldMatch(s, f, v));
                }
            }
            if (s.decl instanceof RecordDeclaration r) {
                for (Parameter p : r.getParameters()) {
                    if (p.getNameAsString().equals(name)) return Optional.of(new FieldMatch(s, null, null, p));
                }
            }
            if (s.decl instanceof EnumDeclaration e) {
                if (e.getEntries().stream().anyMatch(en -> en.getNameAsString().equals(name))) {
                    return Optional.of(new FieldMatch(s, null, null, null));
                }
            }
        }
        return Optional.empty();
    }

    public record FieldMatch(TypeInfo owner, FieldDeclaration field, VariableDeclarator var, Parameter recordComponent) {
        FieldMatch(TypeInfo owner, FieldDeclaration field, VariableDeclarator var) { this(owner, field, var, null); }

        public Type type() {
            if (var != null) return var.getType();
            if (recordComponent != null) return recordComponent.getType();
            return null;
        }

        public Optional<Expression> initializer() {
            return var == null ? Optional.empty() : var.getInitializer();
        }

        public boolean isConstant() {
            if (var == null) return false;
            return field.isFinal() && field.isStatic() || owner.isInterface;
        }
    }

    public TypeInfo entityType(String nameOrSimple) {
        if (nameOrSimple == null) return null;
        String simple = nameOrSimple.contains(".") ? nameOrSimple.substring(nameOrSimple.lastIndexOf('.') + 1) : nameOrSimple;
        TypeInfo t = entities.get(simple);
        if (t == null) t = entities.get(nameOrSimple);
        return t;
    }

    /** Physical table for an entity (or JPQL entity) name; a non-entity token is taken as a table name. */
    public String tableFor(String token) {
        if (token == null || token.isBlank()) return "?";
        TypeInfo e = entityType(token);
        return e != null ? e.table : normalizeTable(token);

    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    /** Raw simple name of a type: {@code java.util.List<Foo>} -> {@code List}, {@code Foo[]} -> {@code Foo[]}. */
    public static String rawName(Type t) {
        if (t.isClassOrInterfaceType()) return t.asClassOrInterfaceType().getNameAsString();
        if (t.isArrayType()) return rawName(t.asArrayType().getComponentType()) + "[]";
        return t.asString();
    }

    public static String normalizeTable(String t) {
        String s = t.trim().replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        return s.toLowerCase(Locale.ROOT);
    }

    public static String snake(String camel) {
        if (camel == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                boolean prevLower = i > 0 && (Character.isLowerCase(camel.charAt(i - 1)) || Character.isDigit(camel.charAt(i - 1)));
                boolean nextLower = i + 1 < camel.length() && Character.isLowerCase(camel.charAt(i + 1));
                if (i > 0 && (prevLower || nextLower && Character.isUpperCase(camel.charAt(i - 1)))) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1)) && Character.isUpperCase(s.charAt(0))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}

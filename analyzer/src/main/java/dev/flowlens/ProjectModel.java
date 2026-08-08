package dev.flowlens;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An index of every class/interface in the analyzed source tree, classified by
 * architectural layer, with entity->table mappings and repository metadata.
 * Built once, then queried during the flow walk.
 */
public class ProjectModel {

    public static final String CONTROLLER = "controller";
    public static final String SERVICE = "service";
    public static final String REPOSITORY = "repository";
    public static final String ENTITY = "entity";
    public static final String OTHER = "other";

    private static final Set<String> REPO_BASES = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
            "Repository", "ReactiveCrudRepository", "MongoRepository", "R2dbcRepository");

    public static class TypeInfo {
        public ClassOrInterfaceDeclaration decl;
        public String qualifiedName;
        public String simpleName;
        public String layer = OTHER;
        public boolean isRepository;
        public String repoEntitySimpleName;   // entity managed by a repository
        public final Map<String, List<MethodDeclaration>> methodsByName = new HashMap<>();
    }

    public final Map<String, TypeInfo> bySimpleName = new HashMap<>();
    public final Map<String, TypeInfo> byQualifiedName = new HashMap<>();
    /** entity simple name -> physical table name */
    public final Map<String, String> entityTable = new HashMap<>();

    public void index(List<CompilationUnit> units) {
        // First pass: register types and layers.
        for (CompilationUnit cu : units) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                TypeInfo ti = new TypeInfo();
                ti.decl = decl;
                ti.simpleName = decl.getNameAsString();
                ti.qualifiedName = pkg.isEmpty() ? ti.simpleName : pkg + "." + ti.simpleName;

                for (MethodDeclaration m : decl.getMethods()) {
                    ti.methodsByName.computeIfAbsent(m.getNameAsString(), k -> new ArrayList<>()).add(m);
                }

                classify(decl, ti);

                bySimpleName.put(ti.simpleName, ti);
                byQualifiedName.put(ti.qualifiedName, ti);
                if (ENTITY.equals(ti.layer)) {
                    entityTable.put(ti.simpleName, tableName(decl, ti.simpleName));
                }
            }
        }
    }

    private void classify(ClassOrInterfaceDeclaration decl, TypeInfo ti) {
        if (hasAnno(decl, "RestController") || hasAnno(decl, "Controller")) {
            ti.layer = CONTROLLER;
        } else if (hasAnno(decl, "Entity")) {
            ti.layer = ENTITY;
        }

        // Repository detection: interface extending a Spring Data base, or @Repository.
        for (ClassOrInterfaceType ext : decl.getExtendedTypes()) {
            if (REPO_BASES.contains(ext.getNameAsString())) {
                ti.isRepository = true;
                ti.layer = REPOSITORY;
                ext.getTypeArguments().ifPresent(args -> {
                    if (!args.isEmpty()) {
                        ti.repoEntitySimpleName = args.get(0).asString();
                    }
                });
            }
        }
        if (hasAnno(decl, "Repository")) {
            ti.isRepository = true;
            ti.layer = REPOSITORY;
        }

        if (OTHER.equals(ti.layer)
                && (hasAnno(decl, "Service") || hasAnno(decl, "Component"))) {
            ti.layer = SERVICE;
        }
    }

    private static boolean hasAnno(ClassOrInterfaceDeclaration decl, String name) {
        return decl.getAnnotationByName(name).isPresent();
    }

    /** Physical table name from @Table(name="..."), else the entity simple name. */
    private static String tableName(ClassOrInterfaceDeclaration decl, String simpleName) {
        return decl.getAnnotationByName("Table")
                .flatMap(a -> a.findFirst(com.github.javaparser.ast.expr.StringLiteralExpr.class))
                .map(com.github.javaparser.ast.expr.StringLiteralExpr::asString)
                .orElse(simpleName);
    }

    public TypeInfo bySimple(String simpleName) {
        return simpleName == null ? null : bySimpleName.get(simpleName);
    }
}

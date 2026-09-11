package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Deterministic source facts. A resolved method declaration is never a proven runtime dispatch. */
public final class ScanService {
    private static final String SPRING = "org.springframework.web.bind.annotation.";
    private static final Map<String, String> SPRING_HTTP = Map.of(
            SPRING + "GetMapping", "GET", SPRING + "PostMapping", "POST",
            SPRING + "PutMapping", "PUT", SPRING + "PatchMapping", "PATCH",
            SPRING + "DeleteMapping", "DELETE", SPRING + "RequestMapping", "ANY");
    private static final Set<String> ASYNC_NAMES = Set.of("submit", "execute", "runAsync", "supplyAsync",
            "thenApply", "thenApplyAsync", "thenCompose", "thenComposeAsync", "whenComplete", "whenCompleteAsync",
            "subscribe", "parallel", "parallelStream", "join", "get", "allOf", "anyOf");

    private ScanService() {}

    public static JsonNode execute(Path project, List<String> args) throws Exception {
        if (args.isEmpty() || args.equals(List.of("--help"))) return Data.object().put("schema_version", 1)
                .put("usage", "scan --repo-id ID --module-id ID --source-root PATH [--source-root PATH] [--classpath-file PATH] --out PATH")
                .put("paths", "CLI paths are relative to --project (or absolute); module config paths must first be resolved against repo.path")
                .put("guide", "docs/super-business-flow-tookit/design/scanner-contract.md");
        String repo = null, module = null;
        Path classpath = null, out = null;
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String option = args.get(i);
            if (!Set.of("--repo-id", "--module-id", "--source-root", "--classpath-file", "--out").contains(option))
                throw new IllegalArgumentException("Unknown scan option: " + option);
            if (++i >= args.size() || args.get(i).startsWith("--"))
                throw new IllegalArgumentException("Missing value for " + option);
            String value = args.get(i);
            switch (option) {
                case "--repo-id" -> { if (repo != null) throw new IllegalArgumentException("Duplicate --repo-id"); repo = value; }
                case "--module-id" -> { if (module != null) throw new IllegalArgumentException("Duplicate --module-id"); module = value; }
                case "--source-root" -> roots.add(project.resolve(value).normalize());
                case "--classpath-file" -> { if (classpath != null) throw new IllegalArgumentException("Duplicate --classpath-file"); classpath = project.resolve(value).normalize(); }
                case "--out" -> { if (out != null) throw new IllegalArgumentException("Duplicate --out"); out = project.resolve(value).normalize(); }
                default -> throw new IllegalArgumentException(option);
            }
        }
        Data.require(repo != null && module != null && !roots.isEmpty() && out != null,
                "scan requires --repo-id ID --module-id ID --source-root PATH [--source-root PATH] --out PATH");
        Path absoluteProject = project.toAbsolutePath().normalize();
        out = out.toAbsolutePath().normalize();
        Data.require(out.startsWith(absoluteProject.resolve(Data.DATA_ROOT))
                        || out.startsWith(absoluteProject.resolve(Data.RUN_ROOT)),
                "Scan output must be within docs/super-business-flow/ or .temp/run/super-business-flow/");
        ObjectNode result = (ObjectNode) scan(repo, module, roots, classpath);
        for (JsonNode ev : result.path("evidence")) {
            ObjectNode source = (ObjectNode) ev.path("source");
            Path path = Path.of(source.path("path").asText());
            if (path.startsWith(absoluteProject)) source.put("path", portable(absoluteProject.relativize(path)));
        }
        Data.write(out, result);
        return result;
    }

    public static JsonNode scan(String repoId, String moduleId, List<Path> sourceRoots, Path classpathFile) throws Exception {
        Data.id(repoId); Data.id(moduleId);
        Data.require(!sourceRoots.isEmpty(), "At least one source root is required");
        List<Path> roots = sourceRoots.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
        for (Path root : roots) Data.require(Files.isDirectory(root), "Source root does not exist: " + root);
        Scan scanner = new Scan(repoId, moduleId, roots);
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(true)); // JDK only; never leak another module's classes.
        for (Path root : roots) solver.add(new JavaParserTypeSolver(root));
        List<URL> directories = new ArrayList<>();
        List<Path> classpathEntries = new ArrayList<>();
        if (classpathFile != null) {
            Path cp = classpathFile.toAbsolutePath().normalize();
            Data.require(Files.isRegularFile(cp), "Classpath file does not exist: " + cp);
            scanner.result.put("classpath_fingerprint", Data.fingerprint(cp));
            String content = Files.readString(cp, StandardCharsets.UTF_8).strip();
            for (String line : content.split("\\R")) {
                for (String value : line.split(Pattern.quote(File.pathSeparator))) {
                    if (value.isBlank()) continue;
                    Path entry = cp.getParent().resolve(value.strip()).normalize();
                    if (!Files.exists(entry)) {
                        scanner.diagnostic("CLASSPATH_ENTRY_MISSING", "warning", "Missing dependency: " + entry, null);
                    } else if (!classpathEntries.contains(entry)) {
                        classpathEntries.add(entry);
                        if (Files.isDirectory(entry)) directories.add(entry.toUri().toURL());
                        else if (entry.toString().endsWith(".jar")) solver.add(new JarTypeSolver(entry));
                        else scanner.diagnostic("UNSUPPORTED_CLASSPATH_ENTRY", "warning", "Expected a JAR or class directory: " + entry, null);
                    }
                }
            }
        } else scanner.diagnostic("CLASSPATH_NOT_PROVIDED", "info",
                "Only module sources and JDK types are resolved. Supply this module's Maven classpath for dependency symbols.", null);

        try (URLClassLoader directoryLoader = new URLClassLoader(directories.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            if (!directories.isEmpty()) solver.add(new ClassLoaderTypeSolver(directoryLoader));
            ParserConfiguration config = new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                    .setCharacterEncoding(StandardCharsets.UTF_8).setSymbolResolver(new JavaSymbolSolver(solver));
            JavaParser parser = new JavaParser(config);
            SortedSet<Path> files = new TreeSet<>();
            for (Path root : roots) try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
            for (Path file : files) {
                // Parse and hash the same bytes. A concurrent edit must never produce
                // source facts for one version and an evidence hash for another.
                byte[] bytes = Files.readAllBytes(file);
                var parsed = parser.parse(new String(bytes, StandardCharsets.UTF_8));
                if (!parsed.isSuccessful()) {
                    scanner.diagnostic("JAVA_PARSE_ERROR", "error", file + ": " + parsed.getProblems(), null);
                    continue; // A recovered partial AST must not become confirmed facts.
                }
                if (parsed.getResult().isPresent()) {
                    CompilationUnit unit = parsed.getResult().get();
                    unit.setStorage(file, StandardCharsets.UTF_8);
                    scanner.units.add(new Unit(file, unit, Data.sha256(bytes)));
                }
            }
            scanner.collect();
            scanner.result.put("source_file_count", files.size());
            scanner.result.put("parsed_file_count", scanner.units.size());
            ObjectNode coverage = scanner.result.putObject("coverage");
            coverage.put("status", "partial");
            coverage.put("basis", "static_source_inventory");
            coverage.put("runtime_registration_verified", false);
            coverage.put("business_flow_complete", false);
            coverage.putArray("not_expanded").add("composed_or_inherited_mappings").add("dynamic_registration")
                    .add("runtime_virtual_dispatch").add("rpc_protocol_matching").add("initializer_and_constructor_flow")
                    .add("reflection_and_generated_proxy_behavior").add("local_and_anonymous_type_flow")
                    .add("complete_control_flow_and_path_feasibility");
            ArrayNode classpathData = scanner.result.putArray("classpath_entries");
            for (Path path : classpathEntries) classpathData.add(portable(path));
        }
        return scanner.result;
    }

    private record Unit(Path path, CompilationUnit ast, String fingerprint) {}
    private record MethodFact(Unit unit, TypeDeclaration<?> owner, MethodDeclaration ast, ObjectNode json) {}
    private record Route(List<String> paths, List<String> verbs, List<String> evidence, ObjectNode attributes) {}

    private static final class Scan {
        final String repo, module;
        final List<Path> roots;
        final ObjectNode result = Data.object();
        final ArrayNode classes = Data.array(), methods = Data.array(), calls = Data.array(), endpoints = Data.array();
        final ArrayNode conditions = Data.array(), evidence = Data.array(), diagnostics = Data.array();
        final List<Unit> units = new ArrayList<>();
        final List<MethodFact> methodFacts = new ArrayList<>();
        final Map<String, String> evidenceIds = new LinkedHashMap<>();
        final Map<String, String> conditionIds = new LinkedHashMap<>();
        final Set<String> symbolIds = new HashSet<>();
        final Set<String> endpointIds = new HashSet<>();
        final Map<String, ObjectNode> classFacts = new HashMap<>();

        Scan(String repo, String module, List<Path> roots) {
            this.repo = repo; this.module = module; this.roots = roots;
            result.put("schema_version", 1).put("repo_id", repo).put("module_id", module);
            result.set("classes", classes); result.set("methods", methods); result.set("calls", calls);
            result.set("endpoints", endpoints); result.set("conditions", conditions);
            result.set("evidence", evidence); result.set("diagnostics", diagnostics);
            ArrayNode sourceRoots = result.putArray("source_roots");
            roots.forEach(p -> sourceRoots.add(portable(p)));
        }

        void collect() {
            Map<String, List<Map.Entry<Unit, TypeDeclaration<?>>>> declarations = new LinkedHashMap<>();
            for (Unit unit : units) {
                for (TypeDeclaration<?> type : unit.ast.findAll(TypeDeclaration.class)) {
                    if (type.findAncestor(LocalClassDeclarationStmt.class).isPresent()
                            || type.findAncestor(LocalRecordDeclarationStmt.class).isPresent()
                            || insideAnonymousType(type)) {
                        diagnostic("LOCAL_OR_ANONYMOUS_TYPE_NOT_EXPANDED", "warning",
                                "Local or anonymous types need their own scoped analysis: " + type.getNameAsString(), ev(unit, type));
                    } else declarations.computeIfAbsent(className(type), k -> new ArrayList<>()).add(Map.entry(unit, type));
                }
                for (ObjectCreationExpr expression : unit.ast.findAll(ObjectCreationExpr.class))
                    if (expression.getAnonymousClassBody().isPresent()) diagnostic("LOCAL_OR_ANONYMOUS_TYPE_NOT_EXPANDED", "warning",
                            "Anonymous type body needs scoped analysis: " + expression.getType(), ev(unit, expression));
            }
            for (var entry : declarations.entrySet()) {
                if (entry.getValue().size() > 1) {
                    for (var declaration : entry.getValue()) diagnostic("DUPLICATE_CLASS", "error",
                            "Multiple source declarations for " + entry.getKey() + "; none was selected.",
                            ev(declaration.getKey(), declaration.getValue()));
                } else {
                    var declaration = entry.getValue().getFirst();
                    collectClass(declaration.getKey(), declaration.getValue());
                }
            }
            for (MethodFact method : methodFacts) {
                collectCalls(method);
                collectEndpoints(method);
                collectExits(method);
            }
            Map<String, List<JsonNode>> byRoute = new LinkedHashMap<>();
            for (JsonNode endpoint : endpoints) byRoute.computeIfAbsent(endpoint.path("http").toString(), k -> new ArrayList<>()).add(endpoint);
            for (List<JsonNode> candidates : byRoute.values()) if (candidates.size() > 1) {
                for (JsonNode endpoint : candidates) ((ObjectNode) endpoint).put("route_ambiguity", "requires_disambiguation");
                diagnostic("ROUTE_CANDIDATES_REQUIRE_DISAMBIGUATION", "warning",
                        "Several declared handlers share " + candidates.getFirst().path("http")
                                + "; inspect class scope, parameters, headers and runtime registration before selecting one.",
                        candidates.getFirst().path("evidence_refs").path(0).asText());
            }
        }

        void collectClass(Unit unit, TypeDeclaration<?> type) {
            String className = className(type);
            String id = stable("class", repo + "/" + module + "/" + className);
            if (!symbolIds.add(id)) {
                diagnostic("DUPLICATE_CLASS", "error", "Multiple source declarations for " + className, ev(unit, type));
                return;
            }
            ObjectNode fact = classes.addObject();
            fact.put("id", id).put("repo_id", repo).put("module_id", module).put("class_name", className);
            fact.put("kind", type.getClass().getSimpleName());
            fact.set("annotations", annotations(unit, type.getAnnotations()));
            fact.set("modifiers", modifiers(type));
            fact.set("evidence_refs", refs(ev(unit, type)));
            ArrayNode extending = fact.putArray("extends"), implementing = fact.putArray("implements");
            if (type instanceof ClassOrInterfaceDeclaration declared) {
                declared.getExtendedTypes().forEach(t -> extending.add(typeName(t)));
                declared.getImplementedTypes().forEach(t -> implementing.add(typeName(t)));
                fact.put("interface", declared.isInterface());
            }
            if (type instanceof EnumDeclaration declared) declared.getImplementedTypes().forEach(t -> implementing.add(typeName(t)));
            if (type instanceof RecordDeclaration declared) declared.getImplementedTypes().forEach(t -> implementing.add(typeName(t)));
            if (!extending.isEmpty() || !implementing.isEmpty()) diagnostic("INHERITANCE_NOT_EXPANDED", "info",
                    "Inherited or interface-declared routing requires hierarchy inspection: " + className, ev(unit, type));
            ArrayNode fields = fact.putArray("fields");
            for (FieldDeclaration field : type.getFields()) for (VariableDeclarator variable : field.getVariables()) {
                ObjectNode f = fields.addObject();
                f.put("id", stable("field", id + "/" + variable.getNameAsString()));
                f.put("name", variable.getNameAsString()).put("type", typeName(variable.getType()));
                f.put("declared_type", variable.getTypeAsString());
                f.set("annotations", annotations(unit, field.getAnnotations())); f.set("modifiers", modifiers(field));
                f.set("evidence_refs", refs(ev(unit, field)));
                variable.getInitializer().ifPresent(x -> f.put("initializer", x.toString()));
            }
            classFacts.put(className, fact);
            for (MethodDeclaration method : type.getMethods()) {
                String signature = method.getNameAsString() + "(" + String.join(",", method.getParameters().stream()
                        .map(p -> typeName(p.getType()) + (p.isVarArgs() ? "[]" : "")).toList()) + ")";
                ObjectNode m = methods.addObject();
                m.put("id", stable("method", id + "/" + signature)).put("class_id", id).put("class_name", className);
                m.put("repo_id", repo).put("module_id", module).put("method_name", method.getNameAsString());
                m.put("method_signature", signature).put("return_type", typeName(method.getType()));
                m.put("body_present", method.getBody().isPresent());
                m.set("annotations", annotations(unit, method.getAnnotations())); m.set("modifiers", modifiers(method));
                m.set("evidence_refs", refs(ev(unit, method)));
                ArrayNode params = m.putArray("parameters");
                for (Parameter p : method.getParameters()) {
                    ObjectNode parameter = params.addObject();
                    parameter.put("name", p.getNameAsString()).put("type", typeName(p.getType())).put("varargs", p.isVarArgs());
                    parameter.set("annotations", annotations(unit, p.getAnnotations()));
                }
                try { m.put("qualified_signature", method.resolve().getQualifiedSignature()).put("signature_resolution", "qualified"); }
                catch (RuntimeException | LinkageError ignored) { m.putNull("qualified_signature"); m.put("signature_resolution", "source"); }
                methodFacts.add(new MethodFact(unit, type, method, m));
            }
        }

        ArrayNode annotations(Unit unit, Iterable<AnnotationExpr> annotations) {
            ArrayNode result = Data.array();
            for (AnnotationExpr annotation : annotations) {
                ObjectNode item = result.addObject();
                item.put("name", annotation.getNameAsString());
                String name = qualifiedAnnotation(unit, annotation, item);
                if (name == null) item.putNull("full_name"); else item.put("full_name", name);
                item.put("resolved", name != null);
                item.set("evidence_refs", refs(ev(unit, annotation)));
                ObjectNode values = item.putObject("values"), raw = item.putObject("raw_values");
                Map<String, Expression> attrs = attributes(annotation);
                for (var attr : attrs.entrySet()) {
                    raw.put(attr.getKey(), attr.getValue().toString());
                    JsonNode literal = literal(attr.getValue());
                    if (literal != null) values.set(attr.getKey(), literal);
                }
            }
            return result;
        }

        String qualifiedAnnotation(Unit unit, AnnotationExpr annotation, ObjectNode metadata) {
            String sourceName = annotation.getNameAsString();
            try {
                String resolved = annotation.resolve().getQualifiedName();
                metadata.put("qualification", "symbol_solver"); return resolved;
            } catch (RuntimeException | LinkageError ignored) { /* The explicit source spelling still provides evidence. */ }
            if (sourceName.contains(".")) { metadata.put("qualification", "fully_qualified"); return sourceName; }
            Set<String> imported = new LinkedHashSet<>();
            unit.ast.getImports().stream().filter(i -> !i.isAsterisk() && !i.isStatic())
                    .filter(i -> i.getName().getIdentifier().equals(sourceName)).forEach(i -> imported.add(i.getNameAsString()));
            if (imported.size() == 1) { metadata.put("qualification", "explicit_import"); return imported.iterator().next(); }
            metadata.put("qualification", "unresolved");
            diagnostic("ANNOTATION_NAME_UNRESOLVED", "warning", "Cannot establish annotation identity: " + sourceName
                    + "; provide the module classpath or inspect imports/declaration.", ev(unit, annotation));
            return null;
        }

        void collectCalls(MethodFact method) {
            Map<String, Integer> occurrence = new HashMap<>();
            for (MethodCallExpr call : method.ast.findAll(MethodCallExpr.class)) {
                if (!belongsToMethod(call, method.ast)) continue;
                String text = call.toString();
                int ordinal = occurrence.merge(text, 1, Integer::sum);
                ObjectNode fact = calls.addObject();
                fact.put("id", stable("call", method.json.path("id").asText() + "/" + text + "/" + ordinal));
                fact.put("caller_method_id", method.json.path("id").asText()).put("expression", text);
                fact.put("method_name", call.getNameAsString());
                String receiver = receiverField(method, call);
                if (receiver == null) fact.putNull("receiver_field"); else fact.put("receiver_field", receiver);
                String receiverType = null;
                if (receiver != null) for (JsonNode field : classFacts.get(className(method.owner)).path("fields"))
                    if (field.path("name").asText().equals(receiver)) receiverType = field.path("type").asText();
                if (receiverType == null) fact.putNull("receiver_type"); else fact.put("receiver_type", receiverType);
                ArrayNode types = fact.putArray("argument_types");
                for (Expression arg : call.getArguments()) {
                    try { types.add(arg.calculateResolvedType().describe()); }
                    catch (RuntimeException | LinkageError ignored) { types.add("?"); }
                }
                String invoked = call.getNameAsString() + "(" + String.join(",", streamText(types)) + ")";
                fact.put("invoked_method", invoked);
                try {
                    ResolvedMethodDeclaration declaration = call.resolve();
                    fact.put("target_signature", declaration.getQualifiedSignature());
                    fact.put("invoked_method", declaration.getSignature());
                    fact.put("resolution", "resolved");
                    fact.put("dispatch", declaration.isStatic() ? "static_declaration" : "declaration_only");
                } catch (RuntimeException | LinkageError ex) {
                    fact.putNull("target_signature"); fact.put("resolution", "unresolved").put("dispatch", "unresolved");
                    diagnostic("CALL_TARGET_UNRESOLVED", "warning", "Cannot resolve the declaration of " + text
                            + "; inspect module classpath, generated sources or dispatch rules.", ev(method.unit, call));
                }
                fact.set("condition_refs", guardRefs(method, call));
                fact.set("evidence_refs", refs(ev(method.unit, call)));
                fact.put("execution_context", nestedLambda(call, method.ast) ? "deferred_lambda" : "method_body");
                if (nestedLambda(call, method.ast)) fact.put("invocation_timing", "unresolved");
                if (ASYNC_NAMES.contains(call.getNameAsString())) {
                    fact.put("async_role", "requires_semantic_review");
                    diagnostic("POSSIBLE_ASYNC_BOUNDARY", "info", "Method name is only a navigation hint; verify scheduling/join semantics: " + text, ev(method.unit, call));
                }
            }
            for (MethodReferenceExpr ref : method.ast.findAll(MethodReferenceExpr.class)) {
                if (belongsToMethod(ref, method.ast))
                    diagnostic("METHOD_REFERENCE_DEFERRED", "warning", "Method reference target and invocation require review: " + ref, ev(method.unit, ref));
            }
        }

        String receiverField(MethodFact method, MethodCallExpr call) {
            if (call.getScope().isEmpty()) return null;
            Expression scope = call.getScope().get();
            String name;
            if (scope instanceof FieldAccessExpr field && field.getScope().isThisExpr()) name = field.getNameAsString();
            else if (scope instanceof NameExpr expression) {
                name = expression.getNameAsString();
                try { if (!expression.resolve().isField()) return null; }
                catch (RuntimeException | LinkageError ignored) {
                    if (method.ast.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) return null;
                    // Conservative with local shadowing: never attach an RPC field's rule to a local receiver.
                    if (method.ast.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(name))) return null;
                    if (method.ast.findAll(LambdaExpr.class).stream().flatMap(l -> l.getParameters().stream())
                            .anyMatch(p -> p.getNameAsString().equals(name))) return null;
                }
            } else return null;
            ObjectNode owner = classFacts.get(className(method.owner));
            for (JsonNode field : owner.path("fields")) if (field.path("name").asText().equals(name)) return name;
            return null;
        }

        void collectEndpoints(MethodFact method) {
            List<Route> methodRoutes = routes(method.unit, method.ast.getAnnotations(), false);
            if (methodRoutes.isEmpty()) return;
            List<Route> classRoutes = routes(method.unit, method.owner.getAnnotations(), true);
            boolean hasUnresolvedClassMapping = method.owner.getAnnotations().stream().anyMatch(a -> {
                String n = qualifiedAnnotation(method.unit, a, Data.object());
                return SPRING_HTTP.containsKey(n == null ? "" : n)
                        || (n == null && SPRING_HTTP.containsKey(SPRING + a.getName().getIdentifier()));
            }) && classRoutes.isEmpty();
            if (hasUnresolvedClassMapping) return;
            if (classRoutes.isEmpty()) classRoutes = List.of(new Route(List.of(""), List.of("ANY"), List.of(), Data.object()));
            if (methodRoutes.size() > 1 || classRoutes.size() > 1) {
                diagnostic("MULTIPLE_MAPPING_ANNOTATIONS", "warning", "Multiple mapping annotations require Spring precedence review: "
                        + method.json.path("method_signature").asText(), ev(method.unit, method.ast)); return;
            }
            for (Route outer : classRoutes) for (Route inner : methodRoutes) {
                List<String> verbs = combineVerbs(outer.verbs, inner.verbs);
                for (String prefix : outer.paths) for (String suffix : inner.paths) for (String verb : verbs) {
                    if (!suffix.isEmpty() && prefix.contains("*")) {
                        diagnostic("MAPPING_PATTERN_COMBINATION_UNSUPPORTED", "warning",
                                "A class-level wildcard pattern requires the project's Spring path-combination rules: " + prefix,
                                ev(method.unit, method.owner));
                        continue;
                    }
                    String path = routePath(prefix, suffix);
                    String endpointId = stable("endpoint", method.json.path("id").asText() + "/" + verb + "/" + path);
                    if (!endpointIds.add(endpointId)) continue;
                    ObjectNode endpoint = endpoints.addObject();
                    endpoint.put("id", endpointId);
                    endpoint.put("role", "provider").put("repo_id", repo).put("module_id", module);
                    endpoint.put("class_name", method.json.path("class_name").asText());
                    endpoint.put("method_signature", method.json.path("method_signature").asText());
                    endpoint.put("method_id", method.json.path("id").asText());
                    endpoint.put("resolution", "confirmed").put("exposure", "declared_spring_mapping");
                    endpoint.put("runtime_registration", "not_evaluated");
                    endpoint.put("url_scope", "declared_mapping_without_gateway_or_context_prefix");
                    endpoint.putObject("http").put("method", verb).put("path", path);
                    ObjectNode restrictions = endpoint.putObject("mapping_attributes");
                    restrictions.set("class", outer.attributes); restrictions.set("method", inner.attributes);
                    ArrayNode refs = endpoint.putArray("evidence_refs");
                    new LinkedHashSet<>(concat(outer.evidence, inner.evidence)).forEach(refs::add);
                }
            }
        }

        List<Route> routes(Unit unit, Iterable<AnnotationExpr> annotations, boolean classLevel) {
            List<Route> result = new ArrayList<>();
            for (AnnotationExpr annotation : annotations) {
                String name = qualifiedAnnotation(unit, annotation, Data.object());
                if (name == null || !SPRING_HTTP.containsKey(name)) continue;
                if (classLevel && !name.equals(SPRING + "RequestMapping")) {
                    diagnostic("INVALID_CLASS_MAPPING_ANNOTATION", "warning", "Standard Spring composed verb mappings target methods: " + name,
                            ev(unit, annotation));
                    continue;
                }
                Map<String, Expression> attrs = attributes(annotation);
                Expression paths = attrs.getOrDefault("path", attrs.get("value"));
                if (attrs.containsKey("path") && attrs.containsKey("value")) {
                    diagnostic("MAPPING_ALIAS_CONFLICT", "warning", "Both path and value are supplied; verify alias compatibility.", ev(unit, annotation)); continue;
                }
                List<String> pathValues = paths == null ? List.of("") : literalStrings(paths);
                if (pathValues == null || pathValues.stream().anyMatch(p -> p.contains("${") || p.contains("#{"))) {
                    diagnostic("MAPPING_PATH_UNRESOLVED", "warning", "Mapping path is not a literal route; resolve constants/configuration: " + paths, ev(unit, annotation)); continue;
                }
                List<String> verbs = List.of(SPRING_HTTP.get(name));
                if (name.equals(SPRING + "RequestMapping") && attrs.containsKey("method")) {
                    verbs = requestMethods(unit, attrs.get("method"));
                    if (verbs == null) {
                        diagnostic("MAPPING_HTTP_METHOD_UNRESOLVED", "warning", "Cannot establish RequestMethod enum values.", ev(unit, annotation)); continue;
                    }
                    if (verbs.isEmpty()) verbs = List.of("ANY");
                }
                ObjectNode preserved = Data.object();
                for (var entry : attrs.entrySet()) preserved.put(entry.getKey(), entry.getValue().toString());
                result.add(new Route(pathValues.isEmpty() ? List.of("") : pathValues.stream().distinct().toList(), verbs,
                        List.of(ev(unit, annotation)), preserved));
            }
            return result;
        }

        List<String> requestMethods(Unit unit, Expression expression) {
            List<Expression> elements = expression.isArrayInitializerExpr() ? expression.asArrayInitializerExpr().getValues() : List.of(expression);
            List<String> result = new ArrayList<>();
            Set<String> allowed = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
            for (Expression element : elements) {
                String name = null;
                if (element instanceof FieldAccessExpr field) {
                    String scope = field.getScope().toString();
                    boolean exact = scope.equals(SPRING + "RequestMethod") || (scope.equals("RequestMethod") && unit.ast.getImports().stream()
                            .anyMatch(i -> !i.isAsterisk() && !i.isStatic() && i.getNameAsString().equals(SPRING + "RequestMethod")));
                    if (exact) name = field.getNameAsString();
                } else if (element instanceof NameExpr id) {
                    boolean exact = unit.ast.getImports().stream().anyMatch(i -> i.isStatic() && !i.isAsterisk()
                            && i.getNameAsString().equals(SPRING + "RequestMethod." + id.getNameAsString()));
                    if (exact) name = id.getNameAsString();
                }
                if (name == null || !allowed.contains(name)) return null;
                result.add(name);
            }
            return result.stream().distinct().toList();
        }

        void collectExits(MethodFact method) {
            ArrayNode exits = method.json.putArray("exits");
            int ordinal = 0;
            for (Node node : method.ast.findAll(Node.class)) {
                if (!(node instanceof ReturnStmt) && !(node instanceof ThrowStmt)) continue;
                if (!belongsToMethod(node, method.ast)) continue;
                ObjectNode exit = exits.addObject();
                exit.put("id", stable("exit", method.json.path("id").asText() + "/" + node + "/" + ++ordinal));
                exit.put("kind", node instanceof ReturnStmt ? "return" : "throw").put("expression", node.toString());
                exit.put("execution_context", nestedLambda(node, method.ast) ? "deferred_lambda" : "method_body");
                exit.set("condition_refs", guardRefs(method, node)); exit.set("evidence_refs", refs(ev(method.unit, node)));
            }
        }

        ArrayNode guardRefs(MethodFact method, Node node) {
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            Node child = node;
            for (Node parent = child.getParentNode().orElse(null); parent != null && parent != method.ast;
                 child = parent, parent = parent.getParentNode().orElse(null)) {
                if (parent instanceof IfStmt branch) {
                    if (child == branch.getThenStmt()) refs.add(condition(method, branch.getCondition(), "if", "true", "java_expression"));
                    else if (branch.getElseStmt().orElse(null) == child) refs.add(condition(method, branch.getCondition(), "if", "false", "java_expression"));
                } else if (parent instanceof ConditionalExpr branch) {
                    if (child == branch.getThenExpr()) refs.add(condition(method, branch.getCondition(), "ternary", "true", "java_expression"));
                    else if (child == branch.getElseExpr()) refs.add(condition(method, branch.getCondition(), "ternary", "false", "java_expression"));
                } else if (parent instanceof WhileStmt loop && child == loop.getBody()) {
                    refs.add(condition(method, loop.getCondition(), "while", "true", "loop_iteration"));
                } else if (parent instanceof ForStmt loop && (child == loop.getBody() || loop.getUpdate().contains(child))) {
                    loop.getCompare().ifPresent(c -> refs.add(condition(method, c, "for", "true", "loop_iteration")));
                } else if (parent instanceof ForEachStmt loop && child == loop.getBody()) {
                    refs.add(condition(method, loop.getIterable(), "foreach", "has_next", "iteration_region"));
                } else if (parent instanceof DoStmt loop && child == loop.getBody()) {
                    refs.add(condition(method, loop.getCondition(), "do_while", "initial_or_repeated", "iteration_region"));
                } else if (parent instanceof CatchClause clause && child == clause.getBody()) {
                    refs.add(condition(method, clause.getParameter(), "catch", "exception_matches", "exception_region"));
                } else if (parent instanceof SwitchEntry entry) {
                    refs.add(condition(method, entry, "switch", "case_or_fallthrough", "syntactic_region"));
                } else if (parent instanceof BinaryExpr binary && child == binary.getRight()) {
                    if (binary.getOperator() == BinaryExpr.Operator.AND) refs.add(condition(method, binary.getLeft(), "short_circuit", "true", "java_expression"));
                    if (binary.getOperator() == BinaryExpr.Operator.OR) refs.add(condition(method, binary.getLeft(), "short_circuit", "false", "java_expression"));
                }
                if (parent instanceof BlockStmt block && child instanceof Statement statement) {
                    for (Statement preceding : block.getStatements()) {
                        if (preceding == statement) break;
                        if (preceding instanceof IfStmt branch) {
                            boolean thenExits = abrupt(branch.getThenStmt()), elseExits = branch.getElseStmt().map(ScanService::abrupt).orElse(false);
                            if (thenExits && !elseExits) refs.add(condition(method, branch.getCondition(), "preceding_exit", "false", "java_expression"));
                            if (!thenExits && elseExits) refs.add(condition(method, branch.getCondition(), "preceding_exit", "true", "java_expression"));
                        }
                    }
                }
            }
            ArrayNode result = Data.array(); refs.forEach(result::add); return result;
        }

        String condition(MethodFact method, Node source, String kind, String outcome, String precision) {
            String sourceIdentity = ev(method.unit, source);
            String key = method.json.path("id").asText() + "/" + sourceIdentity + "/" + kind + "/" + outcome;
            if (conditionIds.containsKey(key)) return conditionIds.get(key);
            // Condition IDs use a source occurrence ordinal, not line numbers or a file fingerprint.
            long ordinal = conditionIds.keySet().stream().filter(k -> k.startsWith(method.json.path("id").asText() + "/")).count();
            String id = stable("condition", method.json.path("id").asText() + "/" + kind + "/" + source + "/" + outcome + "/" + ordinal);
            ObjectNode condition = conditions.addObject();
            condition.put("id", id).put("method_id", method.json.path("id").asText()).put("kind", kind);
            condition.put("expression", source.toString()).put("outcome", outcome).put("precision", precision);
            condition.put("feasibility", "not_evaluated"); condition.set("evidence_refs", refs(sourceIdentity));
            conditionIds.put(key, id); return id;
        }

        String ev(Unit unit, Node node) {
            String range = node.getRange().map(Object::toString).orElse("no-range");
            String key = portable(unit.path) + "/" + unit.fingerprint + "/" + range;
            if (evidenceIds.containsKey(key)) return evidenceIds.get(key);
            String id = stable("evidence", repo + "/" + module + "/" + key);
            ObjectNode item = evidence.addObject();
            item.put("id", id).put("kind", "source").put("repo_id", repo).put("module_id", module);
            ObjectNode source = item.putObject("source");
            source.put("path", portable(unit.path)).put("sha256", unit.fingerprint);
            node.getRange().ifPresent(r -> { source.put("start_line", r.begin.line); source.put("end_line", r.end.line); });
            evidenceIds.put(key, id); return id;
        }

        void diagnostic(String code, String severity, String message, String evidenceRef) {
            String id = stable("diagnostic", code + "/" + message + "/" + evidenceRef);
            for (JsonNode existing : diagnostics) if (existing.path("id").asText().equals(id)) return;
            ObjectNode item = diagnostics.addObject();
            item.put("id", id).put("code", code).put("severity", severity).put("message", message);
            item.set("evidence_refs", evidenceRef == null ? Data.array() : refs(evidenceRef));
        }
    }

    private static boolean abrupt(Statement statement) {
        if (statement.isReturnStmt() || statement.isThrowStmt() || statement.isBreakStmt() || statement.isContinueStmt()) return true;
        if (statement instanceof BlockStmt block) return !block.getStatements().isEmpty() && abrupt(block.getStatement(block.getStatements().size() - 1));
        if (statement instanceof IfStmt branch) return abrupt(branch.getThenStmt()) && branch.getElseStmt().map(ScanService::abrupt).orElse(false);
        return false;
    }

    private static boolean nestedLambda(Node node, MethodDeclaration method) {
        for (Node parent = node.getParentNode().orElse(null); parent != null && parent != method; parent = parent.getParentNode().orElse(null))
            if (parent instanceof LambdaExpr) return true;
        return false;
    }

    private static boolean belongsToMethod(Node node, MethodDeclaration method) {
        for (Node parent = node.getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null)) {
            if (parent == method) return true;
            if (parent instanceof MethodDeclaration || parent instanceof ConstructorDeclaration
                    || parent instanceof TypeDeclaration<?> || parent instanceof InitializerDeclaration) return false;
            if (parent instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
                // Constructor argument expressions still execute in the enclosing method.
                if (creation.getArguments().stream().noneMatch(argument -> argument == node || argument.isAncestorOf(node))) return false;
            }
        }
        return false;
    }

    private static boolean insideAnonymousType(Node node) {
        return node.findAncestor(ObjectCreationExpr.class).filter(x -> x.getAnonymousClassBody().isPresent()).isPresent();
    }

    private static ArrayNode modifiers(Node node) {
        ArrayNode values = Data.array();
        if (node instanceof com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?> declared)
            declared.getModifiers().forEach(m -> values.add(m.getKeyword().asString()));
        return values;
    }

    private static String className(TypeDeclaration<?> type) {
        List<String> names = new ArrayList<>();
        for (Node node = type; node != null; node = node.getParentNode().orElse(null))
            if (node instanceof TypeDeclaration<?> declared) names.add(declared.getNameAsString());
        Collections.reverse(names);
        String pkg = type.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration).map(p -> p.getNameAsString() + ".").orElse("");
        return pkg + String.join(".", names);
    }

    private static String typeName(com.github.javaparser.ast.type.Type type) {
        try { return type.resolve().describe(); }
        catch (RuntimeException | LinkageError ignored) { return type.asString(); }
    }

    private static Map<String, Expression> attributes(AnnotationExpr annotation) {
        Map<String, Expression> attrs = new LinkedHashMap<>();
        if (annotation instanceof SingleMemberAnnotationExpr single) attrs.put("value", single.getMemberValue());
        else if (annotation instanceof NormalAnnotationExpr normal) normal.getPairs().forEach(p -> attrs.put(p.getNameAsString(), p.getValue()));
        return attrs;
    }

    private static JsonNode literal(Expression expression) {
        if (expression instanceof StringLiteralExpr string) return Data.JSON.getNodeFactory().textNode(string.asString());
        if (expression instanceof BooleanLiteralExpr bool) return Data.JSON.getNodeFactory().booleanNode(bool.getValue());
        if (expression instanceof CharLiteralExpr ch) return Data.JSON.getNodeFactory().textNode(String.valueOf(ch.asChar()));
        if (expression instanceof IntegerLiteralExpr n) try { return Data.JSON.getNodeFactory().numberNode(n.asNumber().intValue()); } catch (NumberFormatException ignored) { return null; }
        if (expression instanceof LongLiteralExpr n) try { return Data.JSON.getNodeFactory().numberNode(n.asNumber().longValue()); } catch (NumberFormatException ignored) { return null; }
        if (expression instanceof DoubleLiteralExpr n) try { return Data.JSON.getNodeFactory().numberNode(n.asDouble()); } catch (NumberFormatException ignored) { return null; }
        if (expression instanceof EnclosedExpr enclosed) return literal(enclosed.getInner());
        if (expression instanceof ArrayInitializerExpr array) {
            ArrayNode result = Data.array();
            for (Expression value : array.getValues()) { JsonNode parsed = literal(value); if (parsed == null) return null; result.add(parsed); }
            return result;
        }
        if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            JsonNode left = literal(binary.getLeft()), right = literal(binary.getRight());
            if (left != null && right != null && left.isTextual() && right.isTextual())
                return Data.JSON.getNodeFactory().textNode(left.asText() + right.asText());
        }
        return null;
    }

    private static List<String> literalStrings(Expression expression) {
        JsonNode value = literal(expression);
        if (value == null) return null;
        if (value.isTextual()) return List.of(value.asText());
        if (value.isArray()) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : value) { if (!item.isTextual()) return null; result.add(item.asText()); }
            return result;
        }
        return null;
    }

    private static List<String> combineVerbs(List<String> a, List<String> b) {
        if (a.contains("ANY")) return b;
        if (b.contains("ANY")) return a;
        // RequestMethodsRequestCondition.combine uses a union, not intersection.
        return concat(a, b).stream().distinct().toList();
    }

    private static String routePath(String prefix, String suffix) {
        if (prefix.isEmpty()) return suffix.startsWith("/") ? suffix : "/" + suffix;
        if (suffix.isEmpty()) return prefix.startsWith("/") ? prefix : "/" + prefix;
        String joined = prefix + (prefix.endsWith("/") || suffix.startsWith("/") ? "" : "/") + suffix;
        if (prefix.endsWith("/") && suffix.startsWith("/")) joined = prefix + suffix.substring(1);
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private static List<String> concat(List<String> a, List<String> b) { List<String> result = new ArrayList<>(a); result.addAll(b); return result; }
    private static List<String> streamText(ArrayNode values) { List<String> result = new ArrayList<>(); values.forEach(v -> result.add(v.asText())); return result; }
    private static ArrayNode refs(String id) { return Data.array().add(id); }
    private static String stable(String prefix, String value) { return prefix + "-" + Data.sha256(value.getBytes(StandardCharsets.UTF_8)).substring(0, 24); }
    private static String portable(Path path) { return path.toString().replace('\\', '/'); }
}

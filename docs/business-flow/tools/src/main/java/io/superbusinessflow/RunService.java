package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** A local, evidence-preserving Agent work queue. It never executes or invents business analysis. */
public final class RunService {
    private static final String BASE = "docs/business-flow/";
    private static final String RUNS = ".temp/run/super-business-flow/";
    private static final List<String> PHASES = List.of("inventory", "frameworks", "mappings", "scenarios", "knowledge", "review");
    private static final Set<String> SCHEDULING = Set.of("--max-parallel-tasks", "--max-retries", "--task-timeout-seconds", "--phase-limit");
    private static final Pattern CONFIG_FILE = Pattern.compile(".*\\.(java|kt|xml|yaml|yml|properties|json|conf|config|ini|proto|sql|gradle|kts|toml)$", Pattern.CASE_INSENSITIVE);
    private RunService() {}

    public static JsonNode execute(Path project, List<String> args) throws Exception {
        project = project.toAbsolutePath().normalize();
        if (args.isEmpty() || args.equals(List.of("--help"))) return help();
        if (args.getFirst().equals("task")) return taskCommand(project, args.subList(1, args.size()));
        Selection selection = parseSelection(args);
        return selection.resume == null ? create(project, selection) : resume(project, selection);
    }

    private record Selection(ObjectNode input, String resume, Map<String, String> overrides, Map<String, Integer> phaseLimits) {}

    private static Selection parseSelection(List<String> args) {
        String className = null, resume = null; boolean all = false;
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        Map<String, String> overrides = new LinkedHashMap<>(); Map<String, Integer> phases = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String key = args.get(i);
            if (key.equals("--all")) { Data.require(!all, "Repeated --all"); all = true; continue; }
            if (key.equals("--url")) {
                int before = i;
                while (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) urls.add(normalizeUrl(args.get(++i)));
                Data.require(i > before, "--url requires at least one provider URL"); continue;
            }
            Data.require(key.equals("--class") || key.equals("--resume") || SCHEDULING.contains(key)
                || key.equals("--max-task-tokens") || key.equals("--max-memory-items"), "Unknown run option: " + key);
            Data.require(i + 1 < args.size() && !args.get(i + 1).startsWith("--"), "Missing value for " + key);
            String value = args.get(++i);
            if (key.equals("--class")) {
                Data.require(className == null, "Repeated --class");
                Data.require(value.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*"), "Invalid class name: " + value);
                className = value;
            } else if (key.equals("--resume")) { Data.require(resume == null, "Repeated --resume"); resume = Data.id(value); }
            else if (key.equals("--phase-limit")) {
                String[] split = value.split("=", -1);
                Data.require(split.length == 2 && PHASES.contains(split[0]), "Use --phase-limit PHASE=N with a known phase");
                Data.require(phases.putIfAbsent(split[0], integer(split[1], 1, key)) == null, "Repeated phase limit: " + split[0]);
            } else { integer(value, key.equals("--max-retries") ? 0 : 1, key); Data.require(overrides.putIfAbsent(key, value) == null, "Repeated " + key); }
        }
        Data.require(!(all && (className != null || !urls.isEmpty())), "--all cannot be combined with --class or --url");
        Data.require(resume == null || (!all && className == null && urls.isEmpty()), "--resume cannot be combined with new entry selectors");
        Data.require(resume == null || overrides.keySet().stream().allMatch(SCHEDULING::contains), "Resume accepts scheduling overrides only; start a new run to change semantic/context settings");
        Data.require(resume != null || all || className != null || !urls.isEmpty(), "Choose --class, --url, --all or --resume; no implicit full scan");
        ObjectNode input = Data.object().put("mode", all ? "all" : className != null ? "class" : "urls");
        if (className != null) input.put("class_name", className);
        ArrayNode list = input.putArray("urls"); urls.forEach(list::add);
        input.put("class_and_urls_are_intersection", className != null && !urls.isEmpty());
        return new Selection(input, resume, overrides, phases);
    }

    private static String normalizeUrl(String value) {
        try {
            URI uri = URI.create(value);
            Data.require(!value.isBlank() && uri.getFragment() == null, "Provider URL must not contain a fragment");
            Data.require(uri.isAbsolute() ? Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT)) && uri.getHost() != null
                : value.startsWith("/") && uri.getAuthority() == null, "Provide an absolute HTTP URL or a provider path beginning with /");
            String path = uri.getRawPath();
            Data.require(path != null && path.startsWith("/"), "Provider URL needs a path");
            return path; // query values do not select a static business branch; preserve trailing slash and escapes.
        } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid provider URL '" + value + "': " + ex.getMessage()); }
    }

    private static int integer(String value, int minimum, String key) {
        try { int n = Integer.parseInt(value); Data.require(n >= minimum, key + " must be >= " + minimum); return n; }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be an integer"); }
    }

    private static ObjectNode defaults() {
        ObjectNode root = Data.object().put("schema_version", 1);
        root.putObject("execution").put("max_parallel_tasks", 3).put("max_retries", 2).put("task_timeout_seconds", 900).putObject("phase_limits");
        root.putObject("context").put("max_memory_items", 10).put("max_task_tokens", 24000);
        root.putObject("memory").put("enabled", true).put("auto_record", true).put("retrieve_on_demand", true);
        return root;
    }

    private static ObjectNode settings(Path project, Selection selection) throws IOException {
        ObjectNode settings = defaults(); Path path = project.resolve(BASE + "project/settings.yaml");
        if (Files.exists(path)) { JsonNode configured = Data.read(path); version(configured); merge(settings, configured); }
        applyOverrides(settings, selection); validateSettings(settings); return settings;
    }

    private static void merge(ObjectNode target, JsonNode patch) {
        Data.require(patch.isObject(), "Settings must be an object");
        patch.fields().forEachRemaining(field -> {
            if (field.getValue().isObject() && target.path(field.getKey()).isObject()) merge((ObjectNode) target.get(field.getKey()), field.getValue());
            else target.set(field.getKey(), field.getValue().deepCopy());
        });
    }

    private static void applyOverrides(ObjectNode settings, Selection selection) {
        selection.overrides.forEach((key, value) -> {
            String group = SCHEDULING.contains(key) ? "execution" : "context";
            ((ObjectNode) settings.get(group)).put(key.substring(2).replace('-', '_'), Integer.parseInt(value));
        });
        ObjectNode phases = (ObjectNode) settings.path("execution").path("phase_limits");
        selection.phaseLimits.forEach(phases::put);
    }

    private static void validateSettings(JsonNode settings) {
        for (String key : List.of("max_parallel_tasks", "max_retries", "task_timeout_seconds"))
            positiveNode(settings.path("execution").path(key), key.equals("max_retries") ? 0 : 1, "execution." + key);
        JsonNode phases = settings.path("execution").path("phase_limits"); Data.require(phases.isObject(), "execution.phase_limits must be an object");
        phases.fields().forEachRemaining(f -> { Data.require(PHASES.contains(f.getKey()), "Unknown phase limit: " + f.getKey()); positiveNode(f.getValue(), 1, f.getKey()); });
        for (String key : List.of("max_memory_items", "max_task_tokens")) positiveNode(settings.path("context").path(key), 1, "context." + key);
        Data.require(settings.path("memory").path("enabled").isBoolean(), "memory.enabled must be boolean");
    }

    private static void positiveNode(JsonNode node, int minimum, String key) { Data.require(node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= minimum, key + " must be an integer >= " + minimum); }

    private static JsonNode repos(Path project) throws IOException {
        Path path = project.resolve(BASE + "project/repos.yaml");
        Data.require(Files.isRegularFile(path), "Configure docs/business-flow/project/repos.yaml first; the tool will not scan itself");
        JsonNode root = Data.read(path); version(root);
        Data.require(root.path("repos").isArray() && !root.path("repos").isEmpty(), "repos.yaml requires at least one registered repository");
        Set<String> ids = new HashSet<>();
        for (JsonNode repo : root.get("repos")) {
            String id = Data.id(requiredText(repo, "id")); Data.require(ids.add(id), "Duplicate repository id: " + id);
            Path repoPath = Data.resolve(project, requiredText(repo, "path")); Data.require(Files.isDirectory(repoPath), "Repository directory is missing: " + repoPath);
            Data.require(repo.path("modules").isArray() && !repo.path("modules").isEmpty(), "Repository needs explicit modules: " + id);
            Set<String> modules = new HashSet<>();
            for (JsonNode module : repo.get("modules")) {
                Data.require(modules.add(Data.id(requiredText(module, "id"))), "Duplicate module id in " + id);
                Data.require(module.path("source_roots").isArray() && !module.path("source_roots").isEmpty(), "Module source_roots must be explicit: " + module.path("id").asText());
                for (JsonNode source : module.get("source_roots")) {
                    Data.require(source.isTextual(), "source_roots must contain paths");
                    Data.require(Files.isDirectory(Data.resolve(repoPath, source.asText())), "Source root is missing: " + source.asText());
                }
            }
        }
        return root;
    }

    private static JsonNode create(Path project, Selection selection) throws Exception {
        ObjectNode settings = settings(project, selection); JsonNode repos = repos(project);
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path ignore = project.resolve(RUNS + ".gitignore");
        if (!Files.exists(ignore)) Data.writeText(ignore, "*\n!.gitignore\n");
        Path dir = project.resolve(RUNS + runId); Files.createDirectories(dir);
        try (AutoCloseable ignored = Data.lock(dir.resolve("run.lock"))) {
            ObjectNode snapshot = snapshot(project, runId, 1, selection.input, settings, repos, Data.array());
            ObjectNode state = Data.object().put("schema_version", 1).put("run_id", runId).put("revision", 0).put("created_at", Data.now()).put("status", "prepared");
            state.set("snapshot", snapshot); state.set("tasks", bootstrap(snapshot));
            state.set("frontier", Data.array()); state.set("questions", Data.array()); state.set("decisions", Data.array()); state.set("events", Data.array());
            event(state, "created", "Agent stages prepared; no business analysis has executed");
            save(dir, state); return summary(dir, state).put("message", "Drive the queued tasks with the main Skill; selectors require evidence-based entry resolution");
        }
    }

    private static JsonNode resume(Path project, Selection selection) throws Exception {
        Path dir = runDirectory(project, selection.resume);
        try (AutoCloseable ignored = Data.lock(dir.resolve("run.lock"))) {
            ObjectNode state = load(dir); ObjectNode old = (ObjectNode) state.get("snapshot");
            ObjectNode frozen = old.path("effective_settings").deepCopy(); applyOverrides(frozen, selection); validateSettings(frozen);
            JsonNode currentRepos = repos(project);
            ObjectNode current = snapshot(project, selection.resume, old.path("generation").asInt() + 1, (ObjectNode) old.get("input"), frozen, currentRepos, (ArrayNode) old.path("used_memory"));
            ArrayNode changes = changes(old.path("observed_inputs"), current.path("observed_inputs"));
            if (!changes.isEmpty()) {
                Data.write(dir.resolve("history/tasks-" + state.path("revision").asInt() + ".json"), envelope("tasks", state.get("tasks")));
                state.set("snapshot", current); state.set("tasks", bootstrap(current)); state.set("frontier", Data.array());
                for (JsonNode q : state.withArray("questions")) if (q.path("status").asText().equals("open")) ((ObjectNode) q).put("status", "superseded");
                event(state, "invalidated", "Observed input changes require fresh inventory and revalidation").set("changes", changes);
            } else {
                // Scheduling changes do not change the semantic snapshot identity or invalidate valid results.
                old.set("effective_settings", frozen);
                expire(state);
                for (JsonNode t : state.withArray("tasks")) if (t.path("status").asText().equals("failed") && t.path("failures").asInt() <= frozen.path("execution").path("max_retries").asInt()) ((ObjectNode) t).put("status", "pending");
                event(state, "resumed", "Frozen semantic/context settings retained");
            }
            if (!selection.overrides.isEmpty() || !selection.phaseLimits.isEmpty()) event(state, "scheduling_override", Data.JSON.writeValueAsString(Map.of("options", selection.overrides, "phase_limits", selection.phaseLimits)));
            save(dir, state);
            ObjectNode output = summary(dir, state).put("invalidated", !changes.isEmpty()).put("settings_policy", "frozen; use a new run to adopt edited project semantic/context settings"); output.set("changes", changes); return output;
        }
    }

    private static ObjectNode snapshot(Path project, String runId, int generation, ObjectNode input, ObjectNode settings, JsonNode repos, ArrayNode memory) throws Exception {
        ObjectNode node = Data.object().put("schema_version", 1).put("run_id", runId).put("generation", generation).put("created_at", Data.now());
        node.set("input", input.deepCopy()); node.set("effective_settings", settings.deepCopy()); node.set("repositories", repos.deepCopy()); node.set("used_memory", memory.deepCopy());
        node.set("observed_inputs", observe(project, repos, memory));
        ObjectNode basis = Data.object(); basis.set("input", input); basis.set("observed_inputs", node.get("observed_inputs"));
        ObjectNode semantic = settings.deepCopy(); semantic.remove("execution"); basis.set("semantic_settings", semantic);
        node.put("snapshot_id", "s-" + Data.sha256(Data.JSON.writeValueAsBytes(basis)).substring(0, 24)); return node;
    }

    private static ObjectNode observe(Path project, JsonNode repositories, JsonNode memory) throws Exception {
        TreeMap<String, ObjectNode> files = new TreeMap<>(); Map<Path, String> hashes = new HashMap<>();
        for (String file : List.of("project/settings.yaml", "project/repos.yaml", "project/services.yaml")) observeFile(files, hashes, "project:" + file, project.resolve(BASE + file));
        for (String category : List.of("frameworks", "mappings")) collect(project.resolve(BASE + category), false, path -> observeFile(files, hashes, "project:" + category + "/" + slash(project.resolve(BASE + category).relativize(path)), path));
        for (JsonNode repo : repositories.path("repos")) {
            String id = repo.path("id").asText(); Path root = Data.resolve(project, repo.path("path").asText());
            collect(root, true, path -> {
                if (CONFIG_FILE.matcher(path.getFileName().toString()).matches()) observeFile(files, hashes, "repo:" + id + "/" + slash(root.relativize(path)), path);
            });
            String gitHead = gitHead(root);
            files.put("git:" + id, Data.object().put("path", root.toString()).put("status", gitHead == null ? "unavailable" : "present").put("sha256", gitHead == null ? "unavailable" : gitHead));
            for (JsonNode module : repo.path("modules")) {
                for (JsonNode src : module.path("source_roots")) {
                    Path source = Data.resolve(root, src.asText());
                    collect(source, false, path -> observeFile(files, hashes, "source:" + id + "/" + module.path("id").asText() + "/" + src.asText().replace('\\', '/') + "/" + slash(source.relativize(path)), path));
                }
                if (module.hasNonNull("classpath_file")) {
                    Path cp = Data.resolve(root, requiredText(module, "classpath_file"));
                    String prefix = "dependency:" + id + "/" + module.path("id").asText(); observeFile(files, hashes, prefix + "/classpath", cp);
                    if (Files.isRegularFile(cp)) {
                        String content = Files.readString(cp).trim();
                        for (String entry : content.split("\\R|" + Pattern.quote(File.pathSeparator))) if (!entry.isBlank()) {
                            Path dep = Data.resolve(cp.getParent(), entry.trim());
                            if (Files.isDirectory(dep)) collect(dep, false, path -> observeFile(files, hashes, prefix + "/" + dep + "/" + slash(dep.relativize(path)), path));
                            else observeFile(files, hashes, prefix + "/" + dep, dep);
                        }
                    }
                }
            }
        }
        for (JsonNode item : memory) {
            String key = "memory:" + item.path("id").asText() + "@" + item.path("revision").asInt();
            Path path = Data.resolve(project, item.path("path").asText()); observeFile(files, hashes, key, path);
            if (Files.isRegularFile(path)) {
                String markdown = Files.readString(path).replace("\r\n", "\n"); int end = markdown.indexOf("\n---", 4);
                Data.require(markdown.startsWith("---\n") && end > 4, "Invalid pinned memory frontmatter: " + path);
                JsonNode metadata = Data.YAML.readTree(markdown.substring(4, end));
                for (JsonNode source : metadata.path("source_refs")) {
                    Path original = Data.resolve(project, source.path("path").asText().replace('\\', '/'));
                    observeFile(files, hashes, key + "/source:" + source.path("path").asText(), original);
                }
            }
        }
        ObjectNode observations = Data.object(); files.forEach(observations::set); return observations;
    }

    @FunctionalInterface private interface FileConsumer { void accept(Path path) throws IOException; }
    private static void collect(Path root, boolean repository, FileConsumer visitor) throws IOException {
        if (!Files.isDirectory(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString(); String relative = slash(root.relativize(dir));
                if (name.equals(".git") || (repository && (Set.of(".temp", "target", "build", "node_modules", ".idea", ".vscode").contains(name) || relative.equals("docs/business-flow")))) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                String name = path.getFileName().toString();
                if (attrs.isRegularFile() && !name.startsWith(".write-") && !name.endsWith(".lock") && !name.equals(".gitignore")) visitor.accept(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void observeFile(Map<String, ObjectNode> files, Map<Path, String> hashes, String key, Path path) throws IOException {
        path = path.toAbsolutePath().normalize(); ObjectNode item = Data.object().put("path", path.toString());
        if (Files.isRegularFile(path)) {
            String hash = hashes.get(path); if (hash == null) { hash = Data.fingerprint(path); hashes.put(path, hash); }
            item.put("status", "present").put("sha256", hash);
        } else item.put("status", "missing").putNull("sha256");
        files.put(key, item);
    }

    private static String gitHead(Path root) throws Exception {
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            String value = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && value.matches("[0-9a-f]{40,64}") ? value : null;
        } catch (IOException ex) { return null; }
    }

    private static ArrayNode bootstrap(JsonNode snapshot) {
        ArrayNode tasks = Data.array(); ArrayNode inventories = Data.array();
        for (JsonNode repo : snapshot.path("repositories").path("repos")) for (JsonNode module : repo.path("modules")) {
            String id = "inventory-" + Data.sha256((repo.path("id").asText() + ":" + module.path("id").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12);
            ObjectNode scope = Data.object().put("repo_id", repo.path("id").asText()).put("module_id", module.path("id").asText());
            scope.set("module", module.deepCopy()); scope.put("repo_path", repo.path("path").asText()); scope.set("entry_selectors", snapshot.path("input").deepCopy());
            tasks.add(newTask(snapshot, id, "inventory", Data.array(), scope, "Extract module facts and exposure candidates; do not infer unknown framework semantics", "bootstrap")); inventories.add(id);
        }
        String previous = null;
        for (String phase : List.of("frameworks", "mappings", "scenarios", "knowledge")) {
            ArrayNode deps = previous == null ? inventories.deepCopy() : Data.array().add(previous);
            tasks.add(newTask(snapshot, phase, phase, deps, (ObjectNode) snapshot.path("input").deepCopy(), switch (phase) {
                case "frameworks" -> "Apply validated RPC rules; learn missing mechanisms with source evidence and ask about unknowns";
                case "mappings" -> "Resolve endpoint candidates using validated rules and preserved manual overrides";
                case "scenarios" -> "Trace request-to-return potential paths including guards, failure/fallback and unresolved frontiers";
                default -> "Independently review evidence and publish clearly scoped Markdown knowledge; incomplete scope stays partial";
            }, "bootstrap")); previous = phase;
        }
        return tasks;
    }

    private static ObjectNode newTask(JsonNode snapshot, String id, String phase, ArrayNode deps, ObjectNode scope, String objective, String origin) {
        ObjectNode task = Data.object().put("id", Data.id(id)).put("run_id", snapshot.path("run_id").asText()).put("snapshot_id", snapshot.path("snapshot_id").asText())
            .put("phase", phase).put("objective", objective).put("status", "pending").put("attempt", 0).put("failures", 0).put("origin", origin).put("created_at", Data.now()).put("updated_at", Data.now());
        task.set("dependencies", deps); task.set("scope", scope); return task;
    }

    private static JsonNode taskCommand(Path project, List<String> args) throws Exception {
        if (args.isEmpty() || args.equals(List.of("--help"))) return help();
        String command = args.getFirst(); Set<String> allowed = switch (command) {
            case "list", "changes" -> Set.of("--run-id");
            case "add", "answer", "adopt", "use-memory" -> Set.of("--run-id", "--file");
            case "claim" -> Set.of("--run-id", "--worker", "--task-id", "--phase");
            case "heartbeat" -> Set.of("--run-id", "--task-id", "--worker", "--lease-token");
            case "checkpoint" -> Set.of("--run-id", "--task-id", "--worker", "--lease-token", "--file");
            case "finish" -> Set.of("--run-id", "--task-id", "--worker", "--lease-token", "--result");
            default -> throw new IllegalArgumentException("Unknown task command: " + command);
        };
        Map<String, String> opts = Data.options(args.subList(1, args.size()), allowed); String runId = Data.id(Data.required(opts, "--run-id")); Path dir = runDirectory(project, runId);
        try (AutoCloseable ignored = Data.lock(dir.resolve("run.lock"))) {
            ObjectNode state = load(dir); expire(state); JsonNode output;
            switch (command) {
                case "list" -> output = state.deepCopy();
                case "changes" -> output = envelope("changes", currentChanges(project, state));
                case "add" -> output = add(state, Data.read(Data.resolve(project, Data.required(opts, "--file"))));
                case "claim" -> { unchanged(project, state); output = claim(dir, state, opts); }
                case "heartbeat" -> {
                    ObjectNode task = owned(state, opts); ((ObjectNode) task.get("lease")).put("expires_at", deadline(state)); task.put("updated_at", Data.now()); output = task.deepCopy();
                }
                case "finish" -> { unchanged(project, state); output = finish(project, dir, state, opts, false); }
                case "checkpoint" -> { unchanged(project, state); output = finish(project, dir, state, opts, true); }
                case "answer" -> output = answer(state, Data.read(Data.resolve(project, Data.required(opts, "--file"))));
                case "adopt" -> output = adopt(project, state, Data.read(Data.resolve(project, Data.required(opts, "--file"))));
                case "use-memory" -> output = useMemory(project, state, Data.read(Data.resolve(project, Data.required(opts, "--file"))));
                default -> throw new IllegalStateException();
            }
            save(dir, state); return output;
        }
    }

    private static JsonNode add(ObjectNode state, JsonNode input) {
        version(input); Data.require(input.path("tasks").isArray() && !input.path("tasks").isEmpty(), "Task addition needs a nonempty tasks array");
        ArrayNode next = state.withArray("tasks").deepCopy();
        for (JsonNode item : input.get("tasks")) {
            String phase = requiredText(item, "phase"); Data.require(PHASES.contains(phase), "Unknown task phase: " + phase);
            Data.require(item.path("dependencies").isArray() && item.path("scope").isObject(), "Task needs explicit dependencies array and scope object");
            next.add(newTask(state.get("snapshot"), requiredText(item, "id"), phase, (ArrayNode) item.get("dependencies").deepCopy(), (ObjectNode) item.get("scope").deepCopy(), requiredText(item, "objective"), "dynamic"));
        }
        validateDag(next); state.set("tasks", next); event(state, "tasks_added", Integer.toString(input.get("tasks").size())); return envelope("tasks", next);
    }

    private static void validateDag(ArrayNode tasks) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode task : tasks) Data.require(byId.putIfAbsent(task.path("id").asText(), task) == null, "Duplicate task id: " + task.path("id").asText());
        Map<String, Integer> color = new HashMap<>(); for (String id : byId.keySet()) visitDag(id, byId, color);
    }

    private static void visitDag(String id, Map<String, JsonNode> tasks, Map<String, Integer> color) {
        Data.require(tasks.containsKey(id), "Missing dependency task: " + id); int c = color.getOrDefault(id, 0);
        Data.require(c != 1, "Task dependency cycle at " + id); if (c == 2) return;
        color.put(id, 1); Set<String> seen = new HashSet<>();
        for (JsonNode dep : tasks.get(id).path("dependencies")) {
            Data.require(dep.isTextual() && seen.add(dep.asText()), "Dependency IDs must be unique strings");
            Data.require(tasks.containsKey(dep.asText()), "Missing dependency task: " + dep.asText());
            Data.require(PHASES.indexOf(tasks.get(dep.asText()).path("phase").asText()) <= PHASES.indexOf(tasks.get(id).path("phase").asText()), "A task cannot depend on a later phase: " + id);
            visitDag(dep.asText(), tasks, color);
        }
        color.put(id, 2);
    }

    private static JsonNode claim(Path dir, ObjectNode state, Map<String, String> opts) throws IOException {
        String worker = Data.required(opts, "--worker"); Data.require(!worker.isBlank() && worker.length() <= 200, "Worker name must contain 1-200 characters");
        if (opts.containsKey("--phase")) Data.require(PHASES.contains(opts.get("--phase")), "Unknown phase: " + opts.get("--phase"));
        if (opts.containsKey("--task-id")) findTask(state, opts.get("--task-id"));
        JsonNode execution = state.path("snapshot").path("effective_settings").path("execution");
        int active = active(state, null);
        if (active >= execution.path("max_parallel_tasks").asInt()) return unavailable("global_concurrency_limit");
        for (JsonNode item : state.withArray("tasks")) {
            if (opts.containsKey("--task-id") && !item.path("id").asText().equals(opts.get("--task-id"))) continue;
            String phase = item.path("phase").asText(); if (opts.containsKey("--phase") && !phase.equals(opts.get("--phase"))) continue;
            if (!item.path("status").asText().equals("pending")) continue;
            boolean ready = true; for (JsonNode dependency : item.path("dependencies")) if (!findTask(state, dependency.asText()).path("status").asText().equals("completed")) ready = false;
            if (!ready || active(state, phase) >= execution.path("phase_limits").path(phase).asInt(execution.path("max_parallel_tasks").asInt())) continue;
            ObjectNode task = (ObjectNode) item; task.put("status", "in_progress").put("attempt", task.path("attempt").asInt() + 1).put("updated_at", Data.now());
            task.putObject("lease").put("worker", worker).put("token", UUID.randomUUID().toString()).put("claimed_at", Data.now()).put("expires_at", deadline(state));
            Path output = dir.resolve("tasks/" + task.path("id").asText()); Files.createDirectories(output);
            return Data.object().put("schema_version", 1).put("claimed", true).put("output_directory", output.toString()).set("task", task.deepCopy());
        }
        return unavailable("no_ready_task; inspect dependencies, questions and failed tasks");
    }

    private static ObjectNode unavailable(String reason) { return Data.object().put("schema_version", 1).put("claimed", false).put("reason", reason); }
    private static int active(JsonNode state, String phase) { int n = 0; for (JsonNode t : state.path("tasks")) if (t.path("status").asText().equals("in_progress") && (phase == null || phase.equals(t.path("phase").asText()))) n++; return n; }
    private static String deadline(JsonNode state) { return Instant.now().plusSeconds(state.path("snapshot").path("effective_settings").path("execution").path("task_timeout_seconds").asInt()).toString(); }

    private static ObjectNode owned(ObjectNode state, Map<String, String> opts) {
        ObjectNode task = findTask(state, Data.required(opts, "--task-id"));
        Data.require(task.path("status").asText().equals("in_progress"), "Task has no active lease: " + task.path("id").asText());
        Data.require(task.path("lease").path("worker").asText().equals(Data.required(opts, "--worker")) && task.path("lease").path("token").asText().equals(Data.required(opts, "--lease-token")), "Stale or foreign lease; obtain a new claim");
        return task;
    }

    private static JsonNode finish(Path project, Path dir, ObjectNode state, Map<String, String> opts, boolean checkpoint) throws Exception {
        ObjectNode task = owned(state, opts); Path outputDir = dir.resolve("tasks/" + task.path("id").asText());
        Path resultPath = Data.resolve(project, Data.required(opts, checkpoint ? "--file" : "--result")); containedFile(outputDir, resultPath);
        JsonNode result = Data.read(resultPath); version(result);
        for (String key : List.of("run_id", "snapshot_id")) Data.require(result.path(key).asText().equals(task.path(key).asText()), "Result " + key + " does not match the claimed task");
        Data.require(result.path("task_id").asText().equals(task.path("id").asText()), "Result task_id does not match the claimed task");
        Data.require(result.path("lease_token").asText().equals(task.path("lease").path("token").asText()), "Result is from a stale task attempt");
        String status = checkpoint ? "checkpoint" : requiredText(result, "status");
        Data.require(checkpoint || Set.of("completed", "blocked", "failed").contains(status), "Result status must be completed, blocked or failed");
        requiredText(result, "summary");
        Data.require(result.path("questions").isArray(), "Result requires an explicit questions array");
        Data.require(!status.equals("completed") || result.get("questions").isEmpty(), "An unresolved question cannot be marked completed");
        Data.require(!status.equals("blocked") || !result.get("questions").isEmpty(), "A blocked result needs at least one concrete question");
        Data.require(result.path("evidence_refs").isArray(), "Result requires an evidence_refs array; publication independently validates their content");
        Data.require(result.path("output_files").isArray(), "Result requires output_files with path and sha256");
        Data.require(!status.equals("completed") || !result.path("output_files").isEmpty(), "Completed tasks require at least one inspectable output file");
        for (JsonNode file : result.get("output_files")) {
            Path path = outputDir.resolve(requiredText(file, "path")).normalize(); containedFile(outputDir, path);
            Data.require(Data.fingerprint(path).equals(requiredText(file, "sha256")), "Output hash mismatch: " + path);
        }
        Data.require(result.path("frontier").isArray(), "Result requires an explicit frontier array");
        Data.require(!status.equals("completed") || result.path("frontier").isEmpty(), "Incomplete frontier cannot be marked completed");
        // Validate all question identities before changing shared state.
        Set<String> incoming = new HashSet<>();
        for (JsonNode q : result.get("questions")) {
            String id = Data.id(requiredText(q, "id")); requiredText(q, "question"); Data.require(incoming.add(id), "Duplicate question id: " + id);
            for (JsonNode existing : state.withArray("questions")) Data.require(!existing.path("id").asText().equals(id), "Question id already exists; use a new id or answer the existing question: " + id);
        }
        for (JsonNode q : result.get("questions")) {
            ObjectNode item = q.deepCopy(); item.put("task_id", task.path("id").asText()).put("snapshot_id", task.path("snapshot_id").asText()).put("status", "open").put("created_at", Data.now()); state.withArray("questions").add(item);
        }
        {
            ObjectNode frontier = Data.object().put("task_id", task.path("id").asText()).put("snapshot_id", task.path("snapshot_id").asText()).put("updated_at", Data.now()); frontier.set("items", result.get("frontier").deepCopy());
            ArrayNode next = Data.array(); for (JsonNode f : state.withArray("frontier")) if (!f.path("task_id").asText().equals(task.path("id").asText())) next.add(f);
            if (!result.path("frontier").isEmpty()) next.add(frontier); state.set("frontier", next);
        }
        if (checkpoint) {
            task.put("status", result.get("questions").isEmpty() ? "pending" : "blocked");
        } else if (status.equals("failed")) failure(state, task, "worker_failure");
        else task.put("status", status);
        Path archived = outputDir.resolve("accepted/attempt-" + task.path("attempt").asInt() + (checkpoint ? "-checkpoint.json" : "-result.json")); Data.write(archived, result);
        task.put("last_result", slash(dir.relativize(archived))).put("last_result_sha256", Data.fingerprint(archived)).put("updated_at", Data.now()); task.remove("lease");
        event(state, checkpoint ? "checkpoint" : "task_finished", task.path("id").asText() + ": " + task.path("status").asText());
        return task.deepCopy();
    }

    private static void containedFile(Path root, Path file) throws IOException {
        Data.require(Files.isRegularFile(file), "Expected an existing task output file: " + file);
        Data.require(file.toRealPath().startsWith(root.toRealPath()), "Task output must stay inside its assigned directory: " + root);
    }

    private static JsonNode answer(ObjectNode state, JsonNode input) {
        version(input); String id = requiredText(input, "question_id"); requiredText(input, "answer"); requiredText(input, "answered_by");
        Data.require(input.path("source").asText().equals("user"), "Ambiguity decisions must record an actual user answer with source: user");
        ObjectNode question = null; for (JsonNode q : state.withArray("questions")) if (q.path("id").asText().equals(id)) question = (ObjectNode) q;
        Data.require(question != null && question.path("status").asText().equals("open"), "Question is missing, answered or from an invalidated snapshot: " + id);
        ObjectNode decision = input.deepCopy(); decision.put("recorded_at", Data.now()).put("snapshot_id", question.path("snapshot_id").asText()); state.withArray("decisions").add(decision);
        question.put("status", "answered").put("answered_at", Data.now());
        String taskId = question.path("task_id").asText(); boolean remaining = false;
        for (JsonNode q : state.withArray("questions")) if (q.path("task_id").asText().equals(taskId) && q.path("status").asText().equals("open")) remaining = true;
        ObjectNode task = findTask(state, taskId); if (!remaining && task.path("status").asText().equals("blocked")) task.put("status", "pending").put("updated_at", Data.now());
        event(state, "question_answered", id); return decision;
    }

    private static JsonNode adopt(Path project, ObjectNode state, JsonNode input) throws Exception {
        version(input); Data.require(active(state, null) == 0, "Adopt is a stage barrier; finish or checkpoint active tasks first");
        JsonNode old = state.path("snapshot"); Data.require(requiredText(input, "expected_snapshot_id").equals(old.path("snapshot_id").asText()), "Snapshot changed; review changes again");
        String phase = requiredText(input, "phase"); Data.require(Set.of("frameworks", "mappings").contains(phase), "Only framework/mapping publications can be adopted"); requiredText(input, "reason");
        Data.require(input.path("changes").isArray() && !input.path("changes").isEmpty(), "Adopt requires the exact changes from task changes");
        Data.require(input.path("evidence_refs").isArray() && !input.path("evidence_refs").isEmpty(), "Adopt requires reviewed evidence references");
        ObjectNode current = snapshot(project, state.path("run_id").asText(), old.path("generation").asInt() + 1, (ObjectNode) old.get("input"), (ObjectNode) old.get("effective_settings"), old.get("repositories"), (ArrayNode) old.get("used_memory"));
        ArrayNode actual = changes(old.get("observed_inputs"), current.get("observed_inputs"));
        Data.require(!actual.isEmpty() && canonicalChanges(actual).equals(canonicalChanges(input.get("changes"))), "Adopt changes do not exactly match current files; inspect task changes");
        for (JsonNode change : actual) Data.require(change.path("key").asText().startsWith("project:" + phase + "/"), "Source/config/dependency changes require --resume, not adopt: " + change.path("key").asText());
        state.set("snapshot", current);
        for (JsonNode item : state.withArray("tasks")) {
            ObjectNode t = (ObjectNode) item;
            if (PHASES.indexOf(t.path("phase").asText()) > PHASES.indexOf(phase)) {
                t.put("status", "pending").put("failures", 0).put("requires_scope_revalidation", true); t.remove("lease");
            }
            if (!t.path("status").asText().equals("completed")) t.put("snapshot_id", current.path("snapshot_id").asText());
        }
        for (JsonNode q : state.withArray("questions")) if (q.path("status").asText().equals("open") && findTask(state, q.path("task_id").asText()).path("requires_scope_revalidation").asBoolean()) ((ObjectNode) q).put("status", "superseded");
        ObjectNode e = event(state, "inputs_adopted", input.path("reason").asText()); e.set("review", input.deepCopy()); return current;
    }

    private static JsonNode useMemory(Path project, ObjectNode state, JsonNode input) throws Exception {
        version(input); Data.require(active(state, null) == 0, "Pin memory before dispatching tasks or at a stage barrier"); unchanged(project, state);
        Data.require(state.path("snapshot").path("effective_settings").path("memory").path("enabled").asBoolean(), "Project memory is disabled for this run");
        Data.require(input.path("entries").isArray() && !input.path("entries").isEmpty(), "use-memory requires entries");
        ObjectNode old = (ObjectNode) state.get("snapshot"); ArrayNode used = old.withArray("used_memory").deepCopy();
        Set<String> known = new HashSet<>(); for (JsonNode item : used) known.add(item.path("id").asText());
        for (JsonNode entry : input.get("entries")) {
            String id = Data.id(requiredText(entry, "id")); positiveNode(entry.path("revision"), 1, "revision");
            Data.require(known.add(id), "Memory is already pinned; revalidate in a fresh run before replacing: " + id);
            Path path = Data.resolve(project, requiredText(entry, "path")); Path memoryRoot = project.resolve(BASE + "memory"); containedFile(memoryRoot, path);
            Path immutable = memoryRoot.resolve("versions/" + id + "/r%06d.md".formatted(entry.path("revision").asInt())).toAbsolutePath().normalize();
            Data.require(path.equals(immutable), "Pin the immutable memory/versions/<id>/rNNNNNN.md file, not the mutable entry or index");
            JsonNode verified = MemoryService.execute(project, List.of("verify", "--id", id)).path("entry");
            Data.require(verified.path("revision").asInt() == entry.path("revision").asInt() && verified.path("state").asText().equals("verified") && verified.path("verification").path("status").asText().equals("matched"), "Memory is stale, unavailable, or no longer the current verified revision: " + id);
            Data.require(requiredText(entry, "sha256").equals(Data.fingerprint(path)), "Memory changed before use: " + id);
            ObjectNode copy = entry.deepCopy(); copy.put("path", slash(project.relativize(path))).put("pinned_at", Data.now()); used.add(copy);
        }
        Data.require(used.size() <= old.path("effective_settings").path("context").path("max_memory_items").asInt(), "Run memory budget exceeded; narrow retrieval instead of loading everything");
        ObjectNode current = snapshot(project, state.path("run_id").asText(), old.path("generation").asInt() + 1, (ObjectNode) old.get("input"), (ObjectNode) old.get("effective_settings"), old.get("repositories"), used);
        state.set("snapshot", current); for (JsonNode t : state.withArray("tasks")) if (!t.path("status").asText().equals("completed")) ((ObjectNode) t).put("snapshot_id", current.path("snapshot_id").asText());
        event(state, "memory_pinned", Integer.toString(input.get("entries").size())); return current;
    }

    private static SortedMap<String, String> canonicalChanges(JsonNode list) {
        SortedMap<String, String> result = new TreeMap<>();
        for (JsonNode c : list) {
            String key = requiredText(c, "key"); Data.require(c.has("previous_sha256") && c.has("sha256"), "Each adopted change requires previous_sha256 and sha256");
            Data.require(result.putIfAbsent(key, c.get("previous_sha256").toString() + ":" + c.get("sha256").toString()) == null, "Repeated adopted change: " + key);
        }
        return result;
    }

    private static ArrayNode currentChanges(Path project, JsonNode state) throws Exception {
        JsonNode snap = state.get("snapshot"); return changes(snap.get("observed_inputs"), observe(project, snap.get("repositories"), snap.get("used_memory")));
    }
    private static void unchanged(Path project, JsonNode state) throws Exception { Data.require(currentChanges(project, state).isEmpty(), "Observed inputs changed. Review task changes; adopt reviewed framework/mapping outputs at a stage barrier, otherwise --resume to invalidate stale work"); }
    private static ArrayNode changes(JsonNode old, JsonNode current) {
        TreeSet<String> keys = new TreeSet<>(); old.fieldNames().forEachRemaining(keys::add); current.fieldNames().forEachRemaining(keys::add); ArrayNode result = Data.array();
        for (String key : keys) if (!Objects.equals(old.get(key), current.get(key))) {
            ObjectNode c = Data.object().put("key", key).put("path", current.path(key).path("path").asText(old.path(key).path("path").asText()));
            c.set("previous_sha256", old.path(key).path("sha256").isMissingNode() ? Data.JSON.nullNode() : old.path(key).get("sha256"));
            c.set("sha256", current.path(key).path("sha256").isMissingNode() ? Data.JSON.nullNode() : current.path(key).get("sha256")); result.add(c);
        }
        return result;
    }

    private static void expire(ObjectNode state) {
        Instant now = Instant.now();
        for (JsonNode item : state.withArray("tasks")) if (item.path("status").asText().equals("in_progress") && !Instant.parse(item.path("lease").path("expires_at").asText()).isAfter(now)) {
            ObjectNode task = (ObjectNode) item; failure(state, task, "lease_expired"); task.remove("lease"); event(state, "lease_expired", task.path("id").asText());
        }
    }
    private static void failure(JsonNode state, ObjectNode task, String reason) {
        int count = task.path("failures").asInt() + 1; task.put("failures", count).put("last_failure", reason).put("updated_at", Data.now());
        task.put("status", count <= state.path("snapshot").path("effective_settings").path("execution").path("max_retries").asInt() ? "pending" : "failed");
    }

    private static ObjectNode findTask(JsonNode state, String id) { for (JsonNode task : state.path("tasks")) if (task.path("id").asText().equals(id)) return (ObjectNode) task; throw new IllegalArgumentException("Unknown task id: " + id); }
    private static ObjectNode event(ObjectNode state, String kind, String detail) { ObjectNode event = Data.object().put("kind", kind).put("at", Data.now()).put("detail", detail); state.withArray("events").add(event); return event; }
    private static Path runDirectory(Path project, String id) { Path dir = project.resolve(RUNS + Data.id(id)); Data.require(Files.isRegularFile(dir.resolve("state.json")), "Run not found: " + id + "; a deleted temporary run cannot be resumed exactly"); return dir; }
    private static ObjectNode load(Path dir) throws IOException { JsonNode state = Data.read(dir.resolve("state.json")); version(state); Data.require(state.isObject(), "Run state must be an object"); validateDag((ArrayNode) state.path("tasks")); return (ObjectNode) state; }

    private static void save(Path dir, ObjectNode state) throws IOException {
        state.put("revision", state.path("revision").asInt() + 1).put("updated_at", Data.now());
        boolean complete = !state.withArray("tasks").isEmpty(), blocked = false;
        for (JsonNode task : state.withArray("tasks")) { complete &= task.path("status").asText().equals("completed"); blocked |= Set.of("blocked", "failed").contains(task.path("status").asText()); }
        complete &= state.withArray("frontier").isEmpty();
        for (JsonNode question : state.withArray("questions")) complete &= !question.path("status").asText().equals("open");
        state.put("status", complete ? "ready_for_review" : blocked ? "needs_attention" : "in_progress");
        JsonNode snapshot = state.path("snapshot"); Path history = dir.resolve("history/snapshot-" + snapshot.path("generation").asInt() + "-" + snapshot.path("snapshot_id").asText() + ".json");
        if (!Files.exists(history)) Data.write(history, snapshot);
        Data.write(dir.resolve("state.json"), state); // authoritative commit point; projections may be reconstructed after a crash.
        Data.write(dir.resolve("snapshot.json"), snapshot);
        for (String key : List.of("tasks", "frontier", "questions", "decisions")) Data.write(dir.resolve(key + (Set.of("questions", "decisions").contains(key) ? ".yaml" : ".json")), envelope(key, state.get(key)));
        Data.writeText(dir.resolve("resume.md"), "# 恢复运行\n\n运行 ID：`" + state.path("run_id").asText() + "`。\n\n使用 `/super-business-flow --resume " + state.path("run_id").asText() + "`。CLI 将验证输入并恢复工作队列；它不会自动执行 Agent 分析。\n\n先读取 `snapshot.json`、`tasks.json`、`frontier.json`、`questions.yaml` 和 `decisions.yaml`，按依赖领取任务。`state.json` 是原子提交的权威状态，其他文件是投影，禁止手工改写投影来确认事实。未知机制必须提出问题，普通业务条件保留两条可能分支。所有子任务只写自己的任务目录，由主 Agent 审阅并发布到 docs/business-flow。\n\n详见 `docs/business-flow/design/run-contract.md`。任务全部完成只代表待独立审阅，最终发布仍受证据与覆盖率校验约束。\n");
    }

    private static ObjectNode summary(Path dir, JsonNode state) {
        return Data.object().put("schema_version", 1).put("run_id", state.path("run_id").asText()).put("run_directory", dir.toString())
            .put("snapshot_id", state.path("snapshot").path("snapshot_id").asText()).put("status", state.path("status").asText()).put("task_count", state.path("tasks").size());
    }
    private static ObjectNode envelope(String key, JsonNode value) { return Data.object().put("schema_version", 1).set(key, value.deepCopy()); }
    private static String slash(Path path) { return path.toString().replace('\\', '/'); }
    private static String requiredText(JsonNode node, String key) { Data.require(node.path(key).isTextual() && !node.path(key).asText().isBlank(), "Required nonempty text field: " + key); return node.get(key).asText(); }
    private static void version(JsonNode node) { Data.require(node.isObject() && node.path("schema_version").isIntegralNumber() && node.path("schema_version").asInt() == 1, "Expected schema_version: 1 object"); }
    private static JsonNode help() {
        return Data.object().put("schema_version", 1).put("usage", "run (--class FQCN [--url URL ...] | --url URL ... | --all | --resume RUN_ID) [--max-parallel-tasks N] [--phase-limit PHASE=N] [--max-retries N] [--task-timeout-seconds N]")
            .put("tasks", "task list|changes|add|claim|heartbeat|checkpoint|finish|answer|adopt|use-memory --run-id ID ...")
            .put("guide", "docs/business-flow/design/run-contract.md");
    }
}

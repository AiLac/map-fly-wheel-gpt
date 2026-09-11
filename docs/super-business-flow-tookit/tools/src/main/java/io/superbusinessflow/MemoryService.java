package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Small, evidence-backed project memory. The Agent validates meaning; this class validates provenance. */
public final class MemoryService {
    private static final String ROOT = Data.DATA_ROOT + "/memory";
    private static final Set<String> INPUT_FIELDS = Set.of("schema_version", "id", "type", "statement", "scope",
        "state", "source_refs", "canonical_refs", "supersedes", "basis", "body", "reason");
    private static final Set<String> CONTENT_FIELDS = Set.of("schema_version", "id", "type", "statement", "scope",
        "state", "source_refs", "canonical_refs", "supersedes", "basis", "reason", "state_reason");

    private MemoryService() {}

    public static JsonNode execute(Path project, List<String> args) throws Exception {
        Data.require(!args.isEmpty(), "memory requires put, retrieve, verify, delete or index");
        Path root = project.toAbsolutePath().normalize();
        String command = args.getFirst();
        List<String> rest = args.subList(1, args.size());
        try (AutoCloseable ignored = Data.lock(root.resolve(Data.DATA_ROOT + "/.project.lock"))) {
            return switch (command) {
                case "put" -> put(root, Data.options(rest, Set.of("--file", "--expected-revision")));
                case "retrieve" -> retrieve(root, Data.options(rest, Set.of("--query", "--scope", "--limit")));
                case "verify" -> verify(root, Data.options(rest, Set.of("--id")));
                case "delete" -> delete(root, Data.options(rest, Set.of("--id", "--expected-revision", "--reason")));
                case "index" -> {
                    Data.require(rest.isEmpty(), "memory index takes no options");
                    yield rebuildIndex(root);
                }
                default -> throw new IllegalArgumentException("Unknown memory operation: " + command);
            };
        }
    }

    private static JsonNode put(Path project, Map<String, String> options) throws IOException {
        Entry incoming = input(Data.resolve(project, Data.required(options, "--file")));
        ObjectNode next = incoming.meta();
        String id = next.path("id").asText();
        Entry current = load(project, id);
        Integer expected = expectedRevision(options, current != null);
        checkRevision(current, expected, id);
        Data.require(current == null || !current.meta().path("state").asText().equals("deleted"),
            "Memory " + id + " is deleted; create a new id with supersedes instead of reusing its tombstone");
        Verification verification = inspectSources(project, next.path("source_refs"));
        if (next.path("state").asText().equals("verified")) {
            Data.require(verification.matched(), "Verified memory needs live, matching source evidence: " + verification.details());
        }
        String now = Data.now();
        if (current != null && sameContent(current, incoming)) {
            int previousRevision = current.meta().path("revision").asInt();
            ObjectNode metadata = current.meta();
            metadata.set("verification", verification.json(now));
            if (verification.matched()) metadata.put("last_verified_at", now);
            // A no-op put cannot conceal changed evidence behind unchanged content.
            Entry checked = applyVerification(project, current, verification, now);
            saveHead(project, checked);
            rebuildIndex(project);
            return result(checked.meta().path("revision").asInt() == previousRevision ? "unchanged" : "updated", checked);
        }
        int revision = current == null ? 1 : current.meta().path("revision").asInt() + 1;
        next.put("revision", revision);
        next.put("created_at", current == null ? now : current.meta().path("created_at").asText());
        next.put("updated_at", now);
        copyNullable(next, "last_used_at", current);
        next.putNull("deleted_at");
        if (verification.matched()) next.put("last_verified_at", now);
        else copyNullable(next, "last_verified_at", current);
        next.set("verification", verification.json(now));
        if (verification.mismatch() && !next.path("state").asText().equals("stale")) {
            next.put("state", "stale");
            next.put("state_reason", "Source fingerprint changed; revalidate the statement before reuse");
        }
        Entry stored = new Entry(next, incoming.body());
        saveRevision(project, stored);
        rebuildIndex(project);
        return result(current == null ? "created" : "updated", stored);
    }

    private static JsonNode retrieve(Path project, Map<String, String> options) throws IOException {
        int limit = options.containsKey("--limit") ? positiveInt(options.get("--limit"), "--limit") : defaultLimit(project);
        Data.require(limit <= 1000, "--limit must not exceed 1000");
        String query = options.getOrDefault("--query", "").strip().toLowerCase(Locale.ROOT);
        String scope = options.get("--scope");
        ArrayNode items = Data.array();
        ArrayNode excluded = Data.array();
        if (!memoryEnabled(project)) return Data.object().put("schema_version", 1).put("enabled", false)
            .put("limit", limit).set("items", items);
        List<Entry> entries = loadAll(project);
        // Stable ordering makes bounded retrieval repeatable; this is term matching, not semantic search.
        entries.sort(Comparator.comparing(e -> e.meta().path("id").asText()));
        for (Entry entry : entries) {
            if (!matches(entry, query, scope)) continue;
            if (entry.meta().path("state").asText().equals("deleted")) {
                excluded.add(exclusion(entry));
                continue;
            }
            Verification verification = inspectSources(project, entry.meta().path("source_refs"));
            Entry checked = applyVerification(project, entry, verification, Data.now());
            if (!checked.meta().path("state").asText().equals("verified") || !verification.matched()) {
                excluded.add(exclusion(checked));
            } else if (items.size() < limit) {
                checked.meta().put("last_used_at", Data.now());
                saveHead(project, checked);
                items.add(checked.json());
            }
        }
        rebuildIndex(project);
        ObjectNode output = Data.object().put("schema_version", 1).put("enabled", true).put("limit", limit);
        output.set("items", items); output.set("excluded", excluded);
        return output;
    }

    private static JsonNode verify(Path project, Map<String, String> options) throws IOException {
        Entry current = requiredEntry(project, Data.id(Data.required(options, "--id")));
        Entry checked = current;
        if (!current.meta().path("state").asText().equals("deleted")) {
            checked = applyVerification(project, current,
                inspectSources(project, current.meta().path("source_refs")), Data.now());
        }
        rebuildIndex(project);
        return result("checked", checked);
    }

    private static JsonNode delete(Path project, Map<String, String> options) throws IOException {
        String id = Data.id(Data.required(options, "--id"));
        String reason = Data.required(options, "--reason").strip();
        Data.require(!reason.isEmpty(), "Deletion requires a nonempty --reason");
        Entry current = requiredEntry(project, id);
        checkRevision(current, expectedRevision(options, true), id);
        Data.require(!current.meta().path("state").asText().equals("deleted"), "Memory is already deleted: " + id);
        ObjectNode next = current.meta().deepCopy();
        String now = Data.now();
        next.put("state", "deleted").put("state_reason", reason).put("updated_at", now).put("deleted_at", now);
        next.put("revision", next.path("revision").asInt() + 1);
        Entry tombstone = new Entry(next, current.body());
        saveRevision(project, tombstone);
        rebuildIndex(project);
        return result("deleted", tombstone);
    }

    private static Entry applyVerification(Path project, Entry current, Verification verification, String now) throws IOException {
        ObjectNode next = current.meta().deepCopy();
        next.set("verification", verification.json(now));
        if (verification.matched()) next.put("last_verified_at", now);
        boolean changed = verification.mismatch() && !next.path("state").asText().equals("stale");
        if (changed) {
            next.put("state", "stale").put("state_reason", "Source fingerprint changed; revalidate the statement before reuse");
            next.put("revision", next.path("revision").asInt() + 1).put("updated_at", now);
        }
        Entry checked = new Entry(next, current.body());
        if (changed) saveRevision(project, checked); else saveHead(project, checked);
        return checked;
    }

    private static Entry input(Path file) throws IOException {
        Entry parsed = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")
            ? readMarkdown(file) : fromJson(Data.read(file));
        ObjectNode metadata = parsed.meta().deepCopy();
        metadata.fieldNames().forEachRemaining(key -> Data.require(INPUT_FIELDS.contains(key),
            "Unsupported input field (timestamps and revisions are CLI-owned): " + key));
        Data.require(metadata.path("schema_version").isIntegralNumber() && metadata.path("schema_version").asInt() == 1,
            "Memory input requires schema_version: 1");
        Data.id(requiredText(metadata, "id"));
        requiredText(metadata, "type"); requiredText(metadata, "statement");
        Data.require(Set.of("verified", "pending", "stale").contains(requiredText(metadata, "state")),
            "Input state must be verified, pending or stale; use memory delete for tombstones");
        stringArray(metadata, "scope", true);
        Data.require(metadata.path("source_refs").isArray() && !metadata.path("source_refs").isEmpty(),
            "Memory requires at least one durable source_ref, including pending findings");
        for (JsonNode ref : metadata.path("source_refs")) {
            Data.require(ref.isObject(), "Each source_ref must be an object");
            requiredText(ref, "path");
            Data.require(requiredText(ref, "sha256").matches("[a-fA-F0-9]{64}"), "source_refs.sha256 must be a SHA-256 hex digest");
        }
        stringArray(metadata, "canonical_refs", false);
        for (JsonNode ref : metadata.path("canonical_refs")) validateRelative(ref.asText());
        stringArray(metadata, "supersedes", false);
        for (JsonNode id : metadata.path("supersedes")) Data.id(id.asText());
        if (!metadata.has("basis")) metadata.set("basis", Data.object());
        Data.require(metadata.path("basis").isObject(), "basis must be an object of version metadata");
        if (metadata.has("reason")) requiredText(metadata, "reason");
        return new Entry(metadata, parsed.body());
    }

    private static Verification inspectSources(Path project, JsonNode sourceRefs) throws IOException {
        ArrayNode issues = Data.array();
        boolean mismatch = false, unavailable = false;
        List<Path> registered = registeredRoots(project);
        for (JsonNode ref : sourceRefs) {
            String relative = requiredText(ref, "path");
            Path file = sourcePath(project, relative, registered);
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                unavailable = true;
                issues.add(Data.object().put("path", relative).put("status", "unavailable").put("reason", "Source is missing or unreadable"));
                continue;
            }
            try {
                String actual = Data.fingerprint(file);
                if (!actual.equalsIgnoreCase(ref.path("sha256").asText())) {
                    mismatch = true;
                    issues.add(Data.object().put("path", relative).put("status", "mismatch")
                        .put("expected_sha256", ref.path("sha256").asText()).put("actual_sha256", actual));
                }
            } catch (IOException ex) {
                unavailable = true;
                issues.add(Data.object().put("path", relative).put("status", "unavailable").put("reason", "Source could not be read"));
            }
        }
        return new Verification(mismatch, unavailable, sourceRefs.size(), issues);
    }

    private static Path sourcePath(Path project, String relative, List<Path> registered) throws IOException {
        String normalized = validateRelative(relative);
        Path lexical = project.resolve(normalized).normalize();
        Path canonical = canonicalPath(lexical);
        Path governance = canonicalPath(project.resolve(Data.DATA_ROOT));
        Path evidence = canonicalPath(project.resolve(Data.DATA_ROOT + "/evidence"));
        Data.require(!canonical.startsWith(canonicalPath(project.resolve(Data.TOOLKIT_ROOT))),
            "Toolkit files are not original business evidence: " + relative);
        Data.require(!canonical.startsWith(canonicalPath(project.resolve(".temp"))), "Transient source evidence is forbidden: " + relative);
        Data.require(!canonical.startsWith(canonicalPath(project.resolve(ROOT))), "Memory cannot be its own original evidence: " + relative);
        Data.require(!canonical.startsWith(governance) || canonical.startsWith(evidence),
            "Memory, knowledge and generated rules are not original evidence; preserve original sources in evidence/: " + relative);
        boolean allowed = canonical.startsWith(evidence);
        for (Path repo : registered) if (canonical.startsWith(repo)) allowed = true;
        Data.require(allowed, "Source must be in evidence/ or a registered repository: " + relative);
        return canonical;
    }

    private static String validateRelative(String path) {
        String normalized = path.replace('\\', '/');
        Data.require(!normalized.isBlank() && !normalized.startsWith("/") && !normalized.matches("^[A-Za-z]:.*"),
            "Use project-relative source paths: " + path);
        for (String part : normalized.split("/"))
            Data.require(!Set.of(".temp", ".git").contains(part), "Transient source reference is forbidden: " + path);
        return normalized;
    }

    private static List<Path> registeredRoots(Path project) throws IOException {
        Path config = project.resolve(Data.DATA_ROOT + "/project/repos.yaml");
        List<Path> roots = new ArrayList<>();
        if (Files.exists(config)) {
            JsonNode repos = Data.read(config).path("repos");
            Data.require(repos.isArray(), "repos.yaml requires a repos array");
            for (JsonNode repo : repos) roots.add(canonicalPath(Data.resolve(project, requiredText(repo, "path"))));
        }
        return roots;
    }

    /** Resolve existing ancestors too, so a missing file cannot evade containment using a symlink. */
    private static Path canonicalPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) return absolute.toRealPath();
        Path parent = absolute.getParent();
        return parent == null ? absolute : canonicalPath(parent).resolve(absolute.getFileName());
    }

    private static Entry load(Path project, String id) throws IOException {
        Path head = project.resolve(ROOT + "/entries/" + Data.id(id) + ".md");
        Path versions = project.resolve(ROOT + "/versions/" + id);
        Entry current = Files.exists(head) ? readMarkdown(head) : null;
        Path latest = null;
        if (Files.isDirectory(versions)) {
            try (Stream<Path> paths = Files.list(versions)) {
                latest = paths.filter(path -> path.getFileName().toString().matches("r[0-9]{6,}\\.md"))
                    .max(Comparator.comparingLong(MemoryService::versionNumber)).orElse(null);
            }
        }
        if (latest != null) {
            Entry history = readMarkdown(latest);
            Data.require(history.meta().path("id").asText().equals(id)
                && history.meta().path("revision").asLong() == versionNumber(latest), "Invalid memory version file: " + latest);
            if (current == null || current.meta().path("revision").asInt() < history.meta().path("revision").asInt()) {
                // An immutable revision is the commit record; repair a head lost during interrupted publication.
                current = history;
                saveHead(project, current);
            } else {
                Data.require(current.meta().path("revision").asInt() == history.meta().path("revision").asInt()
                    && sameContent(current, history), "Memory head disagrees with immutable history: " + id);
            }
        } else Data.require(current == null, "Memory head has no immutable history: " + id);
        return current;
    }

    private static long versionNumber(Path path) {
        String name = path.getFileName().toString();
        return Long.parseLong(name.substring(1, name.length() - 3));
    }

    private static List<Entry> loadAll(Path project) throws IOException {
        Set<String> ids = new TreeSet<>();
        Path entries = project.resolve(ROOT + "/entries"), versions = project.resolve(ROOT + "/versions");
        if (Files.isDirectory(entries)) try (Stream<Path> paths = Files.list(entries)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".md")).forEach(p -> {
                String name = p.getFileName().toString(); ids.add(Data.id(name.substring(0, name.length() - 3)));
            });
        }
        if (Files.isDirectory(versions)) try (Stream<Path> paths = Files.list(versions)) {
            paths.filter(Files::isDirectory).forEach(p -> ids.add(Data.id(p.getFileName().toString())));
        }
        List<Entry> result = new ArrayList<>();
        for (String id : ids) { Entry entry = load(project, id); if (entry != null) result.add(entry); }
        return result;
    }

    private static ObjectNode rebuildIndex(Path project) throws IOException {
        ArrayNode entries = Data.array();
        for (Entry entry : loadAll(project)) {
            ObjectNode metadata = entry.meta().deepCopy();
            metadata.put("entry_path", ROOT + "/entries/" + metadata.path("id").asText() + ".md");
            entries.add(metadata);
        }
        ObjectNode index = Data.object().put("schema_version", 1).put("generated_at", Data.now());
        index.set("entries", entries);
        Data.write(project.resolve(ROOT + "/index.json"), index);
        return index;
    }

    private static void saveRevision(Path project, Entry entry) throws IOException {
        String id = entry.meta().path("id").asText();
        Path version = project.resolve(ROOT + "/versions/" + Data.id(id) + "/r%06d.md".formatted(entry.meta().path("revision").asInt()));
        Data.require(!Files.exists(version), "Immutable memory revision already exists: " + version);
        // The project OS lock excludes other publishers. Each file is atomically replaced by Data.writeText.
        Data.writeText(version, markdown(entry));
        saveHead(project, entry);
    }

    private static void saveHead(Path project, Entry entry) throws IOException {
        Data.writeText(project.resolve(ROOT + "/entries/" + Data.id(entry.meta().path("id").asText()) + ".md"), markdown(entry));
    }

    private static Entry requiredEntry(Path project, String id) throws IOException {
        Entry entry = load(project, id); Data.require(entry != null, "Unknown memory id: " + id); return entry;
    }

    private static Entry readMarkdown(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Data.require(text.startsWith("---\n"), "Memory Markdown must start with YAML frontmatter: " + path);
        int end = text.indexOf("\n---\n", 4);
        Data.require(end >= 0, "Memory Markdown needs a closing frontmatter delimiter: " + path);
        JsonNode meta = Data.YAML.readTree(text.substring(4, end));
        Data.require(meta != null && meta.isObject(), "Memory frontmatter must be an object: " + path);
        return new Entry((ObjectNode) meta, normalizeBody(text.substring(end + 5)));
    }

    private static Entry fromJson(JsonNode input) {
        Data.require(input.isObject(), "Memory input must be an object");
        ObjectNode metadata = ((ObjectNode) input).deepCopy();
        Data.require(!metadata.has("body") || metadata.path("body").isTextual(), "Memory body must be text");
        String body = normalizeBody(metadata.path("body").asText("")); metadata.remove("body");
        return new Entry(metadata, body);
    }

    private static String markdown(Entry entry) throws IOException {
        String yaml = Data.YAML.writeValueAsString(entry.meta());
        if (yaml.startsWith("---\n")) yaml = yaml.substring(4);
        return "---\n" + yaml.stripTrailing() + "\n---\n\n" + entry.body() + "\n";
    }

    private static String normalizeBody(String body) { return body.replace("\r\n", "\n").strip(); }

    private static boolean sameContent(Entry a, Entry b) {
        ObjectNode left = Data.object(), right = Data.object();
        for (String key : CONTENT_FIELDS) {
            if (a.meta().has(key)) left.set(key, a.meta().get(key));
            if (b.meta().has(key)) right.set(key, b.meta().get(key));
        }
        return left.equals(right) && a.body().equals(b.body());
    }

    private static Integer expectedRevision(Map<String, String> options, boolean required) {
        Data.require(!required || options.containsKey("--expected-revision"), "Updating an existing memory requires --expected-revision");
        if (!options.containsKey("--expected-revision")) return null;
        try {
            int value = Integer.parseInt(options.get("--expected-revision"));
            Data.require(value >= 0, "--expected-revision must be zero or a positive integer"); return value;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("--expected-revision must be zero or a positive integer"); }
    }

    private static void checkRevision(Entry current, Integer expected, String id) {
        int actual = current == null ? 0 : current.meta().path("revision").asInt();
        Data.require(expected == null ? actual == 0 : expected == actual,
            "Memory revision conflict for " + id + ": expected " + expected + ", actual " + actual + "; reload and merge");
    }

    private static int positiveInt(String value, String name) {
        try { int number = Integer.parseInt(value); Data.require(number > 0, name + " must be positive"); return number; }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(name + " must be a positive integer"); }
    }

    private static int defaultLimit(Path project) throws IOException {
        Path settings = project.resolve(Data.DATA_ROOT + "/project/settings.yaml");
        int limit = Files.exists(settings) ? Data.read(settings).path("context").path("max_memory_items").asInt(10) : 10;
        Data.require(limit > 0 && limit <= 1000, "context.max_memory_items must be between 1 and 1000"); return limit;
    }

    private static boolean memoryEnabled(Path project) throws IOException {
        Path settings = project.resolve(Data.DATA_ROOT + "/project/settings.yaml");
        return !Files.exists(settings) || Data.read(settings).path("memory").path("enabled").asBoolean(true);
    }

    private static boolean matches(Entry entry, String query, String scope) {
        if (scope != null) {
            boolean found = false;
            for (JsonNode token : entry.meta().path("scope")) if (scope.equals(token.asText())) found = true;
            if (!found) return false;
        }
        String haystack = (entry.meta().path("id").asText() + " " + entry.meta().path("type").asText() + " "
            + entry.meta().path("statement").asText() + " " + entry.meta().path("scope") + " " + entry.body()).toLowerCase(Locale.ROOT);
        for (String term : query.split("\\s+")) if (!haystack.contains(term)) return false;
        return true;
    }

    private static String requiredText(JsonNode node, String key) {
        Data.require(node.path(key).isTextual() && !node.path(key).asText().isBlank(), "Nonempty text required: " + key);
        return node.path(key).asText();
    }

    private static void stringArray(ObjectNode node, String key, boolean nonempty) {
        if (!node.has(key) && !nonempty) node.set(key, Data.array());
        Data.require(node.path(key).isArray() && (!nonempty || !node.path(key).isEmpty()), key + " must be " + (nonempty ? "a nonempty" : "an") + " array");
        Set<String> values = new HashSet<>();
        for (JsonNode value : node.path(key)) Data.require(value.isTextual() && !value.asText().isBlank() && values.add(value.asText()),
            key + " must contain distinct nonempty strings");
    }

    private static void copyNullable(ObjectNode node, String key, Entry current) {
        if (current != null && current.meta().hasNonNull(key)) node.set(key, current.meta().get(key)); else node.putNull(key);
    }

    private static ObjectNode exclusion(Entry entry) {
        return Data.object().put("id", entry.meta().path("id").asText()).put("state", entry.meta().path("state").asText())
            .put("verification_status", entry.meta().path("verification").path("status").asText("unverified"));
    }

    private static ObjectNode result(String action, Entry entry) {
        ObjectNode result = Data.object().put("schema_version", 1).put("action", action);
        result.set("entry", entry.json()); return result;
    }

    private record Entry(ObjectNode meta, String body) {
        ObjectNode json() { ObjectNode json = meta.deepCopy(); json.put("body", body); return json; }
    }

    private record Verification(boolean mismatch, boolean unavailable, int count, ArrayNode details) {
        boolean matched() { return !mismatch && !unavailable && count > 0; }
        ObjectNode json(String now) {
            String status = mismatch ? "mismatch" : unavailable ? "unavailable" : count == 0 ? "unverified" : "matched";
            ObjectNode result = Data.object().put("status", status).put("checked_at", now);
            result.set("issues", details); return result;
        }
    }
}

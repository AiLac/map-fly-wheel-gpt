package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URI;
import java.util.*;

/** Publishes evidence-backed static graphs. Business interpretation remains an Agent task. */
public final class PublicationService {
    private static final String BASE = "docs/business-flow/";
    public static JsonNode evidence(Path project, List<String> args) throws Exception {
        var opts = Data.options(args, Set.of("--source", "--symbol", "--line-start", "--line-end"));
        Path source = Data.resolve(project, Data.required(opts, "--source"));
        Data.require(Files.isRegularFile(source), "Source file not found: " + source);
        Data.require(!source.toString().replace('\\', '/').contains("/memory/"), "Memory is not original source evidence");
        byte[] raw = Files.readAllBytes(source);
        List<String> lines = new String(raw, StandardCharsets.UTF_8).lines().toList();
        int start = Integer.parseInt(opts.getOrDefault("--line-start", "1"));
        int end = Integer.parseInt(opts.getOrDefault("--line-end", String.valueOf(lines.size())));
        Data.require(start >= 1 && start <= end && end <= lines.size(), "Invalid source line interval");
        String excerpt = String.join("\n", lines.subList(start - 1, end)) + "\n";
        String sourceHash = Data.sha256(raw), excerptHash = Data.sha256(excerpt.getBytes(StandardCharsets.UTF_8));
        String sourceIdentity = source.startsWith(project.toAbsolutePath().normalize()) ? project.toAbsolutePath().normalize().relativize(source).toString().replace('\\','/') : source.toString();
        String key = Data.sha256((sourceIdentity + ":" + sourceHash + ":" + start + ":" + end + ":" + opts.getOrDefault("--symbol", "")).getBytes(StandardCharsets.UTF_8));
        String relative = BASE + "evidence/" + key + "/source.txt";
        ObjectNode origin = Data.object().put("path", sourceIdentity).put("sha256", sourceHash)
            .put("line_start", start).put("line_end", end).put("symbol", opts.getOrDefault("--symbol", ""));
        ObjectNode record = Data.object().put("schema_version", 1).put("id", "ev-" + key).put("path", relative).put("sha256", excerptHash);
        record.set("source", origin);
        try (var ignored = Data.lock(project.resolve(BASE + ".project.lock"))) {
            Path target = project.resolve(relative), recordPath = target.resolveSibling("record.json");
            if (Files.exists(target)) Data.require(Data.fingerprint(target).equals(excerptHash), "Immutable evidence was changed: " + target);
            else Data.writeText(target, excerpt);
            if (!Files.exists(recordPath)) Data.write(recordPath, record);
        }
        return record;
    }

    public static JsonNode publish(Path project, List<String> args) throws Exception {
        var opts = Data.options(args, Set.of("--graph", "--scenario-id", "--expected-revision"));
        String scenario = Data.id(Data.required(opts, "--scenario-id"));
        JsonNode graph = Data.read(Data.resolve(project, Data.required(opts, "--graph")));
        try (var ignored = Data.lock(project.resolve(BASE + ".project.lock"))) {
            validateGraph(project, graph);
            Data.require(graph.path("scenario_id").asText().equals(scenario), "scenario_id does not match --scenario-id");
            Path scenarioDir = project.resolve(BASE + "scenarios/" + scenario);
            Path manifestFile = scenarioDir.resolve("manifest.json");
            int previous = Files.exists(manifestFile) ? Data.read(manifestFile).path("revision").asInt() : 0;
            int expected = Integer.parseInt(opts.getOrDefault("--expected-revision", "0"));
            Data.require(expected == previous, "Publication revision changed; inspect manifest.json and pass --expected-revision " + previous);
            String hash = Data.sha256(Data.JSON.writeValueAsBytes(graph));
            Path knowledge = project.resolve(BASE + "knowledge/" + scenario), revision = knowledge.resolve("revisions/" + hash);
            Path graphOutput = revision.resolve("graph.json");
            if (!Files.exists(graphOutput)) {
                ArrayNode mappingSnapshots = Data.array(); Set<String> copied = new HashSet<>();
                for (JsonNode edge : graph.path("edges")) if (edge.path("kind").asText().equals("rpc")) {
                    JsonNode ref = edge.path("mapping_ref"); String mappingHash = ref.path("sha256").asText();
                    if (copied.add(mappingHash)) {
                        Path source = Data.resolve(project, ref.path("path").asText());
                        String name = "mapping-snapshots/" + mappingHash + (source.toString().endsWith(".json") ? ".json" : ".yaml");
                        String contents = Files.readString(source, StandardCharsets.UTF_8);
                        Data.require(Data.sha256(contents.getBytes(StandardCharsets.UTF_8)).equals(mappingHash), "Mapping changed during publication");
                        Data.writeText(revision.resolve(name), contents);
                        mappingSnapshots.add(Data.object().put("source_path", ref.path("path").asText()).put("sha256", mappingHash).put("snapshot_path", name));
                    }
                }
                ObjectNode mappingManifest = Data.object().put("schema_version", 1); mappingManifest.set("mappings", mappingSnapshots);
                Data.write(revision.resolve("mapping-snapshots.json"), mappingManifest);
                Data.writeText(revision.resolve("overview.md"), overview(project, revision, graph));
                Data.writeText(revision.resolve("flow.md"), flow(graph));
                Data.writeText(revision.resolve("sequence.md"), sequence(graph));
                Data.writeText(revision.resolve("code-links.md"), codeLinks(project, revision, graph));
                Data.write(graphOutput, graph);
            } else Data.require(Data.read(graphOutput).equals(graph), "Immutable graph revision conflict");
            ObjectNode manifest = Data.object().put("schema_version", 1).put("scenario_id", scenario).put("revision", previous + 1)
                .put("graph_sha256", hash).put("status", graph.path("coverage").path("status").asText()).put("updated_at", Data.now())
                .put("knowledge_path", BASE + "knowledge/" + scenario + "/revisions/" + hash);
            // The versioned bundle is complete before the current pointer advances.
            Data.write(scenarioDir.resolve("graph.json"), graph);
            Data.write(manifestFile, manifest);
            Data.writeText(knowledge.resolve("README.md"), "# " + plain(graph.path("title").asText(scenario)) + "\n\n状态：" + manifest.path("status").asText() +
                "；静态可能链路。\n\n[业务概述](revisions/" + hash + "/overview.md) · [流程图](revisions/" + hash + "/flow.md) · [服务关系](revisions/" + hash + "/sequence.md) · [代码入口](revisions/" + hash + "/code-links.md)\n");
            return manifest;
        }
    }

    static void validateGraph(Path project, JsonNode graph) throws Exception {
        try (var stream = PublicationService.class.getResourceAsStream("/schemas/graph.schema.json")) {
            Data.require(stream != null, "Missing graph schema; rebuild the tool");
            ValidationService.validate(graph, Data.JSON.readTree(stream));
        }
        Map<String,JsonNode> evidence = index(graph.path("evidence"), "evidence");
        Path durableRoot = project.resolve(BASE + "evidence").toAbsolutePath().normalize();
        for (JsonNode e : evidence.values()) {
            Path path = Data.resolve(project, e.path("path").asText());
            Data.require(path.startsWith(durableRoot) && Files.isRegularFile(path), "Publication needs durable evidence: " + e.path("id").asText());
            Data.require(path.toRealPath().startsWith(durableRoot.toRealPath()), "Evidence symlink escapes durable directory");
            Data.require(Data.fingerprint(path).equals(e.path("sha256").asText()), "Stale evidence copy: " + e.path("id").asText());
        }
        Map<String,JsonNode> nodes = index(graph.path("nodes"), "node"), conditions = index(graph.path("conditions"), "condition");
        index(graph.path("edges"), "edge");
        for (JsonNode node : nodes.values()) refs(node, evidence);
        for (JsonNode condition : conditions.values()) refs(condition, evidence);
        for (JsonNode edge : graph.path("edges")) {
            refs(edge, evidence);
            Data.require(nodes.containsKey(edge.path("from").asText()) && nodes.containsKey(edge.path("to").asText()), "Dangling graph edge: " + edge.path("id"));
            Data.require(!Set.of("candidate", "unresolved").contains(edge.path("resolution").asText()), "Candidates belong in frontier, not confirmed graph edges");
            for (JsonNode ref : edge.path("condition_refs")) Data.require(conditions.containsKey(ref.asText()), "Unknown condition: " + ref.asText());
            if (edge.path("kind").asText().equals("rpc")) {
                Data.require(edge.path("binding_id").isTextual(), "RPC edge needs binding_id");
                Data.require(edge.path("mapping_ref").isObject(), "RPC edge needs pinned effective mapping_ref {path,sha256}");
                JsonNode mapRef = edge.path("mapping_ref");
                Path mapPath = Data.resolve(project, mapRef.path("path").asText());
                Data.require(mapPath.startsWith(project.resolve(BASE + "mappings/effective").toAbsolutePath().normalize()), "RPC edge must use effective mapping");
                Data.require(Files.isRegularFile(mapPath) && Data.fingerprint(mapPath).equals(mapRef.path("sha256").asText()), "Effective mapping changed");
                JsonNode effective = Data.read(mapPath); JsonNode binding = null;
                Data.require(effective.path("layer").asText().equals("effective"), "RPC mapping must be produced by mappings resolve");
                for (JsonNode mappingEvidence : effective.path("evidence")) {
                    JsonNode sourceEvidence = mappingEvidence.has("path") ? mappingEvidence : mappingEvidence.path("source");
                    Path sourcePath = Data.resolve(project, sourceEvidence.path("path").asText());
                    Data.require(Files.isRegularFile(sourcePath) && Data.fingerprint(sourcePath).equals(sourceEvidence.path("sha256").asText()), "Mapping source evidence changed: " + mappingEvidence.path("id").asText());
                }
                for (JsonNode b : effective.path("bindings")) if (b.path("id").asText().equals(edge.path("binding_id").asText())) binding = b;
                Data.require(binding != null && binding.path("resolution").path("status").asText().equals("confirmed"), "RPC binding is not confirmed");
                Data.require(nodes.get(edge.path("from").asText()).path("callsite_id").asText().equals(binding.path("caller").path("callsite_id").asText()) && !binding.path("caller").path("callsite_id").asText().isBlank(), "RPC edge caller callsite_id does not match binding");
                String endpointId = nodes.get(edge.path("to").asText()).path("endpoint_id").asText();
                boolean matches = false;
                for (JsonNode target : binding.path("targets")) if (target.path("endpoint_id").asText().equals(endpointId)) {
                    String guard = target.path("condition_ref").asText("");
                    matches = guard.isEmpty() || contains(edge.path("condition_refs"), guard);
                    if (matches) break;
                }
                Data.require(matches, "RPC edge target/guard does not match binding");
            }
        }
        JsonNode coverage = graph.path("coverage");
        if (coverage.path("status").asText().equals("complete")) {
            Data.require(coverage.path("frontier").isEmpty() && coverage.path("open_questions").isEmpty(), "Complete graph still has unresolved scope");
            Data.require(coverage.path("review").path("status").asText().equals("passed") && !coverage.path("review").path("reviewer").asText().isBlank(), "Complete publication needs independent review");
            refs(coverage.path("review"), evidence);
        }
    }
    private static boolean contains(JsonNode items, String s) { for (JsonNode item : items) if (item.asText().equals(s)) return true; return false; }
    private static Map<String,JsonNode> index(JsonNode entries, String label) {
        Map<String,JsonNode> result = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            String id = Data.id(entry.path("id").asText());
            Data.require(result.putIfAbsent(id, entry) == null, "Duplicate " + label + " id: " + id);
        }
        return result;
    }
    private static void refs(JsonNode claim, Map<String,JsonNode> evidence) {
        Data.require(claim.path("evidence_refs").isArray() && !claim.path("evidence_refs").isEmpty(), "Claim needs original evidence_refs: " + claim.path("id").asText());
        for (JsonNode ref : claim.path("evidence_refs")) Data.require(evidence.containsKey(ref.asText()), "Unknown evidence reference: " + ref.asText());
    }
    private static String overview(Path project, Path output, JsonNode graph) throws Exception {
        StringBuilder md = new StringBuilder("# " + plain(graph.path("title").asText()) + "\n\n状态：**" + graph.path("coverage").path("status").asText() + "**。本图描述业务场景的静态可能链路，不能证明某次请求实际执行的路径。\n\n");
        md.append(graph.path("overview").asText()).append("\n\n## 范围与缺口\n\n");
        md.append("入口：").append(plain(graph.path("entry").toString())).append("\n\n");
        for (String key : List.of("open_questions", "frontier", "accepted_exclusions")) md.append("- ").append(key).append(": ").append(plain(graph.path("coverage").path(key).toString())).append("\n");
        md.append("\n[流程图](flow.md) · [服务交互](sequence.md) · [代码和证据](code-links.md) · [结构化图](graph.json)\n");
        return md.toString();
    }
    private static String flow(JsonNode graph) {
        StringBuilder md = new StringBuilder("# 静态业务流程\n\n箭头类型和条件以结构化图为准；调用关系不等于执行先后关系。\n\n```mermaid\nflowchart TD\n");
        Map<String,String> ids = new LinkedHashMap<>(); int n = 0;
        for (JsonNode node : graph.path("nodes")) { String id = "n" + ++n; ids.put(node.path("id").asText(), id); md.append("  ").append(id).append("[\"").append(mermaid(node.path("label").asText())).append("\"]\n"); }
        Map<String,JsonNode> conditions = index(graph.path("conditions"), "condition");
        for (JsonNode edge : graph.path("edges")) {
            List<String> labels = new ArrayList<>(); labels.add(edge.path("kind").asText());
            for (JsonNode guard : edge.path("condition_refs")) labels.add(conditions.get(guard.asText()).path("expression").asText());
            md.append("  ").append(ids.get(edge.path("from").asText())).append(" -->|\"").append(mermaid(String.join("; ", labels))).append("\"| ").append(ids.get(edge.path("to").asText())).append("\n");
        }
        return md.append("```\n").toString();
    }
    private static String sequence(JsonNode graph) {
        StringBuilder md = new StringBuilder("# 服务交互片段\n\n每个片段只表示一条已确认的潜在跨服务调用；不从调用图推测全局时序、重试次数或并发顺序。真实时序待后续日志/Trace 叠加。\n\n");
        Map<String,JsonNode> nodes = index(graph.path("nodes"), "node"), guards = index(graph.path("conditions"), "condition");
        int count = 0;
        for (JsonNode edge : graph.path("edges")) {
            if (!edge.path("kind").asText().equals("rpc")) continue;
            JsonNode from = nodes.get(edge.path("from").asText()), to = nodes.get(edge.path("to").asText()); count++;
            md.append("## ").append(plain(edge.path("id").asText())).append("\n\n");
            md.append("| 调用方 | 提供方 | 操作 | 条件 |\n| --- | --- | --- | --- |\n| ")
                .append(plain(from.path("service_id").asText(from.path("label").asText()))).append(" | ")
                .append(plain(to.path("service_id").asText(to.path("label").asText()))).append(" | ").append(plain(to.path("label").asText())).append(" | ");
            List<String> conditions = new ArrayList<>(); for (JsonNode ref : edge.path("condition_refs")) conditions.add(guards.get(ref.asText()).path("expression").asText());
            md.append(plain(conditions.isEmpty() ? "未附加分支条件（以证据范围为准）" : String.join(" && ", conditions))).append(" |\n\n");
        }
        if (count == 0) md.append("当前已确认范围内没有跨服务调用。未解析项见业务概述的 frontier。\n");
        return md.toString();
    }
    private static String codeLinks(Path project, Path output, JsonNode graph) throws Exception {
        StringBuilder md = new StringBuilder("# 代码与证据\n\n原代码链接用于本地工作区跳转，指向当前工作树；持久化摘录保留分析时的代码。Markdown 阅读器的行号跳转支持不同，完整符号与行号单独列出。\n\n| 证据 | 原代码 / 符号 | 原行号 |\n| --- | --- | --- |\n");
        for (JsonNode e : graph.path("evidence")) {
            String path = output.relativize(Data.resolve(project, e.path("path").asText())).toString().replace('\\','/');
            String url = new URI(null, null, path, null).toASCIIString();
            JsonNode source = e.path("source");
            String sourceLabel = plain(source.path("symbol").asText(source.path("path").asText()));
            String sourceLink = sourceLabel;
            if (!source.path("path").asText().isBlank()) {
                try {
                    Path original = Data.resolve(project, source.path("path").asText());
                    String originalRelative = output.relativize(original).toString().replace('\\','/');
                    String originalUrl = new URI(null, null, originalRelative, null).toASCIIString();
                    sourceLink = "[" + sourceLabel + "](" + originalUrl + ")";
                } catch (IllegalArgumentException ignored) { /* Different Windows volumes: retain symbol/path and durable excerpt. */ }
            }
            md.append("| [").append(plain(e.path("id").asText())).append("](").append(url).append(") | ")
                .append(sourceLink).append(" | ")
                .append(source.path("line_start").asText()).append("–").append(source.path("line_end").asText()).append(" |\n");
        }
        return md.toString();
    }
    private static String plain(String text) { return text.replace("|", "\\|").replace("\n", " ").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String mermaid(String text) { return text.replace("&", "and").replace('"', '\'').replace("`", "'").replace("\n", " ").replace("<", " less than ").replace(">", " greater than ").replace("|", "/").replace("[", "(").replace("]", ")"); }
}

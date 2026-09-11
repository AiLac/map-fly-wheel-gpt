package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MemoryServiceTest {
    @TempDir Path project;

    @Test void timestampsHaveDistinctLifecyclesAndVersionsAreImmutable() throws Exception {
        Path source = source("original source bytes");
        Path input = input("rpc-navigation", source, "verified", "Remote wrapper navigation");
        JsonNode first = call("put", "--file", input.toString()).path("entry");
        assertEquals(1, first.path("revision").asInt());
        assertEquals(first.path("created_at"), first.path("updated_at"));
        assertTrue(first.path("last_used_at").isNull());
        assertTrue(first.path("deleted_at").isNull());
        Instant.parse(first.path("created_at").asText());
        Instant.parse(first.path("last_verified_at").asText());
        Path history = history("rpc-navigation", 1);
        String originalHistory = Files.readString(history);

        JsonNode used = call("retrieve", "--query", "REMOTE navigation", "--scope", "repo:caller", "--limit", "1").path("items").get(0);
        assertNotNull(used);
        assertTrue(used.path("last_used_at").isTextual());
        assertEquals(first.path("updated_at"), used.path("updated_at"));
        assertEquals(1, used.path("revision").asInt());
        JsonNode checked = call("verify", "--id", "rpc-navigation").path("entry");
        assertEquals(first.path("updated_at"), checked.path("updated_at"));
        assertEquals(used.path("last_used_at"), checked.path("last_used_at"));
        assertEquals(originalHistory, Files.readString(history), "Reads and successful checks must not rewrite historical versions");

        ObjectNode update = (ObjectNode) Data.read(input);
        update.put("statement", "Remote wrapper delegates through the injected field");
        Data.write(input, update);
        JsonNode second = call("put", "--file", input.toString(), "--expected-revision", "1").path("entry");
        assertEquals(2, second.path("revision").asInt());
        assertEquals(first.path("created_at"), second.path("created_at"));
        assertNotEquals(first.path("updated_at"), second.path("updated_at"));
        assertEquals(used.path("last_used_at"), second.path("last_used_at"));
        assertTrue(Files.exists(history("rpc-navigation", 2)));
    }

    @Test void compareAndSwapRejectsLostUpdatesAndNoOpPutRetainsRevision() throws Exception {
        Path input = input("cas", source("same bytes"), "verified", "Stable observation");
        JsonNode first = call("put", "--file", input.toString(), "--expected-revision", "0").path("entry");
        assertThrows(IllegalArgumentException.class, () -> call("put", "--file", input.toString()));
        JsonNode noOp = call("put", "--file", input.toString(), "--expected-revision", "1");
        assertEquals("unchanged", noOp.path("action").asText());
        assertEquals(1, noOp.path("entry").path("revision").asInt());
        assertEquals(first.path("updated_at"), noOp.path("entry").path("updated_at"));
        ObjectNode update = (ObjectNode) Data.read(input);
        update.put("body", "New verified explanation"); Data.write(input, update);
        call("put", "--file", input.toString(), "--expected-revision", "1");
        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
            () -> call("put", "--file", input.toString(), "--expected-revision", "1"));
        assertTrue(conflict.getMessage().contains("actual 2"));
        assertEquals(2, call("verify", "--id", "cas").path("entry").path("revision").asInt());
        assertFalse(Files.exists(history("cas", 3)));
    }

    @Test void hashChangeInvalidatesOnUseAndCorrectionRequiresNewEvidenceAndCas() throws Exception {
        Path source = source("v1");
        Path input = input("changed", source, "verified", "The earlier behavior");
        JsonNode first = call("put", "--file", input.toString()).path("entry");
        Files.writeString(source, "v2", StandardCharsets.UTF_8);
        JsonNode retrieved = call("retrieve", "--query", "earlier");
        assertTrue(retrieved.path("items").isEmpty());
        assertEquals("stale", retrieved.path("excluded").get(0).path("state").asText());
        JsonNode stale = call("verify", "--id", "changed").path("entry");
        assertEquals(2, stale.path("revision").asInt());
        assertEquals("mismatch", stale.path("verification").path("status").asText());
        assertEquals(first.path("last_verified_at"), stale.path("last_verified_at"));
        assertTrue(stale.path("deleted_at").isNull());
        assertThrows(IllegalArgumentException.class,
            () -> call("put", "--file", input.toString(), "--expected-revision", "2"));

        input("changed", source, "verified", "The corrected behavior backed by v2");
        JsonNode corrected = call("put", "--file", input.toString(), "--expected-revision", "2").path("entry");
        assertEquals(3, corrected.path("revision").asInt());
        assertEquals("verified", corrected.path("state").asText());
        assertEquals(first.path("created_at"), corrected.path("created_at"));
        assertEquals(Data.fingerprint(source), corrected.path("source_refs").get(0).path("sha256").asText());
        assertEquals(1, call("retrieve", "--query", "corrected").path("items").size());
    }

    @Test void missingSourceIsUnavailableAndNeverEvidenceOfFalsehood() throws Exception {
        Path source = source("temporarily offline repository bytes");
        Path input = input("offline", source, "verified", "A supported observation");
        JsonNode first = call("put", "--file", input.toString()).path("entry");
        byte[] original = Files.readAllBytes(source); Files.delete(source);
        JsonNode checked = call("verify", "--id", "offline").path("entry");
        assertEquals("verified", checked.path("state").asText());
        assertEquals("unavailable", checked.path("verification").path("status").asText());
        assertEquals(1, checked.path("revision").asInt());
        assertEquals(first.path("updated_at"), checked.path("updated_at"));
        assertEquals(first.path("last_verified_at"), checked.path("last_verified_at"));
        assertTrue(call("retrieve").path("items").isEmpty());
        Files.write(source, original);
        JsonNode restored = call("retrieve").path("items").get(0);
        assertEquals(1, restored.path("revision").asInt());
        assertEquals("matched", restored.path("verification").path("status").asText());
    }

    @Test void deletePreservesTombstoneHistoryAndCannotSilentlyResurrect() throws Exception {
        Path input = input("obsolete", source("original"), "verified", "Replaced finding");
        JsonNode first = call("put", "--file", input.toString()).path("entry");
        assertThrows(IllegalArgumentException.class,
            () -> call("delete", "--id", "obsolete", "--expected-revision", "0", "--reason", "Superseded"));
        JsonNode removed = call("delete", "--id", "obsolete", "--expected-revision", "1", "--reason", "Superseded by canonical mapping").path("entry");
        assertEquals("deleted", removed.path("state").asText());
        assertEquals(2, removed.path("revision").asInt());
        assertEquals(first.path("created_at"), removed.path("created_at"));
        assertEquals(removed.path("updated_at"), removed.path("deleted_at"));
        assertTrue(Files.exists(history("obsolete", 1)));
        assertTrue(call("retrieve").path("items").isEmpty());
        assertEquals("deleted", call("verify", "--id", "obsolete").path("entry").path("state").asText());
        assertThrows(IllegalArgumentException.class,
            () -> call("put", "--file", input.toString(), "--expected-revision", "2"));
    }

    @Test void pendingEvidenceDoesNotBecomeVerifiedFromAHashCheck() throws Exception {
        Path input = input("hypothesis", source("a partial observation"), "pending", "A hypothesis to confirm");
        call("put", "--file", input.toString());
        JsonNode checked = call("verify", "--id", "hypothesis").path("entry");
        assertEquals("pending", checked.path("state").asText());
        assertEquals("matched", checked.path("verification").path("status").asText());
        assertTrue(call("retrieve").path("items").isEmpty());
    }

    @Test void rawEvidenceMayBeRegisteredSiblingRepoButNotMemoryOrTemporaryFiles() throws Exception {
        Path sibling = project.resolve("../business-source-" + project.getFileName()).normalize();
        try {
            Files.createDirectories(sibling.resolve("src/memory"));
            Path realSource = sibling.resolve("src/memory/Cache.java");
            Files.writeString(realSource, "class Cache {}", StandardCharsets.UTF_8);
            ObjectNode config = Data.object().put("schema_version", 1);
            config.set("repos", Data.array().add(Data.object().put("id", "business")
                .put("path", project.relativize(sibling).toString().replace('\\', '/'))));
            Data.write(project.resolve("docs/business-flow/project/repos.yaml"), config);
            Path input = input("registered", realSource, "verified", "Source in independent Git repository");
            assertEquals("created", call("put", "--file", input.toString()).path("action").asText());
        } finally {
            if (Files.exists(sibling)) try (var paths = Files.walk(sibling)) {
                for (Path file : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        Path temporary = project.resolve(".temp/run/super-business-flow/run-1/evidence.txt");
        Files.createDirectories(temporary.getParent()); Files.writeString(temporary, "temporary bytes");
        Path tempInput = input("temporary", temporary, "pending", "Unsafe source");
        assertThrows(IllegalArgumentException.class, () -> call("put", "--file", tempInput.toString()));
        Path memory = project.resolve("docs/business-flow/memory/entries/registered.md");
        Path selfInput = input("self", memory, "verified", "Repeated assertion is not evidence");
        assertThrows(IllegalArgumentException.class, () -> call("put", "--file", selfInput.toString()));
        Path unknown = project.resolve("unregistered.java"); Files.writeString(unknown, "class Unknown {}");
        Path unknownInput = input("unknown", unknown, "verified", "Unknown repository source");
        assertThrows(IllegalArgumentException.class, () -> call("put", "--file", unknownInput.toString()));
    }

    @Test void indexCanBeRebuiltAndInterruptedHeadPublicationRecoversFromHistory() throws Exception {
        Path input = input("recoverable", source("lasting evidence"), "verified", "Survives interrupted publication");
        call("put", "--file", input.toString());
        Files.delete(project.resolve("docs/business-flow/memory/index.json"));
        Files.delete(project.resolve("docs/business-flow/memory/entries/recoverable.md"));
        JsonNode index = call("index");
        assertEquals(1, index.path("entries").size());
        assertEquals("recoverable", index.path("entries").get(0).path("id").asText());
        assertTrue(Files.exists(project.resolve("docs/business-flow/memory/entries/recoverable.md")));
        assertEquals(1, call("retrieve").path("items").size());
    }

    @Test void markdownInputPreservesChineseBodyAndScopeFilteringIsExplicit() throws Exception {
        Path source = source("真实证据");
        Path input = project.resolve("docs/business-flow/input.md");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "---\nschema_version: 1\nid: chinese\ntype: navigation\nstate: verified\n"
            + "statement: RPC 字段导航\nscope: [\"service:text-search\"]\nsource_refs:\n"
            + "  - path: docs/business-flow/evidence/source.txt\n    sha256: " + Data.fingerprint(source)
            + "\n---\n\n正文保留中文。\n", StandardCharsets.UTF_8);
        call("put", "--file", input.toString());
        assertTrue(call("retrieve", "--scope", "repo:caller").path("items").isEmpty());
        JsonNode found = call("retrieve", "--query", "字段 中文", "--scope", "service:text-search").path("items").get(0);
        assertEquals("正文保留中文。", found.path("body").asText());
    }

    @Test void metadataOwnershipAndSharedPublicationLockAreEnforced() throws Exception {
        Path input = input("managed", source("basis"), "verified", "Timestamp ownership");
        ObjectNode data = (ObjectNode) Data.read(input); data.put("created_at", "2000-01-01T00:00:00Z"); Data.write(input, data);
        assertThrows(IllegalArgumentException.class, () -> call("put", "--file", input.toString()));
        data.remove("created_at"); Data.write(input, data);
        try (AutoCloseable ignored = Data.lock(project.resolve("docs/business-flow/.project.lock"))) {
            assertThrows(Exception.class, () -> call("put", "--file", input.toString()));
        }
        assertFalse(Files.exists(history("managed", 1)));
        assertEquals("created", call("put", "--file", input.toString()).path("action").asText());
    }

    private JsonNode call(String... args) throws Exception { return MemoryService.execute(project, List.of(args)); }
    private Path history(String id, int revision) {
        return project.resolve("docs/business-flow/memory/versions/" + id + "/r%06d.md".formatted(revision));
    }
    private Path source(String content) throws Exception {
        Path file = project.resolve("docs/business-flow/evidence/source.txt");
        Files.createDirectories(file.getParent()); Files.writeString(file, content, StandardCharsets.UTF_8); return file;
    }
    private Path input(String id, Path source, String state, String statement) throws Exception {
        ObjectNode input = Data.object().put("schema_version", 1).put("id", id).put("type", "observation")
            .put("state", state).put("statement", statement).put("body", "Evidence describes a scoped observation.");
        input.set("scope", Data.array().add("repo:caller"));
        input.set("source_refs", Data.array().add(Data.object()
            .put("path", project.relativize(source).toString().replace('\\', '/')).put("sha256", Data.fingerprint(source))));
        Path file = project.resolve("docs/business-flow/" + id + "-input.yaml"); Data.write(file, input); return file;
    }
}

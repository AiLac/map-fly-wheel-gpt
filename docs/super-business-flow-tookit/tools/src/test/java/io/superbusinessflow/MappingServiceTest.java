package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MappingServiceTest {
    @TempDir Path project;
    ObjectNode facts, rules, services;
    Path review;

    @BeforeEach void fixtures() throws Exception {
        Path resources = Path.of(getClass().getResource("/mappings/source").toURI());
        facts = document();
        for (String key : List.of("classes", "methods", "calls", "endpoints", "conditions", "evidence", "diagnostics")) facts.putArray(key);
        for (String repo : List.of("caller", "provider", "other")) {
            Path destination = project.resolve("repos/" + repo);
            try (var files = Files.walk(resources.resolve(repo))) {
                for (Path source : files.filter(Files::isRegularFile).toList()) {
                    Path target = destination.resolve(resources.resolve(repo).relativize(source));
                    Files.createDirectories(target.getParent()); Files.copy(source, target);
                }
            }
            JsonNode scanned = ScanService.scan(repo, "main", List.of(destination), null);
            for (String key : List.of("classes", "methods", "calls", "endpoints", "conditions", "evidence", "diagnostics"))
                ((ArrayNode) facts.get(key)).addAll((ArrayNode) scanned.path(key));
        }
        review = project.resolve("docs/super-business-flow/evidence/known-pair.md");
        Files.createDirectories(review.getParent());
        Files.copy(Path.of(getClass().getResource("/mappings/known-pair.md").toURI()), review);
        rules = document();
        ObjectNode framework = rules.putObject("framework");
        framework.put("id", "test-rpc").put("revision", "test-v1").put("status", "verified"); refs(framework);
        ObjectNode consumer = rules.putObject("consumer");
        consumer.put("annotation", "fixture.rpc.RpcReference").put("service_attribute", "microserviceName").put("schema_attribute", "schemaId");
        ObjectNode consumerOperation = consumer.putObject("operation").put("source", "invoked_method_name"); refs(consumerOperation);
        ObjectNode provider = rules.putObject("provider");
        provider.put("annotation", "fixture.rpc.RestSchema").put("schema_attribute", "schemaId");
        provider.putArray("exposure_annotations").add("org.springframework.web.bind.annotation.PostMapping");
        ObjectNode providerOperation = provider.putObject("operation").put("source", "method_name"); refs(providerOperation);
        refs(rules.putObject("contract").put("status", "verified"));
        evidence(rules);
        services = document();
        ArrayNode registered = services.putArray("services");
        register(registered, "caller", "caller-service", null);
        register(registered, "provider", "text-search-service", "MapSiteService:MapSearchTextSearchService");
        register(registered, "other", "other-service", "OtherService");
        evidence(services);
    }

    @Test void resolvesOnlyExactServiceIdentityAndNeverWrapperImplements() throws Exception {
        JsonNode discovered = discover();
        assertEquals(1, discovered.path("bindings").size());
        JsonNode binding = firstBinding(discovered);
        assertEquals("confirmed", binding.path("resolution").path("status").asText());
        assertEquals("demo.TsInnerClient", binding.path("caller").path("class_name").asText());
        assertEquals("MapSiteService:MapSearchTextSearchService", binding.path("remote_identity").path("microservice_name_raw").asText());
        assertEquals(1, binding.path("targets").size());
        JsonNode endpoint = endpoint(discovered, binding.path("targets").get(0).path("endpoint_id").asText());
        assertEquals("demo.TsSearchRpcService", endpoint.path("class_name").asText());
        assertEquals("text-search-service", endpoint.path("service_id").asText());
        assertEquals("/ts-rpc/searchByText", endpoint.path("http").path("path").asText());
        assertEquals(2, discovered.path("endpoints").size());
    }

    @Test void unknownOperationStaysNullAndDoesNotCreateEffectiveEdge() throws Exception {
        ((ObjectNode) rules.path("consumer").path("operation")).put("source", "unknown");
        ((ObjectNode) rules.path("provider").path("operation")).put("source", "unknown");
        JsonNode binding = firstBinding(discover());
        assertTrue(binding.path("remote_identity").path("operation_id").isNull());
        assertEquals("candidate", binding.path("resolution").path("status").asText());
        assertTrue(binding.path("targets").isEmpty());
        assertEquals(1, binding.path("candidate_targets").size());
    }

    @Test void returnWrapperCompatibilityNeedsVerifiedContract() throws Exception {
        ((ObjectNode) rules.path("contract")).put("status", "unknown");
        JsonNode candidate = firstBinding(discover());
        assertEquals("candidate", candidate.path("resolution").path("status").asText());
        assertEquals(1, candidate.path("candidate_targets").size());
        JsonNode effective = MappingService.resolve(project, discover(), emptyEndpoints(), List.of());
        assertEquals("candidate", firstBinding(effective).path("resolution").path("status").asText());
    }

    @Test void shortAnnotationNameIsNeverAcceptedAsProtocolIdentity() throws Exception {
        for (JsonNode clazz : facts.path("classes")) for (JsonNode field : clazz.path("fields"))
            for (JsonNode annotation : field.path("annotations")) {
                ((ObjectNode) annotation).put("full_name", "RpcReference").put("resolved", false);
            }
        assertTrue(discover().path("bindings").isEmpty());
        ((ObjectNode) rules.path("consumer")).put("annotation", "RpcReference");
        assertThrows(IllegalArgumentException.class, this::discover);
    }

    @Test void explicitOperationAliasIsReadInsteadOfJavaMethodName() throws Exception {
        ObjectNode consumer = (ObjectNode) rules.path("consumer").path("operation");
        consumer.put("source", "annotation_attribute").put("annotation", "fixture.rpc.RpcReference").put("attribute", "operation");
        ObjectNode provider = (ObjectNode) rules.path("provider").path("operation");
        provider.put("source", "annotation_attribute").put("annotation", "fixture.rpc.Operation").put("attribute", "id");
        JsonNode binding = firstBinding(discover());
        assertEquals("text-v2", binding.path("remote_identity").path("operation_id").asText());
        assertEquals("confirmed", binding.path("resolution").path("status").asText());
    }

    @Test void staleAndFabricatedEvidenceAreRejected() throws Exception {
        Files.writeString(review, "Changed independently reviewed protocol", StandardCharsets.UTF_8);
        assertTrue(assertThrows(IllegalArgumentException.class, this::discover).getMessage().contains("Stale evidence"));
        evidence(rules); evidence(services);
        ((ObjectNode) rules.path("framework")).putArray("evidence_refs").add("nonexistent-source");
        assertTrue(assertThrows(IllegalArgumentException.class, this::discover).getMessage().contains("Dangling evidence_ref"));
    }

    @Test void operationRuleCannotExecuteArbitraryStrategies() {
        ((ObjectNode) rules.path("consumer").path("operation")).put("source", "eval_java");
        assertTrue(assertThrows(IllegalArgumentException.class, this::discover).getMessage().contains("Unsupported operation strategy"));
    }

    @Test void staleOverrideFailsAndGeneratedInputIsNotMutated() throws Exception {
        ObjectNode generated = (ObjectNode) discover();
        JsonNode original = generated.deepCopy();
        ObjectNode patch = replacement(generated);
        ((ObjectNode) patch.path("overrides").get(0)).put("expected_source_fingerprint", "0".repeat(64));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, emptyEndpoints(), List.of(patch))).getMessage().contains("Stale override"));
        assertEquals(original, generated);
        ((ObjectNode) rules.path("framework")).put("revision", "test-v2");
        assertNotEquals(firstBinding(generated).path("source_fingerprint"), firstBinding(discover()).path("source_fingerprint"));
    }

    @Test void manualKnownPairCanConfirmCandidateWithEvidenceAndCas() throws Exception {
        ((ObjectNode) rules.path("contract")).put("status", "unknown");
        ObjectNode generated = (ObjectNode) discover();
        JsonNode effective = MappingService.resolve(project, generated, emptyEndpoints(), List.of(replacement(generated)));
        JsonNode binding = firstBinding(effective);
        assertEquals("confirmed", binding.path("resolution").path("status").asText());
        assertEquals("manual_override", binding.path("resolution").path("origin").asText());
        assertEquals("candidate", firstBinding(generated).path("resolution").path("status").asText());
    }

    @Test void conflictingPatchesFailRegardlessOfFileOrder() throws Exception {
        ObjectNode generated = (ObjectNode) discover(), a = replacement(generated), b = replacement(generated);
        ((ObjectNode) b.path("overrides").get(0)).put("id", "second-edit").put("action", "disable_binding");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, emptyEndpoints(), List.of(a, b))).getMessage().contains("Conflicting overrides"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, emptyEndpoints(), List.of(b, a))).getMessage().contains("Conflicting overrides"));
    }

    @Test void manualOverrideCannotContradictResolvedService() throws Exception {
        ObjectNode generated = (ObjectNode) discover(), patch = replacement(generated);
        String other = null;
        for (JsonNode endpoint : generated.path("endpoints"))
            if (endpoint.path("service_id").asText().equals("other-service")) other = endpoint.path("id").asText();
        ((ObjectNode) patch.path("overrides").get(0).path("targets").get(0)).put("endpoint_id", other);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, emptyEndpoints(), List.of(patch))).getMessage().contains("contradicts resolved service"));
    }

    @Test void danglingEndpointsCannotBecomeEffectiveBindings() throws Exception {
        ObjectNode generated = (ObjectNode) discover(), patch = replacement(generated);
        ((ObjectNode) patch.path("overrides").get(0).path("targets").get(0)).put("endpoint_id", "missing-endpoint");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, emptyEndpoints(), List.of(patch))).getMessage().contains("Dangling endpoint_id"));
    }

    @Test void multipleEffectiveTargetsNeedExplicitConditionsAndRoutingEvidence() throws Exception {
        ObjectNode generated = (ObjectNode) discover(), patch = replacement(generated);
        ObjectNode original = (ObjectNode) endpoint(generated, firstBinding(generated).path("targets").get(0).path("endpoint_id").asText());
        ObjectNode alternative = original.deepCopy().put("id", "alternative-provider");
        ObjectNode inventory = emptyEndpoints(); ((ArrayNode) inventory.path("endpoints")).add(alternative);
        ObjectNode replace = (ObjectNode) patch.path("overrides").get(0);
        ((ArrayNode) replace.path("targets")).addObject().put("endpoint_id", "alternative-provider").putNull("condition_ref");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, inventory, List.of(patch))).getMessage().contains("conditional routing"));
        refs(replace.putObject("routing").put("mode", "conditional"));
        ArrayNode conditions = patch.putArray("conditions");
        refs(conditions.addObject().put("id", "normal").put("expression", "!request.useAlternative()"));
        refs(conditions.addObject().put("id", "alternative").put("expression", "request.useAlternative()"));
        ((ObjectNode) replace.path("targets").get(0)).put("condition_ref", "normal");
        ((ObjectNode) replace.path("targets").get(1)).put("condition_ref", "alternative");
        JsonNode effective = MappingService.resolve(project, generated, inventory, List.of(patch));
        assertEquals(2, firstBinding(effective).path("targets").size());
        ((ObjectNode) replace.path("targets").get(1)).put("condition_ref", "not-defined");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, inventory, List.of(patch))).getMessage().contains("Dangling condition_ref"));
    }

    @Test void duplicateRawServiceRegistrationIsAnExplicitConflict() {
        ObjectNode another = ((ObjectNode) services.path("services").get(2));
        another.putArray("microservice_names_raw").add("MapSiteService:MapSearchTextSearchService");
        assertTrue(assertThrows(IllegalArgumentException.class, this::discover).getMessage().contains("different services"));
    }

    @Test void unverifiedAliasCannotBorrowAnotherModulesVerifiedServiceIdentity() throws Exception {
        ObjectNode provider = (ObjectNode) services.path("services").get(1);
        provider.putArray("microservice_names_raw");
        ArrayNode registered = (ArrayNode) services.path("services");
        register(registered, "unscanned-module-repo", "text-search-service", "MapSiteService:MapSearchTextSearchService");
        ((ObjectNode) registered.get(3)).put("status", "candidate");
        JsonNode binding = firstBinding(discover());
        assertEquals("candidate", binding.path("resolution").path("status").asText());
        assertTrue(binding.path("targets").isEmpty());
    }

    @Test void temporaryOnlyEvidenceNeverConfirmsAnOverride() throws Exception {
        ObjectNode generated = (ObjectNode) discover(), patch = replacement(generated);
        Path transientFile = project.resolve(".temp/run/super-business-flow/test/evidence.md");
        Files.createDirectories(transientFile.getParent()); Files.writeString(transientFile, "unpublished claim");
        ObjectNode ev = ((ArrayNode) patch.path("evidence")).addObject();
        ev.put("id", "temp-only").put("path", project.relativize(transientFile).toString()).put("sha256", Data.fingerprint(transientFile));
        ((ObjectNode) patch.path("overrides").get(0)).putArray("evidence_refs").add("temp-only");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, emptyEndpoints(), List.of(patch))).getMessage().contains("durable evidence"));
    }

    @Test void disablePreservesTheRecordAndItsSourceFingerprint() throws Exception {
        ObjectNode generated = (ObjectNode) discover(), patch = replacement(generated);
        ((ObjectNode) patch.path("overrides").get(0)).put("action", "disable_binding");
        JsonNode effective = MappingService.resolve(project, generated, emptyEndpoints(), List.of(patch));
        assertEquals("disabled", firstBinding(effective).path("resolution").path("status").asText());
        assertTrue(firstBinding(effective).path("targets").isEmpty());
        assertEquals(firstBinding(generated).path("source_fingerprint"), firstBinding(effective).path("source_fingerprint"));
    }

    @Test void manualEndpointAndMissingBindingCanBeAddedInEitherFileOrder() throws Exception {
        ObjectNode generated = (ObjectNode) discover();
        ObjectNode missingBinding = ((ObjectNode) firstBinding(generated)).deepCopy();
        ObjectNode missingEndpoint = ((ObjectNode) endpoint(generated, missingBinding.path("targets").get(0).path("endpoint_id").asText())).deepCopy();
        ((ArrayNode) generated.path("bindings")).removeAll();
        ((ArrayNode) generated.path("endpoints")).removeAll();
        String sourceRef = missingBinding.path("caller").path("evidence_refs").get(0).asText();
        for (JsonNode source : generated.path("evidence")) if (source.path("id").asText().equals(sourceRef))
            missingBinding.put("source_fingerprint", source.path("sha256").asText());
        ObjectNode addBinding = document(), addEndpoint = document();
        ObjectNode bp = addBinding.putArray("overrides").addObject().put("id", "missing-call").put("action", "add_binding").put("reason", "Known missing invocation");
        bp.set("binding", missingBinding); refs(bp); evidence(addBinding);
        ObjectNode ep = addEndpoint.putArray("overrides").addObject().put("id", "missing-provider").put("action", "add_endpoint").put("reason", "Known missing provider");
        ep.set("endpoint", missingEndpoint); refs(ep); evidence(addEndpoint);
        JsonNode first = MappingService.resolve(project, generated, emptyEndpoints(), List.of(addBinding, addEndpoint));
        JsonNode second = MappingService.resolve(project, generated, emptyEndpoints(), List.of(addEndpoint, addBinding));
        assertEquals(first, second);
        assertEquals("confirmed", firstBinding(first).path("resolution").path("status").asText());
        missingBinding.put("source_fingerprint", "f".repeat(64));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, generated, emptyEndpoints(), List.of(addBinding, addEndpoint)))
                .getMessage().contains("referenced callsite source file"));
    }

    @Test void cliKeepsGeneratedAndManualLayersSeparateAndChecksCurrentRuleFile() throws Exception {
        Path directory = project.resolve("docs/super-business-flow");
        Path factsFile = directory.resolve("inventory/facts.json"), rulesFile = directory.resolve("frameworks/test/rules.yaml");
        Path servicesFile = directory.resolve("project/services.yaml"), generatedFile = directory.resolve("mappings/generated/test.yaml");
        Data.write(factsFile, facts); Data.write(rulesFile, rules); Data.write(servicesFile, services);
        JsonNode generated = MappingService.execute(project, List.of("discover", "--facts", factsFile.toString(), "--rules", rulesFile.toString(),
                "--services", servicesFile.toString(), "--out", generatedFile.toString()));
        assertEquals("confirmed", firstBinding(generated).path("resolution").path("status").asText());
        assertThrows(IllegalArgumentException.class, () -> MappingService.execute(project,
                List.of("discover", "--facts", factsFile.toString(), "--rules", rulesFile.toString(), "--services", servicesFile.toString(), "--out", rulesFile.toString())));
        Files.writeString(rulesFile, Files.readString(rulesFile) + "\n# changed rule source\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MappingService.resolve(project, Data.read(generatedFile), emptyEndpoints(), List.of())).getMessage().contains("Stale evidence"));
    }

    private JsonNode discover() throws Exception { return MappingService.discover(project, facts, rules, services); }
    private static ObjectNode document() { return Data.object().put("schema_version", 1); }
    private static ObjectNode emptyEndpoints() { ObjectNode doc = document(); doc.putArray("endpoints"); return doc; }
    private static void refs(ObjectNode node) { node.putArray("evidence_refs").add("reviewed-pair"); }
    private void evidence(ObjectNode document) throws Exception {
        document.putArray("evidence").addObject().put("id", "reviewed-pair").put("path", project.relativize(review).toString())
                .put("sha256", Data.fingerprint(review));
    }
    private static void register(ArrayNode array, String repo, String serviceId, String raw) {
        ObjectNode service = array.addObject().put("repo_id", repo).put("module_id", "main").put("service_id", serviceId).put("status", "verified");
        ArrayNode names = service.putArray("microservice_names_raw"); if (raw != null) names.add(raw); refs(service);
    }
    private static JsonNode firstBinding(JsonNode discovered) { return discovered.path("bindings").get(0); }
    private static JsonNode endpoint(JsonNode discovered, String id) {
        for (JsonNode endpoint : discovered.path("endpoints")) if (endpoint.path("id").asText().equals(id)) return endpoint;
        throw new AssertionError("Missing endpoint " + id);
    }
    private ObjectNode replacement(ObjectNode generated) throws Exception {
        JsonNode binding = firstBinding(generated);
        ObjectNode patch = document();
        ObjectNode replace = patch.putArray("overrides").addObject().put("id", "reviewed-fix").put("action", "replace_targets")
                .put("binding_id", binding.path("id").asText()).put("expected_source_fingerprint", binding.path("source_fingerprint").asText())
                .put("reason", "Independently reviewed synthetic known pair");
        JsonNode targets = binding.path("targets").isEmpty() ? binding.path("candidate_targets") : binding.path("targets");
        replace.set("targets", targets.deepCopy()); refs(replace); evidence(patch); return patch;
    }
}

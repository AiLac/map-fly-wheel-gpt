package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ScanServiceTest {
    @TempDir Path temp;

    @Test void discoversLiteralProviderUrlOnServiceLayerAndKeepsSourceEvidence() throws Exception {
        JsonNode facts = fixture("flow");
        assertEquals(1, facts.path("endpoints").size());
        JsonNode endpoint = facts.path("endpoints").get(0);
        assertEquals("demo.GatewayService", endpoint.path("class_name").asText());
        assertEquals("/map/search/v1/textsearch/searchByText", endpoint.path("http").path("path").asText());
        assertEquals("POST", endpoint.path("http").path("method").asText());
        assertEquals("declared_spring_mapping", endpoint.path("exposure").asText());
        assertEquals("not_evaluated", endpoint.path("runtime_registration").asText());
        assertFalse(facts.path("coverage").path("business_flow_complete").asBoolean());
        for (JsonNode evidence : facts.path("evidence")) {
            Path source = Path.of(evidence.path("source").path("path").asText());
            assertEquals(Data.fingerprint(source), evidence.path("source").path("sha256").asText());
            assertTrue(evidence.path("source").path("start_line").asInt() > 0);
        }
        Set<String> evidenceIds = values(facts.path("evidence"), "id");
        assertTrue(evidenceIds.containsAll(strings(endpoint.path("evidence_refs"))));
    }

    @Test void earlyReturnsConstrainLaterCallsWhileOrdinaryBranchesAreBothRetained() throws Exception {
        JsonNode facts = fixture("flow");
        JsonNode search = call(facts, "route", "search(query)");
        assertHasGuard(facts, search, "query == null", "false");
        assertHasGuard(facts, search, "cache", "false");
        JsonNode cached = call(facts, "route", "cached(query)");
        assertHasGuard(facts, cached, "query == null", "false");
        assertHasGuard(facts, cached, "cache", "true");
        JsonNode rejected = call(facts, "route", "reject()");
        assertHasGuard(facts, rejected, "query == null", "true");
        assertHasGuard(facts, call(facts, "conditional", "fromA()"), "preferA", "true");
        assertHasGuard(facts, call(facts, "conditional", "fromB()"), "preferA", "false");
        assertHasGuard(facts, call(facts, "conditionalCall", "check()"), "enabled", "true");
        for (JsonNode condition : facts.path("conditions"))
            assertEquals("not_evaluated", condition.path("feasibility").asText());
    }

    @Test void deferredLambdaDoesNotBecomeAnImmediateCallAndNestedTypeCallsStayOutOfOwner() throws Exception {
        JsonNode facts = fixture("flow");
        JsonNode deferred = call(facts, "deferred", "audit()");
        assertEquals("deferred_lambda", deferred.path("execution_context").asText());
        assertEquals("unresolved", deferred.path("invocation_timing").asText());
        assertEquals("method_body", call(facts, "deferred", "register(job)").path("execution_context").asText());
        List<JsonNode> scoped = callsIn(facts, "scoped");
        assertEquals(List.of("audit()"), scoped.stream().map(c -> c.path("expression").asText()).toList());
        assertTrue(hasDiagnostic(facts, "LOCAL_OR_ANONYMOUS_TYPE_NOT_EXPANDED"));
        assertFalse(values(facts.path("classes"), "class_name").contains("demo.GatewayService.Local"));
    }

    @Test void rpcFieldIdentitySurvivesAnInterfaceImplementingWrapperWithoutFalseRecursion() throws Exception {
        JsonNode facts = fixture("rpc");
        JsonNode owner = find(facts.path("classes"), "class_name", "demo.TsInnerClient");
        JsonNode field = find(owner.path("fields"), "name", "innerTsRpcService");
        JsonNode annotation = find(field.path("annotations"), "full_name", "example.rpc.RpcReference");
        assertEquals("MapSiteService:MapSearchTextSearchService", annotation.path("values").path("microserviceName").asText());
        assertEquals("tsRpc", annotation.path("values").path("schemaId").asText());
        JsonNode method = find(facts.path("methods"), "class_name", "demo.TsInnerClient", "method_name", "searchByText");
        JsonNode rpcCall = find(facts.path("calls"), "caller_method_id", method.path("id").asText());
        assertEquals("innerTsRpcService", rpcCall.path("receiver_field").asText());
        assertFalse(rpcCall.path("target_signature").asText().startsWith("demo.TsInnerClient."));
        if (rpcCall.path("resolution").asText().equals("resolved")) {
            assertTrue(rpcCall.path("target_signature").asText().startsWith("demo.IInnerTsRpcService."));
            assertEquals("declaration_only", rpcCall.path("dispatch").asText());
        }
        assertFalse(rpcCall.has("rpc_target"));
        JsonNode shadowMethod = find(facts.path("methods"), "method_name", "localShadow");
        assertTrue(find(facts.path("calls"), "caller_method_id", shadowMethod.path("id").asText()).path("receiver_field").isNull());
        JsonNode missingBody = call(facts, "demo.TsSearchRpcService", "searchByText", "search(request)");
        assertEquals("unresolved", missingBody.path("resolution").asText());
        assertTrue(missingBody.path("target_signature").isNull());
        assertEquals("/ts-rpc/searchByText", facts.path("endpoints").get(0).path("http").path("path").asText());
    }

    @Test void sameSimpleNameFromDifferentPackageDoesNotImpersonateSpring() throws Exception {
        Path root = source("fake", "Fake.java", """
                package demo;
                import example.notSpring.PostMapping;
                class Fake { @PostMapping("/not-spring") void run() {} }
                """);
        JsonNode facts = scan(root);
        assertTrue(facts.path("endpoints").isEmpty());
        JsonNode annotation = facts.path("methods").get(0).path("annotations").get(0);
        assertEquals("example.notSpring.PostMapping", annotation.path("full_name").asText());
    }

    @Test void anUnresolvedClassMappingCannotLoseItsPrefixAndCreateAFalseUrl() throws Exception {
        Path root = source("wildcard", "Unresolved.java", """
                package demo;
                import org.springframework.web.bind.annotation.*;
                @RequestMapping("/unknown-prefix")
                class Unresolved {
                    @org.springframework.web.bind.annotation.PostMapping("/suffix") void run() {}
                }
                """);
        JsonNode facts = scan(root);
        assertTrue(facts.path("endpoints").isEmpty());
        assertTrue(hasDiagnostic(facts, "ANNOTATION_NAME_UNRESOLVED"));
    }

    @Test void dynamicPrefixesAndWildcardCombinationAreExplicitGaps() throws Exception {
        Path root = source("dynamic", "Dynamic.java", """
                package demo;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                @RequestMapping("${entry.prefix}")
                class Dynamic { @PostMapping("run") void run() {} }
                @RequestMapping("/prefix/**")
                class Pattern { @PostMapping("run") void run() {} }
                """);
        JsonNode facts = scan(root);
        assertTrue(facts.path("endpoints").isEmpty());
        assertTrue(hasDiagnostic(facts, "MAPPING_PATH_UNRESOLVED"));
        assertTrue(hasDiagnostic(facts, "MAPPING_PATTERN_COMBINATION_UNSUPPORTED"));
    }

    @Test void combinesDeclaredPathsAndSpringHttpMethodUnionWithoutDuplicateEndpoints() throws Exception {
        Path root = source("routes", "Routes.java", """
                package demo;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                @RequestMapping(path = {"/a/", "/a/", "/b"}, method = RequestMethod.GET)
                class Routes {
                    @RequestMapping(path = {"x", "/y"}, method = RequestMethod.POST) void run() {}
                }
                """);
        JsonNode facts = scan(root);
        assertEquals(8, facts.path("endpoints").size());
        assertEquals(Set.of("/a/x", "/a/y", "/b/x", "/b/y"), nestedValues(facts.path("endpoints"), "http", "path"));
        assertEquals(Set.of("GET", "POST"), nestedValues(facts.path("endpoints"), "http", "method"));
        assertEquals(8, values(facts.path("endpoints"), "id").size());
    }

    @Test void ambiguousUrlsAndMultipleMappingAnnotationsNeedReview() throws Exception {
        Path root = source("ambiguous", "Routes.java", """
                package demo;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                class Routes {
                    @PostMapping(path = "/search", params = "mode=a") void a() {}
                    @PostMapping(path = "/search", params = "mode=b") void b() {}
                    @PostMapping("/one") @RequestMapping("/two") void unsupported() {}
                }
                """);
        JsonNode facts = scan(root);
        assertEquals(2, facts.path("endpoints").size());
        assertTrue(hasDiagnostic(facts, "ROUTE_CANDIDATES_REQUIRE_DISAMBIGUATION"));
        assertTrue(hasDiagnostic(facts, "MULTIPLE_MAPPING_ANNOTATIONS"));
        for (JsonNode endpoint : facts.path("endpoints")) {
            assertEquals("requires_disambiguation", endpoint.path("route_ambiguity").asText());
            assertTrue(endpoint.path("mapping_attributes").path("method").has("params"));
        }
    }

    @Test void sourceCallIdentitySurvivesLineShiftsButEvidenceTracksNewBytes() throws Exception {
        Path root = source("stable", "Stable.java", "class Stable { void a() { b(); } void b() {} }");
        JsonNode before = scan(root);
        Path source = root.resolve("Stable.java");
        Files.writeString(source, "// new explanatory comment\n\n" + Files.readString(source));
        JsonNode after = scan(root);
        assertEquals(values(before.path("classes"), "id"), values(after.path("classes"), "id"));
        assertEquals(values(before.path("methods"), "id"), values(after.path("methods"), "id"));
        assertEquals(values(before.path("calls"), "id"), values(after.path("calls"), "id"));
        assertNotEquals(values(before.path("evidence"), "id"), values(after.path("evidence"), "id"));
    }

    @Test void syntaxErrorsNeverProduceConfirmedPartialAstFacts() throws Exception {
        Path root = source("broken", "Broken.java", "class Broken { void missing( { }");
        JsonNode facts = scan(root);
        assertEquals(1, facts.path("source_file_count").asInt());
        assertEquals(0, facts.path("parsed_file_count").asInt());
        assertTrue(facts.path("classes").isEmpty());
        assertTrue(hasDiagnostic(facts, "JAVA_PARSE_ERROR"));
    }

    @Test void duplicateClassDefinitionsRequireSourceRootCorrectionInsteadOfPickingTheFirst() throws Exception {
        Path first = source("duplicate-a", "Same.java", """
                package demo;
                class Same { @org.springframework.web.bind.annotation.PostMapping("/a") void a() {} }
                """);
        Path second = source("duplicate-b", "Same.java", """
                package demo;
                class Same { @org.springframework.web.bind.annotation.PostMapping("/b") void b() {} }
                """);
        JsonNode facts = ScanService.scan("repo", "module", List.of(first, second), null);
        assertTrue(hasDiagnostic(facts, "DUPLICATE_CLASS"));
        assertTrue(facts.path("endpoints").isEmpty());
        assertTrue(facts.path("classes").isEmpty());
    }

    @Test void perModuleClasspathDoesNotLeakDependencyVersionsAcrossScans() throws Exception {
        Path root = source("consumer", "Consumer.java", """
                import demo.dependency.Api;
                class Consumer { void invoke() { Api.oldMethod(); } }
                """);
        Path jarA = dependencyJar("dependency-a", "oldMethod"), jarB = dependencyJar("dependency-b", "newMethod");
        Path cpA = temp.resolve("module-a-classpath.txt"), cpB = temp.resolve("module-b-classpath.txt");
        Files.writeString(cpA, jarA.toString(), StandardCharsets.UTF_8);
        Files.writeString(cpB, jarB.toString(), StandardCharsets.UTF_8);
        JsonNode first = ScanService.scan("repo", "module-a", List.of(root), cpA);
        JsonNode second = ScanService.scan("repo", "module-b", List.of(root), cpB);
        assertEquals("resolved", first.path("calls").get(0).path("resolution").asText());
        assertEquals("demo.dependency.Api.oldMethod()", first.path("calls").get(0).path("target_signature").asText());
        assertEquals("unresolved", second.path("calls").get(0).path("resolution").asText());
        assertTrue(second.path("calls").get(0).path("target_signature").isNull());
        assertTrue(hasDiagnostic(second, "CALL_TARGET_UNRESOLVED"));
    }

    @Test void executeUsesProjectRelativeEvidenceAndEnforcesOutputDirectoryContract() throws Exception {
        Path root = source("repo with spaces", "Local.java", "class Local { void run() {} }");
        List<String> args = List.of("--repo-id", "repo", "--module-id", "module", "--source-root",
                temp.relativize(root).toString(), "--out", "docs/super-business-flow/evidence/scan.json");
        JsonNode facts = ScanService.execute(temp, args);
        assertTrue(Files.exists(temp.resolve("docs/super-business-flow/evidence/scan.json")));
        assertEquals("repo with spaces/Local.java", facts.path("evidence").get(0).path("source").path("path").asText());
        Path template = temp.resolve("docs/super-business-flow-tookit/templates/facts.json");
        Files.createDirectories(template.getParent()); Files.writeString(template, "preserved template");
        assertThrows(IllegalArgumentException.class, () -> ScanService.execute(temp,
                List.of("--repo-id", "repo", "--module-id", "module", "--source-root", root.toString(), "--out", template.toString())));
        assertEquals("preserved template", Files.readString(template));
        assertThrows(IllegalArgumentException.class, () -> ScanService.execute(temp,
                List.of("--repo-id", "repo", "--module-id", "module", "--source-root", root.toString(), "--out", "forbidden.json")));
        assertThrows(IllegalArgumentException.class, () -> ScanService.execute(temp,
                List.of("--repo-id", "repo", "--module-id", "module", "--source-root")));
    }

    private JsonNode fixture(String name) throws Exception {
        return scan(Path.of(getClass().getResource("/scanner/" + name).toURI()));
    }
    private JsonNode scan(Path root) throws Exception { return ScanService.scan("repo", "module", List.of(root), null); }
    private Path source(String directory, String file, String content) throws Exception {
        Path root = temp.resolve(directory); Files.createDirectories(root);
        Files.writeString(root.resolve(file), content, StandardCharsets.UTF_8); return root;
    }
    private Path dependencyJar(String directory, String methodName) throws Exception {
        Path root = source(directory, "Api.java", "package demo.dependency; public class Api { public static void " + methodName + "() {} }");
        Path classes = root.resolve("classes"); Files.createDirectories(classes);
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "Run scanner tests using a full JDK");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, OutputStream.nullOutputStream(), OutputStream.nullOutputStream(),
                "-d", classes.toString(), root.resolve("Api.java").toString()));
        Path jar = root.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("demo/dependency/Api.class"));
            Files.copy(classes.resolve("demo/dependency/Api.class"), output); output.closeEntry();
        }
        return jar;
    }
    private static JsonNode call(JsonNode facts, String method, String expression) {
        return callsIn(facts, method).stream().filter(c -> c.path("expression").asText().equals(expression)).findFirst().orElseThrow();
    }
    private static JsonNode call(JsonNode facts, String className, String method, String expression) {
        JsonNode owner = find(facts.path("methods"), "class_name", className, "method_name", method);
        return find(facts.path("calls"), "caller_method_id", owner.path("id").asText(), "expression", expression);
    }
    private static List<JsonNode> callsIn(JsonNode facts, String method) {
        String id = find(facts.path("methods"), "method_name", method).path("id").asText();
        List<JsonNode> results = new ArrayList<>();
        for (JsonNode call : facts.path("calls")) if (call.path("caller_method_id").asText().equals(id)) results.add(call);
        return results;
    }
    private static JsonNode find(JsonNode values, String... keyValues) {
        outer: for (JsonNode value : values) {
            for (int i = 0; i < keyValues.length; i += 2) if (!value.path(keyValues[i]).asText().equals(keyValues[i + 1])) continue outer;
            return value;
        }
        throw new AssertionError("Missing fact: " + List.of(keyValues));
    }
    private static boolean hasDiagnostic(JsonNode facts, String code) { return values(facts.path("diagnostics"), "code").contains(code); }
    private static Set<String> values(JsonNode values, String key) { Set<String> result = new HashSet<>(); for (JsonNode value : values) result.add(value.path(key).asText()); return result; }
    private static Set<String> nestedValues(JsonNode values, String key, String nested) { Set<String> result = new HashSet<>(); for (JsonNode value : values) result.add(value.path(key).path(nested).asText()); return result; }
    private static Set<String> strings(JsonNode values) { Set<String> result = new HashSet<>(); values.forEach(v -> result.add(v.asText())); return result; }
    private static void assertHasGuard(JsonNode facts, JsonNode call, String expression, String outcome) {
        Set<String> ids = strings(call.path("condition_refs"));
        for (JsonNode condition : facts.path("conditions"))
            if (ids.contains(condition.path("id").asText()) && condition.path("expression").asText().equals(expression)
                    && condition.path("outcome").asText().equals(outcome)) return;
        fail("Expected guard " + expression + " -> " + outcome + " for " + call.path("expression"));
    }
}

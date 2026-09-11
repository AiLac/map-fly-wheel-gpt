package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicationServiceTest {
    @TempDir Path project;
    private JsonNode capture() throws Exception {
        Path file = project.resolve("repos/search/src/SearchService.java");
        Data.writeText(file, "class SearchService {\n  String search(String text) {\n    if (text.isBlank()) return \"empty\";\n    return text;\n  }\n}\n");
        return PublicationService.evidence(project,List.of("--source",file.toString(),"--symbol","SearchService#search(java.lang.String)","--line-start","2","--line-end","5"));
    }
    private ObjectNode graph(JsonNode evidence) {
        ObjectNode graph = Data.object().put("schema_version",1).put("scenario_id","text-search").put("title","文本检索").put("overview","合成夹具：空文本返回，其他文本继续处理。");
        graph.set("entry", Data.object().put("class_name","SearchService"));
        ArrayNode refs = Data.array().add(evidence.path("id").asText());
        graph.set("evidence",Data.array().add(evidence));
        ObjectNode start = Data.object().put("id","start").put("kind","entry").put("label","文本输入");start.set("evidence_refs",refs);
        ObjectNode end = Data.object().put("id","empty").put("kind","return").put("label","空文本返回");end.set("evidence_refs",refs);
        graph.set("nodes",Data.array().add(start).add(end));
        ObjectNode condition=Data.object().put("id","blank").put("expression","text.isBlank()");condition.set("evidence_refs",refs);
        graph.set("conditions",Data.array().add(condition));
        ObjectNode edge=Data.object().put("id","empty-return").put("from","start").put("to","empty").put("kind","control");
        edge.set("condition_refs",Data.array().add("blank"));edge.set("evidence_refs",refs);graph.set("edges",Data.array().add(edge));
        ObjectNode coverage=Data.object().put("status","partial");coverage.set("open_questions",Data.array());coverage.set("frontier",Data.array().add("非空文本后续范围待分析"));coverage.set("accepted_exclusions",Data.array());graph.set("coverage",coverage);
        return graph;
    }
    @Test void partialPublicationPreservesBranchAndDurableLinksAndRequiresRevision() throws Exception {
        JsonNode evidence=capture();ObjectNode graph=graph(evidence);Path input=project.resolve("input.json");Data.write(input,graph);
        JsonNode manifest=PublicationService.publish(project,List.of("--graph",input.toString(),"--scenario-id","text-search"));
        Path output=project.resolve(manifest.path("knowledge_path").asText());
        assertTrue(Files.readString(output.resolve("flow.md")).contains("text.isBlank()"));
        assertTrue(Files.readString(output.resolve("overview.md")).contains("partial"));
        assertTrue(Files.readString(output.resolve("code-links.md")).contains("source.txt)"));
        assertTrue(Files.readString(output.resolve("code-links.md")).contains("SearchService.java)"));
        assertThrows(IllegalArgumentException.class,()->PublicationService.publish(project,List.of("--graph",input.toString(),"--scenario-id","text-search")));
        assertEquals(2,PublicationService.publish(project,List.of("--graph",input.toString(),"--scenario-id","text-search","--expected-revision","1")).path("revision").asInt());
    }
    @Test void completionCannotHideFrontierAndNeedsReviewEvidence() throws Exception {
        ObjectNode graph=graph(capture());((ObjectNode)graph.path("coverage")).put("status","complete");
        assertThrows(IllegalArgumentException.class,()->PublicationService.validateGraph(project,graph));
        ((ArrayNode)graph.path("coverage").path("frontier")).removeAll();
        assertThrows(IllegalArgumentException.class,()->PublicationService.validateGraph(project,graph));
    }
    @Test void staleDurableCopiesAndDanglingGuardsAreRejected() throws Exception {
        JsonNode evidence=capture();ObjectNode graph=graph(evidence);
        ((ObjectNode)graph.path("edges").get(0)).set("condition_refs",Data.array().add("invented"));
        assertThrows(IllegalArgumentException.class,()->PublicationService.validateGraph(project,graph));
        ObjectNode valid=graph(evidence);
        Data.writeText(project.resolve(evidence.path("path").asText()),"changed");
        assertThrows(IllegalArgumentException.class,()->PublicationService.validateGraph(project,valid));
    }
    @Test void candidateRpcCannotBecomeFactEvenWithTarget() throws Exception {
        JsonNode evidence=capture();ObjectNode graph=graph(evidence);
        ((ObjectNode)graph.path("edges").get(0)).put("kind","rpc").put("resolution","candidate");
        assertThrows(IllegalArgumentException.class,()->PublicationService.validateGraph(project,graph));
    }
    @Test void deletedTemporaryOriginalDoesNotDestroyPublishedEvidence() throws Exception {
        Path original=project.resolve(".temp/review.md");Data.writeText(original,"Reviewed scope independently; unresolved branch remains.\n");
        JsonNode evidence=PublicationService.evidence(project,List.of("--source",original.toString()));Files.delete(original);
        PublicationService.validateGraph(project,graph(evidence));
        assertEquals(evidence.path("sha256").asText(),Data.fingerprint(project.resolve(evidence.path("path").asText())));
    }
    @Test void duplicateYamlKeysAreRejected() throws Exception {
        Path input=project.resolve("duplicate.yaml");Data.writeText(input,"schema_version: 1\nschema_version: 2\n");
        assertThrows(Exception.class,()->Data.read(input));
    }
    @Test void rpcPublicationChecksCallerAndPreservesEffectiveMappingRevision() throws Exception {
        MappingServiceTest fixture=new MappingServiceTest();fixture.project=project;fixture.fixtures();
        JsonNode generated=MappingService.discover(project,fixture.facts,fixture.rules,fixture.services);
        ObjectNode extra=Data.object().put("schema_version",1);extra.set("endpoints",Data.array());
        JsonNode effective=MappingService.resolve(project,generated,extra,List.of());
        Path mapping=project.resolve("docs/business-flow/mappings/effective/test.yaml");Data.write(mapping,effective);String hash=Data.fingerprint(mapping);
        JsonNode binding=effective.path("bindings").get(0);JsonNode ev=PublicationService.evidence(project,List.of("--source","repos/caller/demo/TsInnerClient.java"));ObjectNode graph=graph(ev);
        ObjectNode from=(ObjectNode)graph.path("nodes").get(0),to=(ObjectNode)graph.path("nodes").get(1),edge=(ObjectNode)graph.path("edges").get(0);
        from.put("callsite_id","wrong-callsite");to.put("endpoint_id",binding.path("targets").get(0).path("endpoint_id").asText());
        edge.put("kind","rpc").put("binding_id",binding.path("id").asText());edge.set("mapping_ref",Data.object().put("path","docs/business-flow/mappings/effective/test.yaml").put("sha256",hash));
        assertThrows(IllegalArgumentException.class,()->PublicationService.validateGraph(project,graph));
        from.put("callsite_id",binding.path("caller").path("callsite_id").asText());Path file=project.resolve("graph.json");Data.write(file,graph);
        JsonNode publication=PublicationService.publish(project,List.of("--graph",file.toString(),"--scenario-id","text-search"));
        Path output=project.resolve(publication.path("knowledge_path").asText());JsonNode snapshots=Data.read(output.resolve("mapping-snapshots.json"));
        Data.writeText(mapping,"schema_version: 1\nlayer: effective\nbindings: []\n");
        assertEquals(hash,Data.fingerprint(output.resolve(snapshots.path("mappings").get(0).path("snapshot_path").asText())));
        assertThrows(IllegalArgumentException.class,()->PublicationService.validateGraph(project,graph));
    }
}

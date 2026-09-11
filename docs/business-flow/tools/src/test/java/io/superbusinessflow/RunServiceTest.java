package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunServiceTest {
    @TempDir Path project;
    private void configure(int modules) throws Exception {
        ArrayNode all=Data.array();
        for(int i=0;i<modules;i++){
            Path source=project.resolve("repo/module"+i+"/src/A.java");Data.writeText(source,"class A { int x() { return 1; } }\n");
            ObjectNode m=Data.object().put("id","m"+i);m.set("source_roots",Data.array().add("module"+i+"/src"));all.add(m);
        }
        ObjectNode repo=Data.object().put("id","repo").put("path","repo");repo.set("modules",all);
        ObjectNode cfg=Data.object().put("schema_version",1);cfg.set("repos",Data.array().add(repo));
        Data.write(project.resolve("docs/business-flow/project/repos.yaml"),cfg);
    }
    private JsonNode command(String... args) throws Exception {return RunService.execute(project,List.of(args));}
    private JsonNode start(String... args) throws Exception {configure(2);return command(args);}
    private JsonNode claim(String id) throws Exception {return command("task","claim","--run-id",id,"--worker","agent");}
    private ObjectNode result(JsonNode claimed,String status) throws Exception {
        JsonNode task=claimed.path("task");Path dir=Path.of(claimed.path("output_directory").asText());
        Path artifact=dir.resolve("analysis.md");Data.writeText(artifact,"Scoped source findings with explicit remaining coverage.\n");
        ObjectNode r=Data.object().put("schema_version",1).put("run_id",task.path("run_id").asText()).put("snapshot_id",task.path("snapshot_id").asText())
            .put("task_id",task.path("id").asText()).put("lease_token",task.path("lease").path("token").asText()).put("status",status).put("summary","scoped review");
        r.set("output_files",Data.array().add(Data.object().put("path","analysis.md").put("sha256",Data.fingerprint(artifact))));
        r.set("questions",Data.array());r.set("frontier",Data.array());r.set("evidence_refs",Data.array().add("source-fact"));return r;
    }
    private JsonNode finish(JsonNode claimed,ObjectNode result,boolean checkpoint) throws Exception {
        Path file=Path.of(claimed.path("output_directory").asText()).resolve("result.json");Data.write(file,result);
        JsonNode t=claimed.path("task");return command("task",checkpoint?"checkpoint":"finish","--run-id",t.path("run_id").asText(),"--task-id",t.path("id").asText(),"--worker","agent","--lease-token",t.path("lease").path("token").asText(),checkpoint?"--file":"--result",file.toString());
    }
    @Test void selectorsAreDeterministicAndInvalidCombinationsRejected() throws Exception {
        String id=start("--class","example.SearchService","--url","/a?x=1","/b","--url","/a").path("run_id").asText();
        JsonNode state=command("task","list","--run-id",id);JsonNode input=state.path("snapshot").path("input");
        assertEquals(List.of("/a","/b"),Data.JSON.convertValue(input.path("urls"),List.class));assertTrue(input.path("class_and_urls_are_intersection").asBoolean());
        assertThrows(IllegalArgumentException.class,()->command("--all","--class","A"));
        assertThrows(IllegalArgumentException.class,()->command("--resume",id,"--url","/a"));
        assertThrows(IllegalArgumentException.class,()->command("--url"));
        assertThrows(IllegalArgumentException.class,()->command("--resume",id,"--max-task-tokens","1"));
        assertFalse(command().has("run_id"));
    }
    @Test void globalConcurrencyAndDependenciesControlClaims() throws Exception {
        String id=start("--all","--max-parallel-tasks","1").path("run_id").asText();JsonNode first=claim(id);
        assertTrue(first.path("claimed").asBoolean());assertEquals("global_concurrency_limit",claim(id).path("reason").asText());
        finish(first,result(first,"completed"),false);JsonNode second=claim(id);assertEquals("inventory",second.path("task").path("phase").asText());
        finish(second,result(second,"completed"),false);assertEquals("frameworks",claim(id).path("task").path("phase").asText());
    }
    @Test void phaseLimitAppliesUnderHigherGlobalLimit() throws Exception {
        String id=start("--all","--max-parallel-tasks","3","--phase-limit","inventory=1").path("run_id").asText();
        assertTrue(claim(id).path("claimed").asBoolean());assertFalse(claim(id).path("claimed").asBoolean());
    }
    @Test void checkpointMustBeResumedAndCannotCompleteWithFrontier() throws Exception {
        String id=start("--url","/a","--max-parallel-tasks","1").path("run_id").asText();JsonNode first=claim(id);ObjectNode r=result(first,"completed");r.withArray("frontier").add("next method");
        assertThrows(IllegalArgumentException.class,()->finish(first,r,false));finish(first,r,true);
        JsonNode resumed=claim(id);assertEquals(first.path("task").path("id"),resumed.path("task").path("id"));
        assertThrows(IllegalArgumentException.class,()->finish(resumed,r,false));
        finish(resumed,result(resumed,"completed"),false);assertTrue(command("task","list","--run-id",id).path("frontier").isEmpty());
    }
    @Test void unknownQuestionNeedsActualUserDecisionAndRequeues() throws Exception {
        String id=start("--all").path("run_id").asText();JsonNode first=claim(id);ObjectNode r=result(first,"blocked");r.withArray("questions").add(Data.object().put("id","q-rpc").put("question","Which service registration owns this raw name?"));finish(first,r,false);
        ObjectNode answer=Data.object().put("schema_version",1).put("question_id","q-rpc").put("answer","Use service-a per deployment evidence").put("answered_by","programmer").put("source","agent");Path file=project.resolve("answer.json");Data.write(file,answer);
        assertThrows(IllegalArgumentException.class,()->command("task","answer","--run-id",id,"--file",file.toString()));
        answer.put("source","user");Data.write(file,answer);command("task","answer","--run-id",id,"--file",file.toString());
        assertEquals(first.path("task").path("id"),claim(id).path("task").path("id"));
    }
    @Test void codeChangesInvalidateCompletedWorkWhileSchedulingOverrideDoesNot() throws Exception {
        String id=start("--all").path("run_id").asText();JsonNode first=claim(id);finish(first,result(first,"completed"),false);
        assertFalse(command("--resume",id,"--max-parallel-tasks","1").path("invalidated").asBoolean());
        Data.writeText(project.resolve("repo/module0/src/A.java"),"class A { int x() { return 2; } }\n");
        assertThrows(IllegalArgumentException.class,()->claim(id));assertTrue(command("--resume",id).path("invalidated").asBoolean());
        for(JsonNode t:command("task","list","--run-id",id).path("tasks"))assertEquals("pending",t.path("status").asText());
    }
    @Test void relativeMultilineClasspathChangesAreObserved() throws Exception {
        configure(1);Path cp=project.resolve("repo/build/deps.cp");Data.writeText(cp,"one.jar\ntwo.jar\n");Data.writeText(cp.resolveSibling("one.jar"),"one");Data.writeText(cp.resolveSibling("two.jar"),"two");
        Path cfg=project.resolve("docs/business-flow/project/repos.yaml");JsonNode repos=Data.read(cfg);((ObjectNode)repos.path("repos").get(0).path("modules").get(0)).put("classpath_file","build/deps.cp");Data.write(cfg,repos);
        String id=command("--all").path("run_id").asText();Data.writeText(cp.resolveSibling("two.jar"),"changed");assertTrue(command("--resume",id).path("invalidated").asBoolean());
    }
    @Test void dynamicTasksCannotIntroduceCyclesOrBackwardsPhaseDependencies() throws Exception {
        String id=start("--all").path("run_id").asText();ObjectNode addition=Data.object().put("schema_version",1);ObjectNode t=Data.object().put("id","wrong").put("phase","inventory").put("objective","invalid later-phase dependency");t.set("dependencies",Data.array().add("knowledge"));t.set("scope",Data.object());addition.set("tasks",Data.array().add(t));Path file=project.resolve("tasks.json");Data.write(file,addition);
        assertThrows(IllegalArgumentException.class,()->command("task","add","--run-id",id,"--file",file.toString()));
    }
    @Test void memoryPinsImmutableVersionAndReadTimestampsDoNotInvalidateRun() throws Exception {
        configure(1);Path evidence=project.resolve("docs/business-flow/evidence/source.txt");Data.writeText(evidence,"Known scoped fact.\n");
        ObjectNode entry=Data.object().put("schema_version",1).put("id","navigation").put("type","navigation").put("statement","Known scoped fact").put("state","verified");entry.set("scope",Data.array().add("project:*"));entry.set("source_refs",Data.array().add(Data.object().put("path","docs/business-flow/evidence/source.txt").put("sha256",Data.fingerprint(evidence))));
        Path input=project.resolve("memory.json");Data.write(input,entry);MemoryService.execute(project,List.of("put","--file",input.toString()));
        String id=command("--all").path("run_id").asText();Path current=project.resolve("docs/business-flow/memory/entries/navigation.md"),immutable=project.resolve("docs/business-flow/memory/versions/navigation/r000001.md");
        ObjectNode pin=Data.object().put("schema_version",1);ObjectNode ref=Data.object().put("id","navigation").put("revision",1).put("path",project.relativize(current).toString()).put("sha256",Data.fingerprint(current));pin.set("entries",Data.array().add(ref));Path file=project.resolve("pin.json");Data.write(file,pin);
        assertThrows(IllegalArgumentException.class,()->command("task","use-memory","--run-id",id,"--file",file.toString()));
        ref.put("path",project.relativize(immutable).toString()).put("sha256",Data.fingerprint(immutable));Data.write(file,pin);command("task","use-memory","--run-id",id,"--file",file.toString());
        MemoryService.execute(project,List.of("retrieve","--query","Known"));assertFalse(command("--resume",id).path("invalidated").asBoolean());
        Data.writeText(evidence,"Changed supporting evidence\n");assertTrue(command("--resume",id).path("invalidated").asBoolean());
    }
    @Test void stageAdoptionRequiresExactChangesAndInvalidatesSuccessors() throws Exception {
        configure(1);String id=command("--all").path("run_id").asText();JsonNode inventory=claim(id);finish(inventory,result(inventory,"completed"),false);JsonNode framework=claim(id);finish(framework,result(framework,"completed"),false);
        Path rules=project.resolve("docs/business-flow/frameworks/demo/rules.yaml");Data.writeText(rules,"schema_version: 1\nstatus: candidate\n");
        assertThrows(IllegalArgumentException.class,()->claim(id));JsonNode state=command("task","list","--run-id",id);
        ObjectNode adopt=Data.object().put("schema_version",1).put("expected_snapshot_id",state.path("snapshot").path("snapshot_id").asText()).put("phase","frameworks").put("reason","reviewed staged findings");adopt.set("evidence_refs",Data.array().add("rule-source"));adopt.set("changes",command("task","changes","--run-id",id).path("changes"));Path file=project.resolve("adopt.json");Data.write(file,adopt);
        JsonNode snapshot=command("task","adopt","--run-id",id,"--file",file.toString());JsonNode next=claim(id);assertEquals("mappings",next.path("task").path("phase").asText());assertEquals(snapshot.path("snapshot_id"),next.path("task").path("snapshot_id"));
    }
}

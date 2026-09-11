package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class Main {
    public static void main(String[] raw) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        try { System.out.println(Data.JSON.writeValueAsString(execute(Path.of("."), List.of(raw)))); }
        catch (Exception ex) {
            ObjectNode error = Data.object().put("schema_version", 1).put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            System.err.println(error); System.exit(ex instanceof IllegalArgumentException ? 2 : 1);
        }
    }
    public static JsonNode execute(Path cwd, List<String> raw) throws Exception {
        List<String> args = new ArrayList<>(raw); Path project = cwd.toAbsolutePath().normalize();
        int index = args.indexOf("--project");
        if (index >= 0) {
            Data.require(index + 1 < args.size(), "--project requires a path");
            project = Data.resolve(cwd, args.remove(index + 1)); args.remove(index);
            Data.require(!args.contains("--project"), "Repeated --project");
        }
        if (args.isEmpty() || args.equals(List.of("--help")) || args.equals(List.of("help"))) return help();
        String command = args.getFirst().startsWith("--") ? "run" : args.removeFirst();
        return switch (command) {
            case "run" -> RunService.execute(project, args);
            case "task" -> { args.addFirst("task"); yield RunService.execute(project, args); }
            case "scan" -> ScanService.execute(project, args);
            case "mappings" -> MappingService.execute(project, args);
            case "memory" -> MemoryService.execute(project, args);
            case "validate" -> ValidationService.execute(project, args);
            case "evidence" -> PublicationService.evidence(project, args);
            case "publish" -> PublicationService.publish(project, args);
            default -> throw new IllegalArgumentException("Unknown command: " + command + "; use --help");
        };
    }
    private static JsonNode help() {
        return Data.object().put("schema_version", 1).put("usage", "java -jar docs/super-business-flow-tookit/tools/target/business-flow-tools.jar [--project ROOT] [run] (--class FQCN [--url URL ...] | --url URL ... | --all | --resume RUN_ID)")
            .put("commands", "scan, mappings discover|resolve, task, memory, evidence, validate, publish")
            .put("guide", "docs/super-business-flow-tookit/README.md");
    }
}

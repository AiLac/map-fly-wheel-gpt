package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;
import java.nio.file.*;
import java.util.*;

public final class ValidationService {
    public static JsonNode execute(Path project, List<String> args) throws Exception {
        var options = Data.options(args, Set.of("--file", "--schema"));
        Path file = Data.resolve(project, Data.required(options, "--file"));
        Path schema = Data.resolve(project, Data.required(options, "--schema"));
        validate(Data.read(file), Data.read(schema));
        return Data.object().put("schema_version", 1).put("valid", true).put("file", file.toString());
    }
    public static void validate(JsonNode value, JsonNode schema) {
        var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema).validate(value);
        Data.require(errors.isEmpty(), "Schema validation failed: " + errors);
    }
}

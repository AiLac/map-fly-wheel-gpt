package io.superbusinessflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Evidence-backed, deliberately finite RPC extraction and three-layer mapping merge. */
public final class MappingService {
    private MappingService() {}

    public static JsonNode execute(Path project, List<String> args) throws Exception {
        Data.require(!args.isEmpty(), "mappings requires discover or resolve");
        String action = args.getFirst();
        Data.require(Set.of("discover", "resolve").contains(action), "Unknown mappings command: " + action);
        Set<String> allowed = action.equals("discover")
                ? Set.of("--facts", "--rules", "--services", "--out")
                : Set.of("--generated", "--endpoints", "--overrides", "--out");
        Map<String, List<Path>> options = new LinkedHashMap<>();
        Path root = project.toAbsolutePath().normalize();
        for (int i = 1; i < args.size(); i++) {
            String key = args.get(i);
            Data.require(allowed.contains(key), "Unknown mappings option: " + key);
            Data.require(i + 1 < args.size() && !args.get(i + 1).startsWith("--"), "Missing value for " + key);
            List<Path> values = options.computeIfAbsent(key, ignored -> new ArrayList<>());
            Data.require(key.equals("--overrides") || values.isEmpty(), "Duplicate option: " + key);
            values.add(root.resolve(args.get(++i)).normalize());
        }
        for (String key : allowed) if (!key.equals("--overrides"))
            Data.require(options.containsKey(key), "Missing required mappings option: " + key);
        Path out = options.get("--out").getFirst();
        String layer = action.equals("discover") ? "generated" : "effective";
        Data.require(out.startsWith(root.resolve(Data.DATA_ROOT + "/mappings/" + layer))
                        || out.startsWith(root.resolve(Data.RUN_ROOT)),
                "Mapping output must be in docs/super-business-flow/mappings/" + layer + "/ or the run directory");
        List<Path> inputs = options.entrySet().stream().filter(e -> !e.getKey().equals("--out"))
                .flatMap(e -> e.getValue().stream()).toList();
        for (Path input : inputs) {
            Data.require(Files.isRegularFile(input), "Mapping input not found: " + input);
            Data.require(!out.equals(input) && (!Files.exists(out) || !Files.isSameFile(out, input)),
                    "Mapping output may not overwrite an input layer: " + input);
        }
        try (var ignored = Data.lock(out.resolveSibling(out.getFileName() + ".lock"))) {
            Map<Path, String> versions = new LinkedHashMap<>();
            for (Path input : inputs) versions.put(input, Data.fingerprint(input));
            JsonNode result;
            if (action.equals("discover")) {
                JsonNode facts = Data.read(options.get("--facts").getFirst());
                ObjectNode rules = object(Data.read(options.get("--rules").getFirst()), "rules").deepCopy();
                ObjectNode services = object(Data.read(options.get("--services").getFirst()), "services").deepCopy();
                // Preserve the actual configuration revision, not only a manually typed revision label.
                addInputEvidence(root, options.get("--rules").getFirst(), rules, "rules");
                addInputEvidence(root, options.get("--services").getFirst(), services, "services");
                result = discover(root, facts, rules, services);
            } else {
                List<JsonNode> patches = new ArrayList<>();
                for (Path path : options.getOrDefault("--overrides", List.of())) patches.add(Data.read(path));
                result = resolve(root, Data.read(options.get("--generated").getFirst()),
                        Data.read(options.get("--endpoints").getFirst()), patches);
            }
            try (var publicationLock = Data.lock(root.resolve(Data.DATA_ROOT + "/.project.lock"))) {
                for (var input : versions.entrySet())
                    Data.require(Files.isRegularFile(input.getKey()) && input.getValue().equals(Data.fingerprint(input.getKey())),
                            "Mapping input changed while processing; rerun: " + input.getKey());
                Data.write(out, result);
            }
            return result;
        }
    }

    public static JsonNode discover(Path project, JsonNode facts, JsonNode rules, JsonNode serviceDocument) throws Exception {
        version(facts, "facts"); version(rules, "rules"); version(serviceDocument, "services");
        Evidence evidence = new Evidence(project);
        evidence.add(facts); evidence.add(rules); evidence.add(serviceDocument);
        evidence.validateFiles();
        Rule rule = new Rule(rules, evidence);
        Services services = new Services(serviceDocument, evidence);
        Map<String, ObjectNode> classes = index(facts, "classes");
        Map<String, ObjectNode> methods = index(facts, "methods");
        Map<String, ObjectNode> calls = index(facts, "calls");
        Map<String, ObjectNode> conditions = index(facts, "conditions");
        ObjectNode result = base("generated");
        ArrayNode diagnostics = (ArrayNode) result.get("diagnostics");
        if (facts.has("diagnostics")) diagnostics.addAll(array(facts, "diagnostics"));
        Map<String, ObjectNode> endpoints = new TreeMap<>();

        for (ObjectNode method : methods.values()) {
            ObjectNode owner = classes.get(method.path("class_id").asText());
            Data.require(owner != null, "Method refers to missing class_id: " + method.path("id").asText());
            JsonNode schemaAnnotation = annotation(owner, rule.providerAnnotation);
            if (schemaAnnotation == null) continue;
            List<JsonNode> exposures = annotations(method, rule.exposureAnnotations);
            if (exposures.isEmpty()) continue;
            JsonNode registered = services.forModule(owner);
            String serviceId = nullable(registered.path("service_id"));
            String schema = literal(schemaAnnotation, rule.providerSchema);
            String operation = operation(method, rule.providerOperation);
            ObjectNode endpoint = Data.object();
            endpoint.put("id", stable("rpc-endpoint", rule.id + "/" + scope(method) + "/" + method.path("id").asText()));
            endpoint.put("role", "provider");
            copy(endpoint, method, "repo_id", "module_id", "class_name", "method_signature", "method_id");
            endpoint.put("method_id", text(method, "id"));
            nullable(endpoint, "service_id", serviceId);
            ObjectNode rpc = endpoint.putObject("rpc");
            rpc.put("framework_id", rule.id).put("rule_revision", rule.revision);
            nullable(rpc, "schema_id", schema); nullable(rpc, "operation_id", operation);
            rpc.set("microservice_names_raw", registered.path("microservice_names_raw").isArray()
                    ? registered.path("microservice_names_raw").deepCopy() : Data.array());
            ArrayNode refs = unionRefs(owner, method, schemaAnnotation, registered);
            for (JsonNode exposure : exposures) appendUnique(refs, exposure.path("evidence_refs"));
            appendUnique(refs, rule.providerEvidence());
            endpoint.set("evidence_refs", refs);
            ArrayNode routes = endpoint.putArray("http_routes");
            for (JsonNode exposed : optionalArray(facts, "endpoints")) {
                if (method.path("id").asText().equals(exposed.path("method_id").asText()) && exposed.path("http").isObject()) {
                    if (!contains(routes, exposed.path("http"))) routes.add(exposed.path("http").deepCopy());
                    appendUnique(refs, exposed.path("evidence_refs"));
                }
            }
            if (routes.size() == 1) endpoint.set("http", routes.get(0).deepCopy());
            boolean verified = rule.providerVerified && services.verified(registered) && schema != null && operation != null
                    && evidence.hasSource(owner) && evidence.hasSource(method) && evidence.hasSource(schemaAnnotation)
                    && exposures.stream().allMatch(evidence::hasSource) && evidence.hasRefs(refs, true);
            endpoint.putObject("resolution").put("status", verified ? "confirmed" : "candidate")
                    .put("reason", verified ? "Verified provider rule, service identity and source declaration"
                            : "Provider operation, service identity, rule or source evidence requires confirmation");
            endpoints.put(endpoint.path("id").asText(), endpoint);
        }

        String snapshot = digest(Data.array().add(facts).add(rules).add(serviceDocument));
        Map<String, ObjectNode> bindings = new TreeMap<>();
        for (ObjectNode call : calls.values()) {
            ObjectNode method = methods.get(call.path("caller_method_id").asText());
            Data.require(method != null, "Call refers to missing caller_method_id: " + call.path("id").asText());
            ObjectNode owner = classes.get(method.path("class_id").asText());
            String fieldName = nullable(call.path("receiver_field"));
            if (fieldName == null) continue;
            JsonNode field = null;
            for (JsonNode candidate : optionalArray(owner, "fields")) {
                if (fieldName.equals(candidate.path("name").asText())) {
                    Data.require(field == null, "Duplicate receiver field in class: " + fieldName);
                    field = candidate;
                }
            }
            if (field == null) continue; // A field alias/inherited receiver needs its own adapter.
            JsonNode reference = annotation(field, rule.consumerAnnotation);
            if (reference == null) continue;
            JsonNode callerService = services.forModule(owner);
            String serviceRaw = literal(reference, rule.consumerService);
            String schema = literal(reference, rule.consumerSchema);
            String operation = operation(call, field, rule.consumerOperation);
            String remoteServiceId = services.serviceForRaw(serviceRaw);
            ObjectNode binding = Data.object();
            String id = stable("binding", rule.id + "/" + scope(method) + "/" + call.path("id").asText());
            binding.put("id", id).put("source_fingerprint", digest(Data.array().add(snapshot).add(id)));
            binding.putObject("framework").put("id", rule.id).put("rule_revision", rule.revision);
            ObjectNode caller = binding.putObject("caller");
            copy(caller, method, "repo_id", "module_id", "class_name", "method_signature");
            nullable(caller, "service_id", nullable(callerService.path("service_id")));
            caller.put("callsite_id", text(call, "id")).put("receiver_field", fieldName);
            nullable(caller, "interface_name", nullable(field.path("type")));
            nullable(caller, "invoked_method", nullable(call.path("invoked_method")));
            caller.set("evidence_refs", unionRefs(owner, method, field, reference, call));
            caller.set("condition_refs", optionalArray(call, "condition_refs").deepCopy());
            ObjectNode remote = binding.putObject("remote_identity");
            nullable(remote, "microservice_name_raw", serviceRaw);
            nullable(remote, "service_id", remoteServiceId);
            nullable(remote, "schema_id", schema); nullable(remote, "operation_id", operation);
            ArrayNode candidates = Data.array();
            if (serviceRaw != null || schema != null || operation != null) for (ObjectNode endpoint : endpoints.values()) {
                if (remoteServiceId != null && !remoteServiceId.equals(nullable(endpoint.path("service_id")))) continue;
                // A raw service which is definitely registered must never cross to another service.
                if (serviceRaw != null && !endpoint.path("rpc").path("microservice_names_raw").isEmpty()
                        && !hasText(endpoint.path("rpc").path("microservice_names_raw"), serviceRaw)) continue;
                if (differentKnown(schema, nullable(endpoint.path("rpc").path("schema_id")))) continue;
                if (differentKnown(operation, nullable(endpoint.path("rpc").path("operation_id")))) continue;
                candidates.addObject().put("endpoint_id", endpoint.path("id").asText()).putNull("condition_ref");
            }
            ArrayNode refs = unionRefs(owner, method, field, reference, call, callerService);
            appendUnique(refs, rule.allEvidence());
            JsonNode remoteRegistration = services.forRaw(serviceRaw);
            appendUnique(refs, remoteRegistration.path("evidence_refs"));
            boolean verified = rule.fullyVerified && services.verified(callerService)
                    && services.verified(remoteRegistration) && serviceRaw != null && remoteServiceId != null
                    && schema != null && operation != null && candidates.size() == 1
                    && evidence.hasSource(owner) && evidence.hasSource(method) && evidence.hasSource(field)
                    && evidence.hasSource(reference) && evidence.hasSource(call) && evidence.hasRefs(refs, true);
            if (verified) {
                JsonNode endpoint = endpoints.get(candidates.get(0).path("endpoint_id").asText());
                verified = endpoint.path("resolution").path("status").asText().equals("confirmed");
                appendUnique(refs, endpoint.path("evidence_refs"));
            }
            binding.set("targets", verified ? candidates : Data.array());
            binding.set("candidate_targets", verified ? Data.array() : candidates);
            ObjectNode resolution = binding.putObject("resolution");
            resolution.put("status", verified ? "confirmed" : "candidate").put("origin", "discovered");
            resolution.put("reason", verified ? "Unique service/schema/operation match under verified rules and contract"
                    : "Requires validated service/schema/operation, contract and source evidence; candidates=" + candidates.size());
            resolution.set("evidence_refs", refs);
            if (!verified) diagnostic(diagnostics, "RPC_BINDING_REQUIRES_CONFIRMATION", id, resolution.path("reason").asText(), refs);
            bindings.put(id, binding);
        }
        setValues(result, "bindings", bindings); setValues(result, "endpoints", endpoints);
        setValues(result, "conditions", conditions);
        result.set("services", optionalArray(serviceDocument, "services").deepCopy());
        result.set("evidence", evidence.array());
        result.put("snapshot_fingerprint", snapshot);
        validate(result, evidence);
        evidence.validateFiles();
        return result;
    }

    public static JsonNode resolve(Path project, JsonNode generated, JsonNode endpointDocument,
                                   List<JsonNode> overrideDocuments) throws Exception {
        version(generated, "generated mappings"); version(endpointDocument, "endpoint inventory");
        Data.require(!generated.path("layer").asText().equals("effective"), "--generated must not use the effective output as its source");
        Evidence evidence = new Evidence(project);
        evidence.add(generated); evidence.add(endpointDocument);
        for (JsonNode patch : overrideDocuments) { version(patch, "overrides"); evidence.add(patch); }
        evidence.validateFiles();
        Map<String, ObjectNode> endpoints = index(generated, "endpoints");
        merge(endpoints, index(endpointDocument, "endpoints"), "endpoint");
        Map<String, ObjectNode> bindings = index(generated, "bindings");
        Map<String, ObjectNode> conditions = index(generated, "conditions");
        merge(conditions, index(endpointDocument, "conditions"), "condition");
        Map<String, ObjectNode> overrides = new TreeMap<>();
        Set<String> touched = new HashSet<>();
        for (JsonNode document : overrideDocuments) {
            merge(conditions, index(document, "conditions"), "condition");
            for (var entry : index(document, "overrides").entrySet()) {
                ObjectNode patch = entry.getValue();
                Data.require(!overrides.containsKey(entry.getKey()), "Duplicate override id: " + entry.getKey());
                String action = text(patch, "action");
                Data.require(Set.of("add_endpoint", "add_binding", "replace_targets", "disable_binding").contains(action),
                        "Unsupported override action: " + action);
                text(patch, "reason");
                Data.require(evidence.hasRefs(patch.path("evidence_refs"), true), "Override requires current durable evidence: " + entry.getKey());
                String touch = action.equals("add_endpoint") ? "endpoint:" + text(patch.path("endpoint"), "id")
                        : "binding:" + (action.equals("add_binding") ? text(patch.path("binding"), "id") : text(patch, "binding_id"));
                Data.require(touched.add(touch), "Conflicting overrides touch the same record: " + touch);
                overrides.put(entry.getKey(), patch);
            }
        }
        // Adding endpoints before bindings makes the merge independent of patch-file order.
        for (ObjectNode patch : overrides.values()) if (patch.path("action").asText().equals("add_endpoint")) {
            ObjectNode endpoint = object(patch.path("endpoint"), "override endpoint").deepCopy();
            String id = text(endpoint, "id");
            Data.require(!endpoints.containsKey(id), "add_endpoint cannot replace existing endpoint: " + id);
            endpoint.set("evidence_refs", unionRefs(endpoint, patch));
            endpoints.put(id, endpoint);
        }
        for (ObjectNode patch : overrides.values()) {
            String action = patch.path("action").asText();
            if (action.equals("add_endpoint")) continue;
            ObjectNode binding;
            if (action.equals("add_binding")) {
                binding = object(patch.path("binding"), "override binding").deepCopy();
                String id = text(binding, "id");
                Data.require(!bindings.containsKey(id), "add_binding cannot replace existing binding: " + id);
                String source = fingerprint(binding, "source_fingerprint");
                ArrayNode sourceRefs = unionRefs(binding.path("caller"), binding.path("resolution"));
                Data.require(evidence.matchesFileHash(sourceRefs, source),
                        "Manual add_binding source_fingerprint must match a referenced callsite source file: " + id);
                bindings.put(id, binding);
            } else {
                String id = text(patch, "binding_id");
                binding = bindings.get(id);
                Data.require(binding != null, "Override refers to missing binding: " + id);
                String expected = fingerprint(patch, "expected_source_fingerprint");
                Data.require(expected.equals(binding.path("source_fingerprint").asText()),
                        "Stale override fingerprint for " + id + "; inspect current generated source and reconfirm");
            }
            ObjectNode resolution = binding.path("resolution").isObject()
                    ? (ObjectNode) binding.get("resolution") : binding.putObject("resolution");
            ArrayNode refs = unionRefs(resolution, patch);
            resolution.set("evidence_refs", refs);
            resolution.put("origin", "manual_override").put("reason", patch.path("reason").asText());
            resolution.putArray("override_ids").add(patch.path("id").asText());
            if (action.equals("disable_binding")) {
                binding.putArray("targets"); binding.putArray("candidate_targets"); resolution.put("status", "disabled");
            } else if (action.equals("replace_targets")) {
                binding.set("targets", array(patch, "targets").deepCopy()); binding.putArray("candidate_targets");
                if (patch.has("routing")) binding.set("routing", patch.path("routing").deepCopy());
                resolution.put("status", "confirmed");
            }
        }
        ObjectNode result = base("effective");
        setValues(result, "bindings", bindings); setValues(result, "endpoints", endpoints); setValues(result, "conditions", conditions);
        result.set("evidence", evidence.array());
        result.set("services", optionalArray(generated, "services").deepCopy());
        if (generated.has("snapshot_fingerprint")) result.set("snapshot_fingerprint", generated.get("snapshot_fingerprint"));
        if (generated.has("diagnostics")) result.set("diagnostics", array(generated, "diagnostics").deepCopy());
        ArrayNode applied = result.putArray("applied_overrides");
        overrides.keySet().forEach(applied::add);
        validate(result, evidence);
        evidence.validateFiles();
        return result;
    }

    private static void validate(ObjectNode document, Evidence evidence) {
        Map<String, ObjectNode> endpoints = index(document, "endpoints"), conditions = index(document, "conditions");
        for (ObjectNode condition : conditions.values()) {
            text(condition, "expression");
            Data.require(evidence.hasRefs(condition.path("evidence_refs"), false), "Condition requires source evidence: " + condition.path("id"));
        }
        for (ObjectNode endpoint : endpoints.values()) {
            Data.require(text(endpoint, "role").equals("provider"), "Endpoint role must be provider");
            for (String field : List.of("repo_id", "module_id", "class_name", "method_signature")) text(endpoint, field);
            text(endpoint.path("rpc"), "framework_id");
            Data.require(evidence.hasRefs(endpoint.path("evidence_refs"), false), "Endpoint requires evidence: " + endpoint.path("id"));
        }
        for (ObjectNode binding : index(document, "bindings").values()) {
            String id = text(binding, "id"); fingerprint(binding, "source_fingerprint");
            text(binding.path("framework"), "id"); text(binding.path("framework"), "rule_revision");
            JsonNode caller = binding.path("caller");
            for (String field : List.of("repo_id", "module_id", "class_name", "method_signature", "callsite_id")) text(caller, field);
            Data.require(binding.path("remote_identity").isObject(), "Binding needs remote_identity: " + id);
            for (JsonNode condition : optionalArray(caller, "condition_refs"))
                Data.require(condition.isTextual() && conditions.containsKey(condition.asText()), "Dangling caller condition: " + condition);
            String status = text(binding.path("resolution"), "status");
            Data.require(Set.of("confirmed", "candidate", "disabled").contains(status), "Invalid binding resolution status: " + status);
            ArrayNode targets = array(binding, "targets");
            if (status.equals("confirmed")) {
                Data.require(!targets.isEmpty(), "Confirmed binding must have a target: " + id);
                Data.require(evidence.hasRefs(binding.path("resolution").path("evidence_refs"), true),
                        "Confirmed binding requires current durable source evidence: " + id);
            } else Data.require(targets.isEmpty(), "Candidate/disabled bindings cannot contain effective targets: " + id);
            Data.require(evidence.hasRefs(binding.path("resolution").path("evidence_refs"), false),
                    "Every mapping status requires traceable evidence_refs: " + id);
            if (caller.has("evidence_refs")) evidence.hasRefs(caller.path("evidence_refs"), false);
            validateTargets(binding, targets, endpoints, conditions, evidence, status.equals("confirmed"));
            validateTargets(binding, optionalArray(binding, "candidate_targets"), endpoints, conditions, evidence, false);
            if (status.equals("confirmed") && targets.size() > 1) {
                JsonNode routing = binding.path("routing");
                Data.require(routing.path("mode").asText().equals("conditional") && evidence.hasRefs(routing.path("evidence_refs"), true),
                        "Multiple confirmed targets require evidence-backed conditional routing: " + id);
                for (JsonNode target : targets) Data.require(nullable(target.path("condition_ref")) != null,
                        "Every confirmed routing alternative needs condition_ref: " + id);
            }
        }
    }

    private static void validateTargets(JsonNode binding, ArrayNode targets, Map<String, ObjectNode> endpoints,
                                        Map<String, ObjectNode> conditions, Evidence evidence, boolean definitive) {
        Set<String> duplicates = new HashSet<>();
        for (JsonNode target : targets) {
            String endpointId = text(target, "endpoint_id");
            String conditionId = nullable(target.path("condition_ref"));
            Data.require(duplicates.add(endpointId + "/" + conditionId), "Duplicate target alternative: " + endpointId);
            JsonNode endpoint = endpoints.get(endpointId);
            Data.require(endpoint != null, "Dangling endpoint_id: " + endpointId);
            if (conditionId != null) {
                JsonNode condition = conditions.get(conditionId);
                Data.require(condition != null, "Dangling condition_ref: " + conditionId);
                if (definitive) Data.require(evidence.hasRefs(condition.path("evidence_refs"), true), "Routing condition needs durable evidence: " + conditionId);
            }
            JsonNode remote = binding.path("remote_identity"), rpc = endpoint.path("rpc");
            compatible(nullable(binding.path("framework").path("id")), nullable(rpc.path("framework_id")), "framework", endpointId);
            compatible(nullable(remote.path("service_id")), nullable(endpoint.path("service_id")), "service", endpointId);
            compatible(nullable(remote.path("schema_id")), nullable(rpc.path("schema_id")), "schema", endpointId);
            compatible(nullable(remote.path("operation_id")), nullable(rpc.path("operation_id")), "operation", endpointId);
            String raw = nullable(remote.path("microservice_name_raw"));
            if (raw != null && rpc.path("microservice_names_raw").isArray() && !rpc.path("microservice_names_raw").isEmpty())
                Data.require(hasText(rpc.path("microservice_names_raw"), raw), "Override contradicts resolved raw service identity at " + endpointId);
            if (definitive) Data.require(evidence.hasRefs(endpoint.path("evidence_refs"), true), "Confirmed target requires durable source evidence: " + endpointId);
        }
    }

    private static final class Rule {
        final String id, revision, consumerAnnotation, consumerService, consumerSchema, providerAnnotation, providerSchema;
        final JsonNode document, consumerOperation, providerOperation;
        final Set<String> exposureAnnotations = new LinkedHashSet<>();
        final boolean providerVerified, fullyVerified;
        Rule(JsonNode document, Evidence evidence) {
            this.document = document;
            JsonNode framework = document.path("framework"), consumer = document.path("consumer"), provider = document.path("provider");
            id = Data.id(text(framework, "id")); revision = text(framework, "revision");
            consumerAnnotation = fqcn(consumer, "annotation"); providerAnnotation = fqcn(provider, "annotation");
            consumerService = text(consumer, "service_attribute"); consumerSchema = text(consumer, "schema_attribute");
            providerSchema = text(provider, "schema_attribute");
            for (JsonNode name : array(provider, "exposure_annotations")) {
                Data.require(name.isTextual(), "exposure_annotations requires fully qualified strings");
                validateFqcn(name.asText()); Data.require(exposureAnnotations.add(name.asText()), "Duplicate exposure annotation: " + name);
            }
            Data.require(!exposureAnnotations.isEmpty(), "At least one provider exposure annotation must be declared");
            consumerOperation = consumer.path("operation"); providerOperation = provider.path("operation");
            validateOperation(consumerOperation, true, evidence); validateOperation(providerOperation, false, evidence);
            boolean base = verified(framework, evidence);
            boolean providerOperationKnown = !text(providerOperation, "source").equals("unknown");
            boolean consumerOperationKnown = !text(consumerOperation, "source").equals("unknown");
            providerVerified = base && providerOperationKnown;
            boolean contractVerified = verified(document.path("contract"), evidence);
            fullyVerified = providerVerified && consumerOperationKnown && contractVerified;
        }
        ArrayNode providerEvidence() { return unionRefs(document.path("framework"), providerOperation); }
        ArrayNode allEvidence() { return unionRefs(document.path("framework"), consumerOperation, providerOperation, document.path("contract")); }
    }

    private static final class Services {
        final Map<String, JsonNode> modules = new HashMap<>(), byRaw = new HashMap<>();
        final Map<String, String> names = new HashMap<>();
        final Evidence evidence;
        Services(JsonNode document, Evidence evidence) {
            this.evidence = evidence;
            for (JsonNode service : array(document, "services")) {
                String id = text(service, "service_id"), key = text(service, "repo_id") + "/" + text(service, "module_id");
                Data.require(modules.putIfAbsent(key, service) == null, "Ambiguous repo/module service registration: " + key);
                MappingService.verified(service, evidence); // Validate a declared verified registration even if it is unused.
                for (JsonNode name : array(service, "microservice_names_raw")) {
                    Data.require(name.isTextual() && !name.asText().isBlank(), "Raw service name must be a nonempty string");
                    String old = names.putIfAbsent(name.asText(), id);
                    Data.require(old == null || old.equals(id), "Raw service name maps to different services; declare routing explicitly: " + name.asText());
                    JsonNode previous = byRaw.get(name.asText());
                    if (previous == null || (!verified(previous) && verified(service))) byRaw.put(name.asText(), service);
                }
            }
        }
        JsonNode forModule(JsonNode source) { return modules.getOrDefault(text(source, "repo_id") + "/" + text(source, "module_id"), Data.object()); }
        JsonNode forRaw(String name) { return byRaw.getOrDefault(name, Data.object()); }
        String serviceForRaw(String raw) { return raw == null ? null : names.get(raw); }
        boolean verified(JsonNode service) { return MappingService.verified(service, evidence); }
    }

    private static final class Evidence {
        final Path project;
        final Map<String, ObjectNode> entries = new TreeMap<>();
        final Set<String> temporary = new HashSet<>();
        Evidence(Path project) { this.project = project.toAbsolutePath().normalize(); }
        void add(JsonNode document) {
            Set<String> ownIds = new HashSet<>();
            for (JsonNode item : optionalArray(document, "evidence")) {
                ObjectNode normalized = object(item, "evidence record").deepCopy();
                String id = text(item, "id");
                Data.require(ownIds.add(id), "Duplicate evidence id in one input: " + id);
                if (item.path("source").isObject()) {
                    JsonNode source = item.path("source");
                    copy(normalized, source, "path", "sha256", "start_line", "end_line"); normalized.remove("source");
                }
                text(normalized, "path"); fingerprint(normalized, "sha256");
                ObjectNode old = entries.putIfAbsent(id, normalized);
                Data.require(old == null || old.equals(normalized), "Conflicting evidence id: " + id);
            }
        }
        void validateFiles() throws Exception {
            Map<Path, String> hashes = new HashMap<>();
            for (var entry : entries.entrySet()) {
                JsonNode item = entry.getValue();
                Path path = project.resolve(item.path("path").asText()).toAbsolutePath().normalize();
                Data.require(Files.isRegularFile(path), "Evidence file missing; restore/revalidate instead of guessing: " + path);
                String hash = hashes.get(path);
                if (hash == null) { hash = Data.fingerprint(path); hashes.put(path, hash); }
                Data.require(hash.equals(item.path("sha256").asText()), "Stale evidence fingerprint: " + path);
                Path real = path.toRealPath();
                if (path.startsWith(project.resolve(".temp")) || real.startsWith(project.resolve(".temp"))) temporary.add(entry.getKey());
            }
        }
        boolean hasRefs(JsonNode refs, boolean durable) {
            if (refs.isMissingNode() || refs.isNull()) return false;
            Data.require(refs.isArray(), "evidence_refs must be an array");
            boolean valid = !refs.isEmpty();
            for (JsonNode ref : refs) {
                Data.require(ref.isTextual() && entries.containsKey(ref.asText()), "Dangling evidence_ref: " + ref);
                if (durable && temporary.contains(ref.asText())) valid = false;
            }
            return valid;
        }
        boolean hasSource(JsonNode item) { return hasRefs(item.path("evidence_refs"), true); }
        boolean matchesFileHash(JsonNode refs, String hash) {
            if (!hasRefs(refs, true)) return false;
            for (JsonNode ref : refs) if (entries.get(ref.asText()).path("sha256").asText().equals(hash)) return true;
            return false;
        }
        ArrayNode array() { ArrayNode result = Data.array(); entries.values().forEach(result::add); return result; }
    }

    private static void addInputEvidence(Path project, Path path, ObjectNode document, String kind) throws Exception {
        String id = stable("input-" + kind, path.toString());
        ObjectNode item = Data.object();
        item.put("id", id).put("kind", "configuration");
        item.put("path", path.startsWith(project) ? project.relativize(path).toString().replace('\\', '/') : path.toString());
        item.put("sha256", Data.fingerprint(path));
        ArrayNode list = document.path("evidence").isArray() ? (ArrayNode) document.path("evidence") : document.putArray("evidence");
        list.add(item);
        if (kind.equals("rules")) {
            ObjectNode framework = object(document.path("framework"), "framework");
            Data.require(!framework.path("status").asText().equals("verified") || !optionalArray(framework, "evidence_refs").isEmpty(),
                    "A verified framework needs independent evidence before its configuration fingerprint is added");
            ArrayNode refs = unionRefs(framework); refs.add(id); framework.set("evidence_refs", refs);
        } else for (JsonNode service : array(document, "services")) {
            Data.require(!service.path("status").asText().equals("verified") || !optionalArray(service, "evidence_refs").isEmpty(),
                    "A verified service registration needs independent evidence before its configuration fingerprint is added");
            ArrayNode refs = unionRefs(service); refs.add(id); object(service, "service").set("evidence_refs", refs);
        }
    }

    private static boolean verified(JsonNode node, Evidence evidence) {
        String status = node.path("status").asText("candidate");
        Data.require(Set.of("verified", "candidate", "unknown").contains(status), "Unsupported verification status: " + status);
        boolean refs = evidence.hasRefs(node.path("evidence_refs"), true);
        if (status.equals("verified")) Data.require(refs, "verified requires current durable evidence_refs");
        return status.equals("verified") && refs;
    }
    private static void validateOperation(JsonNode rule, boolean consumer, Evidence evidence) {
        String source = text(rule, "source");
        Set<String> supported = consumer ? Set.of("unknown", "invoked_method_name", "annotation_attribute")
                : Set.of("unknown", "method_name", "annotation_attribute");
        Data.require(supported.contains(source), "Unsupported operation strategy: " + source);
        if (source.equals("annotation_attribute")) { fqcn(rule, "annotation"); text(rule, "attribute"); }
        if (!source.equals("unknown")) Data.require(evidence.hasRefs(rule.path("evidence_refs"), true),
                "An executable operation rule requires current durable evidence_refs");
    }
    private static String operation(JsonNode method, JsonNode rule) { return operation(method, method, rule); }
    private static String operation(JsonNode call, JsonNode annotationOwner, JsonNode rule) {
        return switch (rule.path("source").asText()) {
            case "unknown" -> null;
            case "method_name", "invoked_method_name" -> nullable(call.path("method_name"));
            case "annotation_attribute" -> {
                JsonNode annotation = annotation(annotationOwner, rule.path("annotation").asText());
                yield annotation == null ? null : literal(annotation, rule.path("attribute").asText());
            }
            default -> throw new IllegalArgumentException("Unsupported operation strategy");
        };
    }
    private static JsonNode annotation(JsonNode owner, String fqcn) {
        List<JsonNode> annotations = annotations(owner, Set.of(fqcn));
        Data.require(annotations.size() <= 1, "Repeated RPC annotation requires an explicit adapter: " + fqcn);
        return annotations.isEmpty() ? null : annotations.getFirst();
    }
    private static List<JsonNode> annotations(JsonNode owner, Set<String> names) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode annotation : optionalArray(owner, "annotations"))
            if (annotation.path("resolved").asBoolean(false) && names.contains(annotation.path("full_name").asText())) result.add(annotation);
        return result;
    }
    private static String literal(JsonNode annotation, String attribute) {
        String value = nullable(annotation.path("values").path(attribute));
        return value != null && !value.contains("${") && !value.contains("#{") ? value : null;
    }
    private static ObjectNode base(String layer) {
        ObjectNode result = Data.object(); result.put("schema_version", 1).put("layer", layer);
        result.putArray("bindings"); result.putArray("endpoints"); result.putArray("conditions");
        result.putArray("evidence"); result.putArray("diagnostics"); result.putArray("applied_overrides"); return result;
    }
    private static void version(JsonNode value, String name) {
        Data.require(value.isObject() && value.path("schema_version").isIntegralNumber() && value.path("schema_version").asInt() == 1,
                name + " must be an object with schema_version: 1");
    }
    private static Map<String, ObjectNode> index(JsonNode document, String name) {
        Map<String, ObjectNode> result = new TreeMap<>();
        for (JsonNode value : optionalArray(document, name)) {
            ObjectNode item = object(value, name + " item").deepCopy();
            String id = text(item, "id");
            Data.require(result.putIfAbsent(id, item) == null, "Duplicate " + name + " id: " + id);
        }
        return result;
    }
    private static void merge(Map<String, ObjectNode> target, Map<String, ObjectNode> incoming, String kind) {
        for (var entry : incoming.entrySet()) {
            ObjectNode old = target.putIfAbsent(entry.getKey(), entry.getValue());
            Data.require(old == null || old.equals(entry.getValue()), "Conflicting " + kind + " id: " + entry.getKey());
        }
    }
    private static void setValues(ObjectNode target, String key, Map<String, ObjectNode> values) {
        ArrayNode array = target.putArray(key); values.values().forEach(array::add);
    }
    private static ObjectNode object(JsonNode value, String name) {
        Data.require(value.isObject(), name + " must be an object"); return (ObjectNode) value;
    }
    private static ArrayNode array(JsonNode value, String key) {
        Data.require(value.path(key).isArray(), key + " must be an array"); return (ArrayNode) value.path(key);
    }
    private static ArrayNode optionalArray(JsonNode value, String key) { return value.has(key) ? array(value, key) : Data.array(); }
    private static String text(JsonNode value, String key) {
        String result = nullable(value.path(key)); Data.require(result != null, "Missing nonempty string: " + key); return result;
    }
    private static String nullable(JsonNode value) { return value.isTextual() && !value.asText().isBlank() ? value.asText() : null; }
    private static void nullable(ObjectNode value, String key, String data) { if (data == null) value.putNull(key); else value.put(key, data); }
    private static void copy(ObjectNode target, JsonNode source, String... keys) { for (String key : keys) if (source.has(key)) target.set(key, source.get(key).deepCopy()); }
    private static String fingerprint(JsonNode value, String key) {
        String hash = text(value, key); Data.require(hash.matches("[0-9a-f]{64}"), key + " must be a lowercase SHA256 fingerprint"); return hash;
    }
    private static String fqcn(JsonNode owner, String key) { String value = text(owner, key); validateFqcn(value); return value; }
    private static void validateFqcn(String value) {
        Data.require(value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"), "Annotation must use a full qualified class name: " + value);
    }
    private static ArrayNode unionRefs(JsonNode... nodes) {
        ArrayNode result = Data.array(); for (JsonNode node : nodes) appendUnique(result, node.path("evidence_refs")); return result;
    }
    private static void appendUnique(ArrayNode result, JsonNode values) {
        if (values.isMissingNode() || values.isNull()) return;
        Data.require(values.isArray(), "evidence_refs must be an array");
        for (JsonNode value : values) { Data.require(value.isTextual(), "Evidence reference must be an ID string"); if (!contains(result, value)) result.add(value); }
    }
    private static boolean contains(ArrayNode values, JsonNode needle) { for (JsonNode value : values) if (value.equals(needle)) return true; return false; }
    private static boolean hasText(JsonNode values, String needle) { for (JsonNode value : values) if (value.isTextual() && value.asText().equals(needle)) return true; return false; }
    private static boolean differentKnown(String left, String right) { return left != null && right != null && !left.equals(right); }
    private static void compatible(String left, String right, String name, String endpoint) {
        Data.require(!differentKnown(left, right), "Override contradicts resolved " + name + " identity at " + endpoint);
    }
    private static String scope(JsonNode node) { return text(node, "repo_id") + "/" + text(node, "module_id"); }
    private static String stable(String prefix, String identity) { return prefix + "-" + Data.sha256(identity.getBytes(StandardCharsets.UTF_8)).substring(0, 24); }
    private static String digest(JsonNode value) { return Data.sha256(canonical(value).toString().getBytes(StandardCharsets.UTF_8)); }
    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = Data.object(); TreeSet<String> keys = new TreeSet<>(); value.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) sorted.set(key, canonical(value.get(key))); return sorted;
        }
        if (value.isArray()) { ArrayNode array = Data.array(); for (JsonNode item : value) array.add(canonical(item)); return array; }
        return value;
    }
    private static void diagnostic(ArrayNode output, String code, String binding, String message, JsonNode refs) {
        ObjectNode item = output.addObject(); item.put("code", code).put("binding_id", binding).put("severity", "warning").put("message", message);
        item.set("evidence_refs", refs.deepCopy());
    }
}

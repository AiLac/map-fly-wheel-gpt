package io.superbusinessflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Shared UTF-8, content addressing and process-safe file operations. */
public final class Data {
    public static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(SerializationFeature.INDENT_OUTPUT);
    public static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private Data() {}
    public static ObjectNode object() { return JSON.createObjectNode(); }
    public static ArrayNode array() { return JSON.createArrayNode(); }
    public static String now() { return Instant.now().toString(); }
    public static String id(String value) {
        require(value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}") && !value.contains(".."), "Unsafe identifier: " + value);
        return value;
    }
    public static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    public static JsonNode read(Path path) throws IOException {
        JsonNode node = mapper(path).readTree(Files.readString(path, StandardCharsets.UTF_8));
        require(node != null, "Empty data file: " + path);
        return node;
    }
    private static ObjectMapper mapper(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml") ? YAML : JSON;
    }
    public static void write(Path path, JsonNode node) throws IOException { writeText(path, mapper(path).writeValueAsString(node) + "\n"); }
    public static void writeText(Path path, String value) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".write-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public static String fingerprint(Path path) throws IOException { return sha256(Files.readAllBytes(path)); }
    public static AutoCloseable lock(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalArgumentException("Resource is busy; retry after the current writer finishes: " + path);
            return () -> { try { lock.release(); } finally { channel.close(); } };
        } catch (IOException | RuntimeException ex) { channel.close(); throw ex; }
    }
    public static Path resolve(Path project, String value) { return project.resolve(value).toAbsolutePath().normalize(); }
    public static Map<String, String> options(List<String> args, Set<String> allowed) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            String key = args.get(i);
            require(allowed.contains(key), "Unknown option: " + key);
            require(i + 1 < args.size() && !args.get(i + 1).startsWith("--"), "Missing value for " + key);
            require(options.putIfAbsent(key, args.get(++i)) == null, "Repeated option: " + key);
        }
        return options;
    }
    public static String required(Map<String,String> options, String name) {
        require(options.containsKey(name), "Required option: " + name); return options.get(name);
    }
}

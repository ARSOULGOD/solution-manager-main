package com.solace.sm.dp.apply.runtime.ssh;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Transport-owned Cache configuration editor. It accepts only the Cache package's fixed field
 * allowlist and never accepts broker passwords from the browser.
 */
public final class SshDockerConfigEditor {
    private static final Pattern TTL = Pattern.compile("[1-9][0-9]{0,3}[smhd]");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> TEXT_FIELDS = Set.of(
            "namespace", "topic-key-levels", "payload-key-json-pointer", "expiry-header-key-pointer",
            "expiry-payload-key-pointer", "cache-eligible-key-pointer", "cache-hit-topic", "cache-miss-topic",
            "cache-hit-response.template-key-topic-position", "solace.host", "solace.vpn", "solace.username",
            "solace.management.management-queue");
    private static final Set<String> BOOLEAN_FIELDS = Set.of("solace.enabled", "solace.management.enabled");
    private static final Set<String> LIST_FIELDS = Set.of("solace.queues", "solace.topics");
    private static final Set<String> ENUM_FIELDS = Set.of("mode", "provider", "key-source", "solace.messaging-mode",
            "solace.management.leader-election");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "mode", "provider", "namespace", "key-source", "payload-key-json-pointer", "topic-key-levels",
            "expiry-header-key-pointer", "expiry-payload-key-pointer", "cache-eligible-key-pointer", "max-size", "ttl",
            "cache-hit-topic", "cache-miss-topic", "cache-hit-response.template-key-topic-position",
            "cache-hit-response.template-mappings", "solace.enabled", "solace.host", "solace.vpn", "solace.username",
            "solace.queues", "solace.topics", "solace.messaging-mode", "solace.management.enabled",
            "solace.management.management-queue", "solace.management.leader-election");
    private final SshDockerPlacementCatalog catalog;
    private final SshDockerTransport transport;
    private final ConfigHasher hasher;

    public SshDockerConfigEditor(SshDockerPlacementCatalog catalog, SshCredentialSource credentials, ConfigHasher hasher,
            TargetAddressPolicy addressPolicy) {
        this(catalog, credentials, hasher, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    public SshDockerConfigEditor(SshDockerPlacementCatalog catalog, SshCredentialSource credentials, ConfigHasher hasher,
            TargetAddressPolicy addressPolicy, HostKeyTypeRecorder recorder) {
        this(catalog, new SshjDockerTransport(credentials, addressPolicy, recorder), hasher);
    }

    SshDockerConfigEditor(SshDockerPlacementCatalog catalog, SshDockerTransport transport, ConfigHasher hasher) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.hasher = java.util.Objects.requireNonNull(hasher, "hasher");
    }

    public RenderedConfig withTtl(PlacementRef placement, String ttl) {
        if (ttl == null || !TTL.matcher(ttl).matches()) {
            throw failure("demo.config.ttl.refused");
        }
        SshDockerPlacement target = catalog.lookup(placement);
        String source = new String(transport.readFile(target, target.configFile())
                .orElseThrow(() -> failure("ssh.config.readback.absent")), StandardCharsets.UTF_8);
        String edited = replaceCacheEngineTtl(source, ttl);
        return RenderedConfig.of(placement.tenant(), edited.getBytes(StandardCharsets.UTF_8), hasher,
                Set.of("cache-engine.ttl"));
    }

    /**
     * Merges the browser's non-secret Cache package values into the external YAML file. The remote
     * source remains the authority for fields absent from the form, including the broker password.
     */
    public RenderedConfig withCacheConfiguration(PlacementRef placement, JsonNode values) {
        validateCacheConfiguration(values);
        SshDockerPlacement target = catalog.lookup(placement);
        byte[] source = transport.readFile(target, target.configFile())
                .orElseThrow(() -> failure("ssh.config.readback.absent"));
        Map<String, Object> document = readDocument(source);
        Map<String, Object> cacheEngine = mapAt(document, "cache-engine");
        JsonNode fields = values.path("cache-engine");
        Iterator<Map.Entry<String, JsonNode>> entries = fields.fields();
        Set<String> edited = new java.util.TreeSet<>();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String key = entry.getKey();
            putPath(cacheEngine, key, valueFor(key, entry.getValue()));
            // OR-102: validateCacheConfiguration admitted only allowlisted, browser-safe fields: never a secret path.
            edited.add("cache-engine." + key);
        }
        try {
            return RenderedConfig.of(placement.tenant(), YAML.writeValueAsBytes(document), hasher, edited);
        } catch (Exception e) {
            throw failure("demo.config.render.failed");
        }
    }

    /** Validates only the browser-safe Cache package surface; secrets are intentionally absent. */
    public static void validateCacheConfiguration(JsonNode values) {
        if (values == null || !values.isObject() || !values.path("cache-engine").isObject()) {
            throw failure("demo.config.shape.refused");
        }
        Iterator<Map.Entry<String, JsonNode>> entries = values.path("cache-engine").fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!ALLOWED_FIELDS.contains(entry.getKey())) {
                throw failure("demo.config.field.refused");
            }
            valueFor(entry.getKey(), entry.getValue());
        }
    }

    private static Map<String, Object> readDocument(byte[] source) {
        try {
            Map<String, Object> parsed = YAML.readValue(source, new TypeReference<LinkedHashMap<String, Object>>() {});
            if (parsed == null) {
                throw failure("demo.config.document.empty");
            }
            return parsed;
        } catch (RuntimeAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw failure("demo.config.document.invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value == null) {
            Map<String, Object> created = new LinkedHashMap<>();
            parent.put(key, created);
            return created;
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw failure("demo.config.cache-engine.invalid");
    }

    @SuppressWarnings("unchecked")
    private static void putPath(Map<String, Object> target, String dottedKey, Object value) {
        String[] parts = dottedKey.split("\\.");
        Map<String, Object> current = target;
        for (int index = 0; index < parts.length - 1; index++) {
            Object existing = current.get(parts[index]);
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(parts[index], created);
                current = created;
            } else if (existing instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else {
                throw failure("demo.config.path.invalid");
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    private static Object valueFor(String key, JsonNode value) {
        if ("ttl".equals(key)) {
            if (!value.isTextual() || !TTL.matcher(value.asText()).matches()) throw failure("demo.config.ttl.refused");
            return value.asText();
        }
        if ("max-size".equals(key)) {
            if (!value.canConvertToInt() || value.asInt() < 1 || value.asInt() > 10_000_000) {
                throw failure("demo.config.max-size.refused");
            }
            return value.asInt();
        }
        if (BOOLEAN_FIELDS.contains(key)) {
            if (!value.isBoolean()) throw failure("demo.config.boolean.refused");
            return value.booleanValue();
        }
        if (LIST_FIELDS.contains(key)) {
            if (!value.isArray() || value.size() > 100) throw failure("demo.config.list.refused");
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (JsonNode item : value) values.add(safeText(item));
            return values;
        }
        if ("cache-hit-response.template-mappings".equals(key)) {
            if (!value.isObject() || value.size() > 100) throw failure("demo.config.map.refused");
            Map<String, String> values = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> entries = value.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                values.put(safeMapKey(entry.getKey()), safeText(entry.getValue()));
            }
            return values;
        }
        if (ENUM_FIELDS.contains(key)) return enumValue(key, safeText(value));
        if (TEXT_FIELDS.contains(key)) return safeText(value);
        throw failure("demo.config.field.refused");
    }

    private static String enumValue(String key, String value) {
        boolean valid = switch (key) {
            case "mode" -> Set.of("READ", "READ_WRITE", "ADMIN").contains(value);
            case "provider" -> Set.of("caffeine", "redis", "jcache").contains(value);
            case "key-source" -> Set.of("TOPIC", "PAYLOAD", "HEADER", "COMPOSITE").contains(value);
            case "solace.messaging-mode" -> Set.of("MESSAGING", "GATEWAY").contains(value);
            case "solace.management.leader-election" -> Set.of("STANDALONE", "ACTIVE_STANDBY").contains(value);
            default -> false;
        };
        if (!valid) throw failure("demo.config.enum.refused");
        return value;
    }

    private static String safeText(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 4096
                || value.asText().chars().anyMatch(Character::isISOControl)) {
            throw failure("demo.config.text.refused");
        }
        return value.asText();
    }

    private static String safeMapKey(String value) {
        if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw failure("demo.config.map.refused");
        }
        return value;
    }

    private static String replaceCacheEngineTtl(String source, String ttl) {
        String[] lines = source.split("(?<=\\n)", -1);
        boolean inCacheEngine = false;
        int matches = 0;
        StringBuilder result = new StringBuilder(source.length());
        for (String line : lines) {
            if (line.matches("^cache-engine:\\s*(?:#.*)?(?:\\r?\\n)?$")) {
                inCacheEngine = true;
            } else if (!line.startsWith(" ") && !line.startsWith("\\t") && !line.isBlank()) {
                inCacheEngine = false;
            }
            if (inCacheEngine && line.matches("^[ \\t]+ttl:[ \\t]*[^#\\r\\n]*(?:#.*)?(?:\\r?\\n)?$")) {
                String ending = line.endsWith("\r\n") ? "\r\n" : line.endsWith("\n") ? "\n" : "";
                String indent = line.substring(0, line.indexOf("ttl:"));
                result.append(indent).append("ttl: ").append(ttl).append(ending);
                matches++;
            } else {
                result.append(line);
            }
        }
        if (matches != 1) {
            throw failure("demo.config.ttl.not.unique");
        }
        return result.toString();
    }

    private static RuntimeAdapterException failure(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }
}

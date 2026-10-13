package com.solace.sm.dp.apply.runtime.readback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.api.readback.FieldPathTable;
import com.solace.sm.dp.api.readback.FieldPathTable.Entry;
import com.solace.sm.dp.api.readback.NotAcceptedReason;
import com.solace.sm.dp.api.readback.ReadBackCandidate.NotAccepted;
import com.solace.sm.dp.api.readback.ReadBackRefusedException;
import com.solace.sm.dp.api.readback.ReadBackRefusedException.Reason;
import com.solace.sm.dp.api.secret.SecretCompare;
import com.solace.sm.dp.config.OverlayKeys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** D-ACCEPT §3.3 and §3.5: the walk from the parsed tree to declared fields, and everything it must NOT adopt. */
class ReadBackMapperTest {

    private static final UUID SECRET_REF = UUID.randomUUID();

    /** The Cache package's shape, reduced: dotted keys, a map-typed subtree field, one secret, one yaml field. */
    private static final FieldPathTable TABLE = new FieldPathTable(List.of(
            new Entry("i1", "max-size", "cache-engine.max-size", "int", false, null),
            new Entry("i1", "ttl", "cache-engine.ttl", "duration", false, null),
            new Entry("i1", "namespace", "cache-engine.namespace", "string", false, null),
            new Entry("i1", "solace.enabled", "cache-engine.solace.enabled", "bool", false, null),
            new Entry("i1", "solace.password", "cache-engine.solace.password", "secret", true, SECRET_REF),
            new Entry("i1", "template-mappings", "cache-engine.cache-hit-response.template-mappings", "map", false, null),
            new Entry("i1", "extras", "cache-engine.extras", "yaml", false, null)));

    private static JsonNode tree(String yaml) {
        return ConfigFileParser.parse(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static ReadBackMapper.Walked walk(String yaml) {
        return ReadBackMapper.walk(tree(yaml), TABLE, OverlayKeys::ownsBaseline, (field, node) -> SecretCompare.Outcome.UNAVAILABLE);
    }

    private static Map<String, JsonNode> valuesByField(ReadBackMapper.Walked walked) {
        return walked.values().stream().collect(Collectors.toMap(m -> m.field().fieldKey(), ReadBackMapper.Mapped::value));
    }

    private static Map<String, NotAcceptedReason> reasonsByPath(ReadBackMapper.Walked walked) {
        return walked.notAccepted().stream().collect(Collectors.toMap(NotAccepted::path, NotAccepted::reason));
    }

    // ---- scenario A, and the two spellings of one path -----------------------------------------------------------------

    @Test
    void aNestedAndAFlatSpellingOfADeclaredPathBothMap() {
        ReadBackMapper.Walked nested = walk("cache-engine:\n  max-size: 250000\n  ttl: \"30m\"\n  solace:\n    enabled: true\n");
        assertEquals(250000, valuesByField(nested).get("max-size").intValue());
        assertEquals("30m", valuesByField(nested).get("ttl").textValue());
        assertTrue(valuesByField(nested).get("solace.enabled").booleanValue(), "a dotted field key is a nested path too");

        ReadBackMapper.Walked flat = walk("cache-engine.max-size: 7\ncache-engine.ttl: 1h\n");
        assertEquals(7, valuesByField(flat).get("max-size").intValue());
        assertEquals("1h", valuesByField(flat).get("ttl").textValue());
    }

    @Test
    void aFlatAndANestedSpellingOfOnePathIsRefusedAmbiguous() {
        ReadBackRefusedException e = assertThrows(ReadBackRefusedException.class,
                () -> walk("cache-engine.max-size: 1\ncache-engine:\n  max-size: 2\n"));
        assertEquals(Reason.AMBIGUOUS_KEY, e.reason());
    }

    @Test
    void aFieldWithNoNodeIsSimplyNotInTheResult() {
        ReadBackMapper.Walked walked = walk("cache-engine:\n  namespace: orders\n");
        assertEquals(Map.of("namespace", "orders"),
                valuesByField(walked).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().textValue())),
                "absent means unset, which is the gateway's reading, not a value here");
    }

    @Test
    void aMapTypedFieldTakesItsWholeSubtree() {
        ReadBackMapper.Walked walked = walk("cache-engine:\n  cache-hit-response:\n    template-mappings:\n"
                + "      a: x\n      b:\n        c: 1\n");
        JsonNode subtree = valuesByField(walked).get("template-mappings");
        assertEquals("x", subtree.get("a").textValue());
        assertEquals(1, subtree.get("b").get("c").intValue());
        assertTrue(walked.notAccepted().isEmpty(), "its inner keys are not 'undeclared'");
    }

    // ---- scenario C: keys the package does not declare ---------------------------------------------------------------------

    @Test
    void anUnmappedKeyIsReportedByPathNeverByValue() {
        String sentinel = "value-sentinel-4b7e";
        ReadBackMapper.Walked walked = walk("server:\n  port: " + sentinel + "\nspring.main.banner-mode: off\n"
                + "cache-engine:\n  max-size: 5\n  new-flag: " + sentinel + "\n");

        assertEquals(Map.of("server.port", NotAcceptedReason.UNDECLARED_KEY,
                "spring.main.banner-mode", NotAcceptedReason.UNDECLARED_KEY,
                "cache-engine.new-flag", NotAcceptedReason.UNDECLARED_KEY), reasonsByPath(walked));
        assertFalse(walked.notAccepted().toString().contains(sentinel), "a path names a key, never what it holds");
        assertEquals(5, valuesByField(walked).get("max-size").intValue());
    }

    @Test
    void onlyTheExactDeclaredKebabPathIsMappedAndOtherSpellingsAreReported() {
        ReadBackMapper.Walked walked = walk("cache-engine:\n  maxSize: 5\n  max_size: 6\n  MAX-SIZE: 7\n");
        assertTrue(walked.values().isEmpty(), "M-1: no relaxed binding");
        assertEquals(Map.of("cache-engine.maxSize", NotAcceptedReason.UNDECLARED_KEY,
                "cache-engine.max_size", NotAcceptedReason.UNDECLARED_KEY,
                "cache-engine.MAX-SIZE", NotAcceptedReason.UNDECLARED_KEY), reasonsByPath(walked));
    }

    @Test
    void anUndeclaredListOrScalarAtTheTopIsReportedAtItsPath() {
        ReadBackMapper.Walked walked = walk("things: [a, b]\nplain: 3\n");
        assertEquals(Map.of("things", NotAcceptedReason.UNDECLARED_KEY, "plain", NotAcceptedReason.UNDECLARED_KEY),
                reasonsByPath(walked));
    }

    // ---- scenario H: SM's own keys ----------------------------------------------------------------------------------------

    @Test
    void anOverlayKeyIsStrippedByTheFunctionTheRendererUses() {
        ReadBackMapper.Walked walked = walk("management:\n  endpoint:\n    loggers: {access: read-write}\n  endpoints:\n"
                + "    web:\n      exposure: {include: \"health,loggers\"}\n  server: {port: 18081}\nlogging:\n  level:\n"
                + "    com.solace.cache: DEBUG\ncache-engine:\n  max-size: 1\n");
        Map<String, NotAcceptedReason> reasons = reasonsByPath(walked);
        assertEquals(NotAcceptedReason.SM_OWNED_OVERLAY, reasons.get("management.endpoint.loggers.access"));
        assertEquals(NotAcceptedReason.SM_OWNED_OVERLAY, reasons.get("management.endpoints.web.exposure.include"));
        assertEquals(NotAcceptedReason.UNDECLARED_KEY, reasons.get("management.server.port"),
                "only the two keys OverlayKeys writes are SM's; the management port is the package's, not SM's (BE-D37)");
        assertEquals(NotAcceptedReason.UNDECLARED_KEY, reasons.get("logging.level.com.solace.cache"),
                "a level change is configuration (OR-103): logging.level.* is the operator's, not an overlay key");
        assertTrue(walked.values().stream().noneMatch(m -> m.field().fieldKey().startsWith("management")),
                "nothing SM owns is mapped to a field");

        // the SAME walk with a different owner of the overlay: a hard-coded list in the mapper would not notice
        ReadBackMapper.Walked other = ReadBackMapper.walk(tree("management:\n  server: {port: 1}\nsm-key: 1\n"), TABLE,
                path -> path.equals("sm-key"), (field, node) -> SecretCompare.Outcome.UNAVAILABLE);
        assertEquals(Map.of("management.server.port", NotAcceptedReason.UNDECLARED_KEY,
                "sm-key", NotAcceptedReason.SM_OWNED_OVERLAY), reasonsByPath(other));
    }

    @Test
    void everyKeyTheOverlayWritesIsReportedSmOwnedAndNotAdopted() {
        // the keys come from the function the stage uses, not from a list written here: if OverlayKeys grows a key,
        // this follows it, and a read-back that missed it would adopt SM's own key as an operator's edit
        var support = new com.solace.sm.dp.api.config.LoggerSupport(List.of("health", "info"));
        StringBuilder yaml = new StringBuilder("cache-engine:\n  max-size: 1\n");
        for (OverlayKeys.Entry entry : OverlayKeys.forLoggerSupport(support)) {
            yaml.append(entry.path()).append(": ").append(entry.valueJson()).append('\n');
        }

        ReadBackMapper.Walked walked = walk(yaml.toString());

        Map<String, NotAcceptedReason> reasons = reasonsByPath(walked);
        assertEquals(OverlayKeys.forLoggerSupport(support).size(), reasons.size());
        for (OverlayKeys.Entry entry : OverlayKeys.forLoggerSupport(support)) {
            assertEquals(NotAcceptedReason.SM_OWNED_OVERLAY, reasons.get(entry.path()), entry.path());
        }
        assertEquals(List.of("max-size"), walked.values().stream().map(m -> m.field().fieldKey()).toList(),
                "only the package's own field is adopted");
    }

    // ---- secrets and yaml ---------------------------------------------------------------------------------------------------

    @Test
    void aSecretFieldIsReportedWithTheComparersAnswerAndItsValueIsNeverInTheResult() {
        String secret = "hunter2-sentinel-91d0";
        java.util.List<String> seenByComparer = new java.util.ArrayList<>();
        ReadBackMapper.Walked walked = ReadBackMapper.walk(
                tree("cache-engine:\n  max-size: 5\n  solace:\n    password: " + secret + "\n"), TABLE,
                OverlayKeys::ownsBaseline, (field, node) -> {
                    seenByComparer.add(field.fieldKey());
                    return SecretCompare.Outcome.DIFFERS;
                });

        assertEquals(List.of("solace.password"), seenByComparer, "the comparer is handed the secret node, once");
        NotAccepted entry = walked.notAccepted().get(0);
        assertEquals("cache-engine.solace.password", entry.path());
        assertEquals(NotAcceptedReason.SECRET_NOT_READ, entry.reason());
        assertEquals(SecretCompare.Outcome.DIFFERS, entry.comparison());
        assertEquals("solace.password", entry.fieldKey());
        assertFalse(walked.values().stream().anyMatch(m -> m.field().secret()), "a secret is not a mapped value");
        assertFalse(walked.toString().contains(secret) || walked.notAccepted().toString().contains(secret)
                || walked.values().toString().contains(secret));
    }

    @Test
    void anUnavailableComparisonMakesNoClaimEitherWay() {
        ReadBackMapper.Walked walked = walk("cache-engine:\n  solace:\n    password: x\n");
        assertEquals(null, walked.notAccepted().get(0).comparison());
        assertEquals(NotAcceptedReason.SECRET_NOT_READ, walked.notAccepted().get(0).reason());
    }

    @Test
    void aYamlTypedFieldIsNotAcceptedAndNotRead() {
        ReadBackMapper.Walked walked = walk("cache-engine:\n  extras:\n    token: yaml-token-sentinel\n");
        assertTrue(walked.values().isEmpty());
        assertEquals(Map.of("cache-engine.extras", NotAcceptedReason.YAML_FIELD_NOT_ACCEPTED), reasonsByPath(walked));
        assertFalse(walked.toString().contains("yaml-token-sentinel"));
    }

    // ---- the cap --------------------------------------------------------------------------------------------------------------

    @Test
    void theReportIsCappedAtTheContractsBoundAndSaysSo() {
        StringBuilder many = new StringBuilder("junk:\n");
        for (int i = 0; i < 250; i++) {
            many.append("  k").append(String.format("%03d", i)).append(": 1\n");
        }
        ReadBackMapper.Walked walked = walk(many.toString());
        assertEquals(200, walked.notAccepted().size());
        assertTrue(walked.truncated());
        assertEquals("junk.k000", walked.notAccepted().get(0).path(), "deterministic: sorted by path");
    }

    @Test
    void aVeryLongKeyIsBoundedInTheReport() {
        String longKey = "k".repeat(900);
        ReadBackMapper.Walked walked = walk(longKey + ": 1\n");
        assertTrue(walked.notAccepted().get(0).path().length() <= 256);
    }

    @Test
    void theTableRefusesTwoFieldsAtOnePath() {
        assertThrows(IllegalArgumentException.class, () -> new FieldPathTable(List.of(
                new Entry("i1", "a", "p.x", "string", false, null),
                new Entry("i2", "a", "p.x", "string", false, null))).byPath());
    }
}

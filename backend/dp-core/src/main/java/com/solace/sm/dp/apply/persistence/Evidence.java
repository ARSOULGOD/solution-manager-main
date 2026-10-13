package com.solace.sm.dp.apply.persistence;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.ChangedContent;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ResolutionEvidence;
import com.solace.sm.dp.apply.model.RestartObservation;
import com.solace.sm.dp.apply.model.RestartResolutionEvidence;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three {@code jsonb} columns of design §3.1, serialized here and nowhere else.
 *
 * <p><b>Why this package holds a mapper.</b> {@code arch-tests}' {@code meshbridgeHoldsNoOwnMapper}
 * confines mappers away from the PUBLISHER package, and its reason is specific: mesh events have ONE
 * contract mapper ({@code EventJson}) because the bytes on the wire are a published contract that two
 * modules and a JSON-Schema catalogue must agree on. Persistence {@code jsonb} is a different concern —
 * the bytes are read only by this database's own CHECK constraints and by this class — so a mapper
 * here is not the thing that rule forbids. The rule is package-scoped for exactly that reason, and this
 * class keeps the concern honest by being the ONE place in the apply stack that builds JSON.
 *
 * <p><b>The key names are load-bearing.</b> {@code resolution_is_evidence} reads
 * {@code resolution_observed -> 'stagedGeneration'} and {@code -> 'replicas'} for an {@code apply}
 * resolution, and {@code -> 'restartsObserved'} for a {@code restart} one ({@code BE-D17}, §3.4) — by
 * name; renaming any of them would not fail a compile, it would make every resolution of that operation
 * refuse with a 23514. They are written explicitly below rather than derived from record component
 * names, so the coupling to the DDL is visible at the point where it exists.
 *
 * <p>{@code error_class} is not here: it is a {@code text} column, so the persistence layer stores
 * {@code ErrorClass.token()} directly and there is no JSON to get wrong.
 */
final class Evidence {

    /**
     * One mapper, built once. Every shape below is constructed and read field by field. The one
     * setting is an {@code I6} one: a parse failure's message must not quote the document — a
     * corrupt {@code jsonb} column would otherwise travel into the exception that reports it, and from
     * there into a log. Jackson 2.16+ redacts the source by default; it is disabled explicitly as
     * defence in depth, so a BOM change or a global enable cannot silently reopen that surface — but
     * the property that actually keeps the column's content out of the exception chain is that
     * {@link #read} and {@link #instant} attach no cause, and that is what {@code EvidenceJsonTest}
     * measures: re-enabling this setting alone does not turn any of its assertions red.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    private Evidence() {
    }

    /** {@code {"startedAt":"<ISO-8601 instant>","processRef":"…"}}, or null for an absent baseline. */
    static String write(PreBaseline baseline) {
        if (baseline == null) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("startedAt", baseline.startedAt().toString());
        root.put("processRef", baseline.processRef());
        return root.toString();
    }

    static PreBaseline readPreBaseline(String json) {
        JsonNode root = read(json, "pre_baseline");
        if (root == null) {
            return null;
        }
        return new PreBaseline(instant(root.path("startedAt").asText(), "pre_baseline.startedAt"),
                root.path("processRef").asText());
    }

    /**
     * {@code {"paths":["…"],"pathsTruncated":false,"otherContentChanged":false}} (OR-108 (1)): dotted paths and two flags,
     * never a value. The three key names are the ones {@code pre_image_change_shape} reads.
     */
    static String write(ChangedContent change) {
        if (change == null) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode paths = root.putArray("paths");
        change.paths().forEach(paths::add);
        root.put("pathsTruncated", change.pathsTruncated());
        root.put("otherContentChanged", change.otherContentChanged());
        return root.toString();
    }

    static ChangedContent readChangedContent(String json) {
        JsonNode root = read(json, "pre_image_change");
        if (root == null) {
            return null;
        }
        List<String> paths = new ArrayList<>();
        root.path("paths").forEach(path -> paths.add(path.asText()));
        return new ChangedContent(paths, root.path("pathsTruncated").asBoolean(),
                root.path("otherContentChanged").asBoolean());
    }

    /**
     * The reference map as a JSON object, keyed by {@link RuntimeRef.Key#wire()} — insertion order
     * preserved by {@link RuntimeRef}.
     */
    static String write(RuntimeRef ref) {
        if (ref == null) {
            return null;
        }
        ObjectNode root = MAPPER.createObjectNode();
        ref.fields().forEach((key, value) -> root.put(key.wire(), value));
        return root.toString();
    }

    /**
     * The read half of the closed key set: a key outside {@link RuntimeRef.Key} is refused rather than
     * carried through.
     *
     * <p>Closing the Java API alone would leave the {@code jsonb} as the open door — a row written by
     * an older build, a hand-edit, or an adapter that reached the column some other way could still
     * introduce {@code apiToken}. {@link RuntimeRef.Key#ofWire} is what makes the enum a property of
     * the stored document and not only of the constructor.
     */
    static RuntimeRef readRuntimeRef(String json) {
        JsonNode root = read(json, "runtime_ref");
        if (root == null) {
            return null;
        }
        Map<RuntimeRef.Key, String> fields = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            fields.put(RuntimeRef.Key.ofWire(e.getKey()), e.getValue().asText());
        }
        return new RuntimeRef(fields);
    }

    /**
     * {@code BE-D17} (design §3.4): two operation-specific shapes, dispatched on the evidence's own
     * Java type — {@link ApplyResolutionEvidence} writes
     * {@code {"stagedGeneration":N,"replicas":[{"ref":"…","restarted":true,"observedHash":…}]}},
     * {@link RestartResolutionEvidence} writes
     * {@code {"restartsObserved":[{"ref":"…","restarted":true}]}}. Those top-level key names — three
     * of them now, not two — are the ones {@code resolution_is_evidence} reads; {@code sealed} on
     * {@link ResolutionEvidence} makes this dispatch exhaustive at compile time, so a third shape
     * cannot be added here without the compiler noticing.
     */
    static String write(ResolutionEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        if (evidence instanceof ApplyResolutionEvidence apply) {
            return writeApply(apply);
        }
        if (evidence instanceof RestartResolutionEvidence restart) {
            return writeRestart(restart);
        }
        throw new IllegalStateException(
                "unreachable: ResolutionEvidence is sealed to two permitted types, got "
                        + evidence.getClass());
    }

    private static String writeApply(ApplyResolutionEvidence evidence) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("stagedGeneration", evidence.stagedGeneration());
        ArrayNode replicas = root.putArray("replicas");
        for (ReplicaObservation r : evidence.replicas()) {
            ObjectNode node = replicas.addObject();
            node.put("ref", r.ref().value());
            node.put("restarted", r.restarted());
            if (r.observedHash() == null) {
                node.putNull("observedHash");
            } else {
                node.put("observedHash", r.observedHash());
            }
        }
        return root.toString();
    }

    private static String writeRestart(RestartResolutionEvidence evidence) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode observed = root.putArray("restartsObserved");
        for (RestartObservation r : evidence.restartsObserved()) {
            ObjectNode node = observed.addObject();
            node.put("ref", r.ref().value());
            node.put("restarted", r.restarted());
        }
        return root.toString();
    }

    /**
     * The read half — discriminated the same way the CHECK discriminates the column: by which key is
     * present, {@code stagedGeneration} (an {@code apply} document) or {@code restartsObserved} (a
     * {@code restart} document), never by a caller-asserted flag. There is no live caller of this
     * method in the engine today (resolution columns are write-only from {@code OutcomeService}'s own
     * read path — {@code ApplyRequestRow} does not carry them); it exists for the round-trip proof
     * {@code EvidenceJsonTest} is.
     */
    static ResolutionEvidence readResolutionEvidence(String json) {
        JsonNode root = read(json, "resolution_observed");
        if (root == null) {
            return null;
        }
        if (root.has("restartsObserved")) {
            List<RestartObservation> observed = new ArrayList<>();
            for (JsonNode node : root.path("restartsObserved")) {
                observed.add(new RestartObservation(new ReplicaRef(node.path("ref").asText()),
                        node.path("restarted").asBoolean()));
            }
            return new RestartResolutionEvidence(observed);
        }
        List<ReplicaObservation> replicas = new ArrayList<>();
        for (JsonNode node : root.path("replicas")) {
            JsonNode hash = node.path("observedHash");
            if (hash.isMissingNode()) {
                // BE-D18: Evidence.writeApply always emits this key (putNull when the hash is
                // unavailable), so an entirely absent key is a shape this build never wrote — the
                // same "missing required observation" resolution_is_evidence now refuses at the
                // database. Silently coalescing it to null here would tolerate, on read, exactly
                // the corruption that CHECK exists to reject.
                throw new IllegalStateException(
                        "sm_data.resolution_observed does not hold JSON this build wrote");
            }
            replicas.add(new ReplicaObservation(new ReplicaRef(node.path("ref").asText()),
                    node.path("restarted").asBoolean(),
                    hash.isNull() ? null : hash.asText()));
        }
        return new ApplyResolutionEvidence(root.path("stagedGeneration").asLong(), replicas);
    }

    /**
     * {@link Instant#parse} without its message: {@code DateTimeParseException} quotes the text it could
     * not parse ({@code Text 'x' could not be parsed at index 0} — measured), which for a corrupt column
     * is the column's content. Same corruption-report posture as {@link #read}, no cause.
     */
    private static Instant instant(String text, String field) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("sm_data." + field
                    + " does not hold an ISO-8601 instant this build wrote");
        }
    }

    private static JsonNode read(String json, String column) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // The column can only have been written by write() above, so this is a corruption report,
            // not a parse-failure path a caller recovers from. No cause: Jackson's own message can
            // quote the failing token verbatim when the malformed input's first bad token IS the
            // sensitive text (measured — see EvidenceJsonTest), and Logback's log.error(msg, ex) prints
            // the whole cause chain. Same corruption-report posture as instant() below.
            throw new IllegalStateException("sm_data." + column + " does not hold JSON this build "
                    + "wrote");
        }
    }
}

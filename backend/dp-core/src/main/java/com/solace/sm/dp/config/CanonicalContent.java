package com.solace.sm.dp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The canonical form of a staged generation: the bytes its keyed digest is taken over (OR-33 guard 1) and the text
 * stored in the three JSON columns. Object keys are sorted at every depth, arrays keep their order (a list in a
 * draft is ordered content), the rendered set is sorted by (instance key, path), and nothing here ever holds a
 * resolved secret: a secret entry is its reference.
 *
 * <p>Two stagings of the same content produce the same bytes whatever order the client's JSON keys arrived in;
 * a staging that differs in a single value produces different bytes. The one exception is presentation
 * {@code layout} (UI-D20, OR-96): it is neither digested NOR stored in the generation. A layout inside the component
 * selection is dropped; the layout the command carries separately is canonicalised here and goes to the per-instance
 * presentation record, outside the config of record.
 */
final class CanonicalContent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String componentSelection;
    private final String draftValues;
    private final String renderedEntries;
    private final byte[] digestInput;
    private final String layout;

    private CanonicalContent(String componentSelection, String draftValues, String renderedEntries,
            byte[] digestInput, String layout) {
        this.componentSelection = componentSelection;
        this.draftValues = draftValues;
        this.renderedEntries = renderedEntries;
        this.digestInput = digestInput;
        this.layout = layout;
    }

    static CanonicalContent of(StageCommand c) {
        return of(c, List.of());
    }

    /**
     * OR-103 (T9.4) and Fable APPLY-01: the one place an operator edit that re-renders from a draft (which has no
     * overlay) keeps the configured log levels of the latest generation. Every minting path that builds its content from
     * a {@link StageCommand} (the designer stage, accept-runtime, a secret set) calls it under the instance lock, so no
     * path can drop a level the others keep. A command that declares no logger support has no level to keep.
     */
    static CanonicalContent carryingLevelsOf(StageCommand command, CanonicalContent content,
            Optional<GenerationRecord> latest) {
        if (command.loggerSupport() == null) {
            return content;
        }
        List<OverlayKeys.Entry> carried = latest.map(l -> overlayLevelsOf(l.renderedEntriesJson())).orElse(List.of());
        return carried.isEmpty() ? content : of(command, carried);
    }

    /**
     * The configured-level overlay entries of a stored rendered set, in stored order: what an edit must carry into its
     * successor (OR-103, T9.4). Only SM-owned level entries; a package's own fields and the baseline overlay are not
     * carried because the new stage computes both itself.
     */
    static List<OverlayKeys.Entry> overlayLevelsOf(String renderedEntriesJson) {
        List<OverlayKeys.Entry> levels = new ArrayList<>();
        for (RenderedEntryValue e : renderedFrom(renderedEntriesJson)) {
            if (OverlayKeys.INSTANCE_KEY.equals(e.instanceKey()) && e.valueJson() != null
                    && OverlayKeys.isLevelPath(e.renderedPath())) {
                levels.add(new OverlayKeys.Entry(e.renderedPath(), e.valueJson()));
            }
        }
        return List.copyOf(levels);
    }

    /**
     * As {@link #of(StageCommand)}, for an EDIT whose package declares logger support: {@code carriedLevels} are the
     * previous generation's configured levels, which the stage's own re-rendering cannot know (the draft has no
     * overlay), kept so an unrelated edit does not drop the level ({@code log-levels.md} section 9). They are dropped
     * when the command declares no support, because the package no longer offers loggers.
     */
    static CanonicalContent of(StageCommand c, List<OverlayKeys.Entry> carriedLevels) {
        JsonNode selection = withoutLayout(object(c.componentSelectionJson(), "component selection"));
        JsonNode draft = object(c.draftValuesJson(), "draft values");
        ArrayNode rendered = renderedArray(withOverlay(c, carriedLevels));
        ObjectNode whole = JsonNodeFactory.instance.objectNode();
        whole.put("solution", c.packageSolution());
        whole.put("version", c.packageVersion());
        whole.set("selection", selection);
        whole.set("draft", draft);
        whole.set("rendered", rendered);
        String layout = c.layoutJson() == null ? null : text(layoutOf(c.layoutJson()));
        return new CanonicalContent(text(selection), text(draft), text(rendered),
                text(whole).getBytes(StandardCharsets.UTF_8), layout);
    }

    /**
     * The rendered set a stored {@code rendered_entries} column holds, back as typed entries (a copy-forward's source,
     * OR-42/OR-45). The text was written by {@link #of}, so it is an array of {@code {instanceKey, renderedPath}} with
     * exactly one of {@code value} and {@code secretRef}; anything else is a corrupt row, refused without quoting it.
     * A secret stays a reference ({@code I6}).
     */
    static List<RenderedEntryValue> renderedFrom(String renderedEntriesJson) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(renderedEntriesJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored rendered set is not valid JSON");
        }
        if (parsed == null || !parsed.isArray()) {
            throw new IllegalStateException("a stored rendered set is not an array");
        }
        List<RenderedEntryValue> entries = new ArrayList<>();
        for (JsonNode node : parsed) {
            String instanceKey = node.path("instanceKey").asText(null);
            String path = node.path("renderedPath").asText(null);
            if (instanceKey == null || path == null) {
                throw new IllegalStateException("a stored rendered entry has no instance key or path");
            }
            if (node.has("secretRef")) {
                entries.add(RenderedEntryValue.secret(instanceKey, path,
                        java.util.UUID.fromString(node.get("secretRef").asText())));
            } else if (node.has("value")) {
                entries.add(RenderedEntryValue.plain(instanceKey, path, text(node.get("value"))));
            } else {
                throw new IllegalStateException("a stored rendered entry has neither a value nor a secret reference");
            }
        }
        return entries;
    }

    /**
     * OR-103 / BE-D37: the rendered set with the SM-owned overlay in it. The overlay is added by {@link OverlayKeys}
     * alone, from the package's declaration the command carries; the client's entries are checked against the same
     * class, in both directions, so no path is claimed by a package field and by SM at once and nothing SM does not own
     * is stored under the overlay's reserved key. A command that already carries overlay entries (a copy-forward of a
     * generation that has them) keeps exactly those and cannot also declare support, because the second set would be a
     * duplicate of the first or a contradiction of it.
     */
    private static List<RenderedEntryValue> withOverlay(StageCommand c, List<OverlayKeys.Entry> carriedLevels) {
        for (OverlayKeys.Entry carried : carriedLevels) {
            if (!OverlayKeys.isLevelPath(carried.path())) {
                throw new IllegalArgumentException("only a configured level is carried into an edit");
            }
        }
        boolean carriesOverlay = false;
        for (RenderedEntryValue e : c.rendered()) {
            boolean overlayKeyed = OverlayKeys.INSTANCE_KEY.equals(e.instanceKey());
            if (overlayKeyed && !OverlayKeys.owns(e.renderedPath())) {
                throw new IllegalArgumentException("the overlay's reserved instance key is only for paths SM owns");
            }
            if (!overlayKeyed && OverlayKeys.owns(e.renderedPath())) {
                throw new IllegalArgumentException("a package field cannot claim a path SM owns");
            }
            carriesOverlay |= overlayKeyed;
        }
        if (c.loggerSupport() == null) {
            return c.rendered();
        }
        if (carriesOverlay) {
            throw new IllegalArgumentException("a rendered set that carries overlay entries cannot also declare logger support");
        }
        List<RenderedEntryValue> withOverlay = new ArrayList<>(c.rendered());
        for (OverlayKeys.Entry entry : OverlayKeys.forLoggerSupport(c.loggerSupport())) {
            withOverlay.add(RenderedEntryValue.plain(OverlayKeys.INSTANCE_KEY, entry.path(), entry.valueJson()));
        }
        for (OverlayKeys.Entry carried : carriedLevels) {
            withOverlay.add(RenderedEntryValue.plain(OverlayKeys.INSTANCE_KEY, carried.path(), carried.valueJson()));
        }
        return withOverlay;
    }

    /**
     * OR-96 (UI-D20): {@code layout} is presentation state, so moving a node is not a config change (it must not
     * register as a new generation, draw a stamp or use a retention slot) and it is not config history either: it is
     * dropped from the selection that is both digested and stored in the immutable generation row. The layout lives in
     * the presentation record.
     */
    private static JsonNode withoutLayout(JsonNode selection) {
        JsonNode copy = selection.deepCopy();
        for (JsonNode instance : copy.path("instances")) {
            if (instance instanceof ObjectNode node) {
                node.remove("layout");
            }
        }
        return copy;
    }

    /**
     * The canonical layout (keys sorted, numbers as sent) for the presentation record, or {@code null} when the command
     * carried none, which means "leave the record alone".
     */
    String layout() {
        return layout;
    }

    String componentSelection() {
        return componentSelection;
    }

    String draftValues() {
        return draftValues;
    }

    String renderedEntries() {
        return renderedEntries;
    }

    /** A copy: the caller hands it to a hasher and must not be able to change what is digested. */
    byte[] digestInput() {
        return digestInput.clone();
    }

    private static JsonNode object(String json, String what) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            // the parser's message quotes the offending text: configuration, never repeated (I6)
            throw new IllegalArgumentException(what + " is not valid JSON");
        }
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalArgumentException(what + " must be a JSON object");
        }
        return sorted(parsed);
    }

    /**
     * An object of instance key to exactly {@code {x: number, y: number}}. The refusal names the rule and never quotes
     * the text.
     */
    private static JsonNode layoutOf(String json) {
        JsonNode parsed = object(json, "layout");
        for (Iterator<JsonNode> it = parsed.elements(); it.hasNext();) {
            JsonNode position = it.next();
            if (!position.isObject() || position.size() != 2 || !position.path("x").isNumber()
                    || !position.path("y").isNumber()) {
                throw new IllegalArgumentException("layout must map each instance key to exactly {x, y} numbers");
            }
        }
        return parsed;
    }

    private static ArrayNode renderedArray(List<RenderedEntryValue> entries) {
        List<RenderedEntryValue> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(RenderedEntryValue::instanceKey)
                .thenComparing(RenderedEntryValue::renderedPath));
        Set<String> seen = new HashSet<>();
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (RenderedEntryValue e : ordered) {
            if (!seen.add(e.instanceKey() + '\u0000' + e.renderedPath())) {
                throw new IllegalArgumentException("a rendered set names one value per (instance, path)");
            }
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("instanceKey", e.instanceKey());
            node.put("renderedPath", e.renderedPath());
            if (e.secretRef() != null) {
                node.put("secretRef", e.secretRef().toString());
            } else {
                node.set("value", value(e.valueJson()));
            }
            array.add(node);
        }
        return array;
    }

    private static JsonNode value(String json) {
        try {
            JsonNode parsed = MAPPER.readTree(json);
            if (parsed == null || parsed.isMissingNode()) {
                throw new IllegalArgumentException("a rendered value is not valid JSON");
            }
            return sorted(parsed);
        } catch (JsonProcessingException ex) {
            // the parser's message quotes the offending text: configuration, never repeated (I6)
            throw new IllegalArgumentException("a rendered value is not valid JSON");
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> byKey = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> f = it.next();
                byKey.put(f.getKey(), sorted(f.getValue()));
            }
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            byKey.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(sorted(child)));
            return out;
        }
        return node;
    }

    private static String text(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical JSON could not be written");
        }
    }
}

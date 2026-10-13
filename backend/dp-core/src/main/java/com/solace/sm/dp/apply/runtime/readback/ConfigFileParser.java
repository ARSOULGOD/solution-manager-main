package com.solace.sm.dp.apply.runtime.readback;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.dp.api.readback.ReadBackRefusedException;
import com.solace.sm.dp.api.readback.ReadBackRefusedException.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

/**
 * D-ACCEPT §3.2 / {@code BE-D40}: the read-back file, parsed with a pre-scan that refuses what Jackson reads wrongly.
 *
 * <p><b>Why two passes.</b> The renderer's parser is Jackson, but Jackson reads an alias as <i>its anchor's name</i>
 * ({@code y: *a} becomes {@code "a"}, no error), a merge key as a literal {@code "<<"} key, and a second document not at
 * all. Spring Boot, which reads the same file in the MI, honours all three. A hand-edited file can therefore be perfectly
 * valid to the MI and wrongly read here, and a wrongly read value would be accepted as the new desired configuration. So a
 * SnakeYAML <b>event</b> pass (no node graph is ever built, so there is nothing to expand) refuses an anchor, an alias, any
 * explicit tag, a second document, a non-mapping top, a non-scalar mapping key, an over-deep or over-large file, and then
 * Jackson reads the tree with strict duplicate detection (Boot refuses a duplicate key, and so does this).
 *
 * <p><b>{@code I6}.</b> The file can hold any secret, and a parser's message quotes the text it choked on. Every refusal is
 * a {@link ReadBackRefusedException} with a closed reason, fixed text and no cause; nothing a parser said survives.
 */
final class ConfigFileParser {

    /** The nesting bound (our own rule, D-ACCEPT §3.2): a rendered file is a few levels deep. */
    static final int MAX_DEPTH = 32;

    /** The event bound: far above any real file within the 1 MiB read bound, far below a bomb. */
    static final int MAX_EVENTS = 100_000;

    /** Boot refuses a duplicate key; Jackson refuses one only when asked, so it is asked. */
    private static final ObjectMapper STRICT = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ConfigFileParser() {
    }

    /** One open collection of the event pass: whether it is a mapping, and how many child nodes have started in it. */
    private static final class Frame {
        final boolean mapping;
        int children;

        Frame(boolean mapping) {
            this.mapping = mapping;
        }
    }

    /**
     * @return the parsed top-level mapping (possibly empty)
     * @throws ReadBackRefusedException {@code NOT_YAML}, {@code EMPTY}, {@code NOT_A_MAPPING}, {@code MULTI_DOCUMENT},
     *         {@code ANCHOR}, {@code ALIAS}, {@code TAG}, {@code DUPLICATE_KEY}, {@code TOO_DEEP} or {@code TOO_LARGE}
     */
    static JsonNode parse(byte[] bytes) {
        scan(bytes);
        JsonNode tree;
        try {
            tree = STRICT.readTree(bytes);
        } catch (StreamReadException e) {
            // Only the fixed prefix Jackson gives a duplicate is consulted; the rest of the text is dropped.
            throw refused(e.getOriginalMessage() != null && e.getOriginalMessage().startsWith("Duplicate field")
                    ? Reason.DUPLICATE_KEY : Reason.NOT_YAML);
        } catch (IOException | RuntimeException e) {
            throw refused(Reason.NOT_YAML);
        }
        if (tree == null || tree.isMissingNode()) {
            throw refused(Reason.EMPTY);
        }
        if (!tree.isObject()) {
            throw refused(Reason.NOT_A_MAPPING);
        }
        return tree;
    }

    /** The event pass. It reads no value into anything: it only classifies constructs. */
    private static void scan(byte[] bytes) {
        Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT));
        int documents = 0;
        int events = 0;
        boolean rootSeen = false;
        ArrayDeque<Frame> frames = new ArrayDeque<>();
        try {
            for (Event event : new Yaml(new LoaderOptions()).parse(reader)) {
                if (++events > MAX_EVENTS) {
                    throw refused(Reason.TOO_LARGE);
                }
                if (event instanceof DocumentStartEvent) {
                    if (++documents > 1) {
                        throw refused(Reason.MULTI_DOCUMENT);
                    }
                    continue;
                }
                if (event instanceof AliasEvent) {
                    throw refused(Reason.ALIAS);
                }
                if (event instanceof NodeEvent node && node.getAnchor() != null) {
                    throw refused(Reason.ANCHOR);
                }
                String tag = event instanceof ScalarEvent scalar ? scalar.getTag()
                        : event instanceof CollectionStartEvent collection ? collection.getTag() : null;
                if (tag != null) {
                    throw refused(Reason.TAG);
                }
                boolean startsNode = event instanceof ScalarEvent || event instanceof CollectionStartEvent;
                if (documents == 1 && !rootSeen && startsNode) {
                    rootSeen = true;
                    if (event instanceof ScalarEvent scalar && scalar.getValue().isEmpty()) {
                        throw refused(Reason.EMPTY);
                    }
                    if (!(event instanceof MappingStartEvent)) {
                        throw refused(Reason.NOT_A_MAPPING);
                    }
                }
                Frame enclosing = frames.peek();
                if (enclosing != null && startsNode) {
                    // children alternate key, value, key, value: an even child of a mapping is a KEY and must be a scalar
                    if (enclosing.mapping && enclosing.children % 2 == 0 && !(event instanceof ScalarEvent)) {
                        throw refused(Reason.NOT_YAML);
                    }
                    enclosing.children++;
                }
                if (event instanceof CollectionStartEvent) {
                    if (frames.size() >= MAX_DEPTH) {
                        throw refused(Reason.TOO_DEEP);
                    }
                    frames.push(new Frame(event instanceof MappingStartEvent));
                } else if (event.is(Event.ID.MappingEnd) || event.is(Event.ID.SequenceEnd)) {
                    frames.pop();
                }
            }
        } catch (ReadBackRefusedException e) {
            throw e;
        } catch (RuntimeException e) {
            // a scanner, parser or reader error (an undecodable byte arrives wrapped): its message quotes the file
            throw refused(Reason.NOT_YAML);
        }
        if (documents == 0) {
            throw refused(Reason.EMPTY);
        }
    }

    private static ReadBackRefusedException refused(Reason reason) {
        return new ReadBackRefusedException(reason);
    }
}

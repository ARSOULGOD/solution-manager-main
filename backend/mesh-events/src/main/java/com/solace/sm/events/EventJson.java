package com.solace.sm.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * THE serialization configuration for SM mesh events (plan 001 invariant: "Java serialization goes
 * through the single {@code EventJson.mapper()} — never a second {@code ObjectMapper}").
 *
 * <p>Two settings, each load-bearing against {@code contracts/events/}:
 * <ul>
 *   <li>{@link JavaTimeModule} + {@code WRITE_DATES_AS_TIMESTAMPS} disabled — {@code occurredAtUtc}
 *       must be an RFC 3339 string with an explicit offset, not an epoch number. The contract's
 *       {@code date-time} checker rejects anything else.</li>
 *   <li>{@code NON_NULL} inclusion — {@link EntityRef}'s inapplicable ids are absent on the wire,
 *       not {@code null}. The envelope schema is {@code additionalProperties: false} and the notify
 *       schema uses {@code not: {required: [...]}} to forbid ids that do not belong to a kind, so an
 *       emitted {@code "placementId": null} would be a contract violation, not a cosmetic one.</li>
 * </ul>
 *
 * <h2>Why the mapper itself is not handed out</h2>
 *
 * <p>An {@code ObjectMapper} is <b>mutable for its whole life</b>, builder-built or not — measured on
 * jackson-databind 2.21.4, not assumed: {@code setSerializationInclusion(ALWAYS)} changes the output
 * of a type not yet in the serializer cache, and {@code configure(WRITE_DATES_AS_TIMESTAMPS, true)}
 * or {@code enable(INDENT_OUTPUT)} changes the output of a type already cached. Handing out the
 * shared instance would let any caller anywhere in the backend silently re-shape the wire form for
 * every producer — the exact divergence this module exists to prevent, on the one chokepoint that
 * has no second implementation watching it.
 *
 * <p>So the shared instance is private and reachable only through three doors, each of which hands
 * out something the caller cannot use to change what {@link #write(EventEnvelope)} emits:
 * <ul>
 *   <li>{@link #write(EventEnvelope)} and {@link #writer()} — serialization, through an
 *       {@link ObjectWriter}, which Jackson defines as fully immutable and thread-safe;</li>
 *   <li>{@link #reader()} — deserialization to {@link EventEnvelope}, likewise immutable;</li>
 *   <li>{@link #mapper()} — a defensive {@code copy()} for the cases the reader/writer pair does not
 *       cover (tree operations, arbitrary target types, the golden tests). Callers get the one
 *       configuration; mutating their copy cannot reach the shared instance.</li>
 * </ul>
 *
 * <p><b>Not a security boundary — a reach-through exists.</b> Jackson's {@link ObjectWriter} and
 * {@link ObjectReader} expose the underlying factory, so
 * {@code writer().getFactory().getCodec()} recovers the shared {@code ObjectMapper} itself and a
 * determined caller can mutate it. What survives that is the part that matters here:
 * {@link #write(EventEnvelope)} and {@link #writer()} carry a <i>snapshot</i> of the configuration
 * taken when {@code WRITER} was built, so the wire form they produce is unaffected by a later
 * mutation of the shared instance. The doors are a guard against <i>accidental</i> reconfiguration
 * by a caller who just wanted a mapper — deliberate reach-through is possible, and is a code-review
 * matter, not something the type can prevent.
 *
 * <p>"One configuration" therefore still holds — every door derives from the same private mapper —
 * while "one <i>instance</i> anyone can reconfigure by accident" does not, which is the point.
 */
public final class EventJson {

    // Built through JsonMapper.builder rather than ObjectMapper's setters: the setters are
    // deprecated as of the Jackson the Boot 3.5.16 BOM manages (2.21.4).
    //
    // `defaultPropertyInclusion(Value.construct(NON_NULL, NON_NULL))` is the byte-for-byte
    // equivalent of the deprecated `setSerializationInclusion(NON_NULL)`: verified against
    // jackson-databind 2.21.4's own source, where that setter is exactly
    // `setPropertyInclusion(JsonInclude.Value.construct(incl, incl))` — value AND content
    // inclusion, not value alone (I2: read, not recalled).
    //
    // PRIVATE, and it stays private: see the class javadoc. Nothing in this class returns it.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                            JsonInclude.Include.NON_NULL))
            .build();

    private static final ObjectWriter WRITER = MAPPER.writer();

    private static final ObjectReader READER = MAPPER.readerFor(EventEnvelope.class);

    private EventJson() {
    }

    /**
     * The immutable writer carrying THE configuration. Prefer {@link #write(EventEnvelope)}; use this
     * where you need Jackson's writer API directly (e.g. writing to a stream).
     */
    public static ObjectWriter writer() {
        return WRITER;
    }

    /** The immutable reader for {@link EventEnvelope}, carrying THE configuration. */
    public static ObjectReader reader() {
        return READER;
    }

    /**
     * A fresh <b>copy</b> of the configured mapper, for tree operations and target types the
     * {@link #reader()} does not cover. The copy is yours: reconfiguring it cannot change what
     * {@link #write(EventEnvelope)} emits, and does not affect any other caller. Do not cache it as
     * if it were a shared singleton — call this once per component, or use {@link #writer()} /
     * {@link #reader()}, which need no copying.
     */
    public static ObjectMapper mapper() {
        return MAPPER.copy();
    }

    /** Serializes an envelope to its contract wire form. */
    public static String write(EventEnvelope e) {
        try {
            return WRITER.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            // An envelope is a closed record of contract-shaped values; failure here is a bug in
            // this module's configuration, not a condition a caller can act on.
            throw new IllegalStateException("failed to serialize event " + e.eventId(), ex);
        }
    }
}

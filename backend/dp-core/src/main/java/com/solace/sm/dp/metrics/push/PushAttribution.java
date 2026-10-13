package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.metrics.push.BrokerSampleDecoder.Body;
import com.solace.sm.dp.metrics.push.BrokerSampleDecoder.NumberRead;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Maps one broker event to a placement (plan 011 P11-5.1; the identity half of M-Q7 for path 2, OR-107 OQ-5 payload form,
 * OQ-20 (b), OR-92 / OQ-15). A pure function of the routing, the topic and the bytes.
 *
 * <p>For each route whose pattern matches the topic: the body is parsed once, the filter applied, the key read at the declared
 * pointer and looked up EXACTLY (case-sensitive, MF26) among the instances configured with it. The candidates' live
 * placements decide:
 * <ul>
 *   <li>exactly one: a {@link Hit}, attributed to that placement;</li>
 *   <li>none (no instance has the key, or the instance has no live placement): {@code undeclared_dropped};</li>
 *   <li>more than one (a re-target overlap, M-Q20, or one key configured twice): {@code ambiguous_placement}. No field in the
 *       event names a placement (facts F20), so no placement is guessed and none is "newest".</li>
 * </ul>
 * A body that does not parse, or carries no readable key, is {@code parse_failed}. All three are BROKER-CONNECTION-level drops
 * (no single placement to charge), returned as a set so one event is counted once per reason however many metrics it matched.
 * An event whose topic no route matches, or that a filter excludes, is not an error and counts nothing.
 */
public final class PushAttribution {

    /**
     * One attributed reading. {@code read} is null for a count extraction (the cumulative value is the Data Plane's); for a value
     * extraction it is the finite number or {@link NumberRead#NOT_A_FINITE_NUMBER}, which the ingest path counts against
     * {@code placementId} as {@code parse_failed} and stores nothing for.
     */
    public record Hit(UUID placementId, PushMetric metric, NumberRead read) {
    }

    public record Attributed(List<Hit> hits, Set<CollectorReason> brokerDrops) {

        public Attributed {
            hits = List.copyOf(hits);
            brokerDrops = Set.copyOf(brokerDrops);
        }
    }

    private final BrokerSampleDecoder decoder;

    public PushAttribution(BrokerSampleDecoder decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    public Attributed attribute(PushRouting routing, String topic, byte[] body) {
        List<Hit> hits = new ArrayList<>();
        Set<CollectorReason> drops = EnumSet.noneOf(CollectorReason.class);
        Body parsed = null;
        boolean parseTried = false;
        for (PushRouting.Route route : routing.routes()) {
            PushMetric metric = route.metric();
            if (!metric.topic().matches(topic)) {
                continue;
            }
            if (!parseTried) {
                parsed = decoder.parse(body).orElse(null);
                parseTried = true;
            }
            if (parsed == null) {
                drops.add(CollectorReason.PARSE_FAILED);
                continue;
            }
            if (metric.filter() != null && !parsed.matches(metric.filter())) {
                continue;
            }
            Optional<String> key = parsed.text(metric.keyPointer());
            if (key.isEmpty()) {
                drops.add(CollectorReason.PARSE_FAILED);
                continue;
            }
            List<PushRouting.Target> targets = route.byKey().get(key.get());
            List<UUID> placements = new ArrayList<>();
            if (targets != null) {
                targets.forEach(t -> placements.addAll(t.livePlacements()));
            }
            if (placements.isEmpty()) {
                drops.add(CollectorReason.UNDECLARED_DROPPED);
            } else if (placements.size() > 1) {
                drops.add(CollectorReason.AMBIGUOUS_PLACEMENT);
            } else {
                NumberRead read = metric.extraction() == PushMetric.Extraction.VALUE
                        ? parsed.number(metric.valuePointer()) : null;
                hits.add(new Hit(placements.get(0), metric, read));
            }
        }
        return new Attributed(hits, drops);
    }
}

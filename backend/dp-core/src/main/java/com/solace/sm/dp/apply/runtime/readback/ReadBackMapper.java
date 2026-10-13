package com.solace.sm.dp.apply.runtime.readback;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.api.readback.FieldPathTable;
import com.solace.sm.dp.api.readback.NotAcceptedReason;
import com.solace.sm.dp.api.readback.ReadBackCandidate;
import com.solace.sm.dp.api.readback.ReadBackRefusedException;
import com.solace.sm.dp.api.secret.SecretCompare;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * D-ACCEPT §3.3 / §3.5: from the parsed tree to declared fields, and a by-path account of everything that was not
 * adopted.
 *
 * <ol>
 *   <li><b>Flatten the way Boot does.</b> A node's path is its ancestors' keys joined by {@code .}, so a flat key
 *       {@code cache-engine.max-size} and a nested {@code cache-engine: {max-size: ...}} are one path. If both spellings of
 *       one declared path are present the file is refused ({@code ambiguous-key}): Boot's precedence between them is not
 *       something SM should guess.</li>
 *   <li><b>SM's own keys first.</b> A path {@link SmOverlayKeys} owns is stripped before any declared match.</li>
 *   <li><b>Match a declared path exactly</b> ({@code M-1}). A node whose path equals a declared path takes its WHOLE
 *       subtree as the value (how a list or map field arrives); a node that is not a declared path is descended into; a
 *       leaf that is nothing declared is reported by path. Boot's relaxed binding ({@code maxSize}, {@code max_size}) is
 *       <b>not</b> applied: those spellings are reported, not mapped.</li>
 * </ol>
 *
 * <p><b>{@code I6}.</b> The walk never reads a secret node's text and never reads an undeclared node's value at all; the
 * result names paths. A {@code yaml}-typed field is not read either. A secret node is handed, once, to a
 * {@link SecretNodeComparer} that answers {@code same} or {@code differs} (D-ACCEPT's {@code V-4}); the node is not kept and
 * no copy of its text is made here.
 */
final class ReadBackMapper {

    /** Bounds a reported path: a key in a hand-edited file is not ours to repeat at length. */
    static final int MAX_PATH = 256;

    record Mapped(FieldPathTable.Entry field, JsonNode value) {

        /** The value is configuration. */
        @Override
        public String toString() {
            return "Mapped[" + field.instanceKey() + ", " + field.fieldKey() + "]";
        }
    }

    /**
     * What the walk does with a secret field's node: compare it in memory with the stored secret and answer, nothing more.
     * The node may hold a secret in clear, so it is handed over for ONE call and never kept; the walker itself never reads
     * its text ({@code I6}).
     */
    @FunctionalInterface
    interface SecretNodeComparer {
        SecretCompare.Outcome compare(FieldPathTable.Entry field, JsonNode node);
    }

    /** The finished walk. {@code notAccepted} is sorted by path and already capped; a secret field is listed in it. */
    record Walked(List<Mapped> values, List<ReadBackCandidate.NotAccepted> notAccepted, boolean truncated) {
    }

    private final Map<String, FieldPathTable.Entry> declared;
    private final SmOverlayKeys overlay;
    private final SecretNodeComparer comparer;
    private final Set<String> claimed = new HashSet<>();
    private final List<Mapped> values = new ArrayList<>();
    private final List<ReadBackCandidate.NotAccepted> notAccepted = new ArrayList<>();

    private ReadBackMapper(FieldPathTable table, SmOverlayKeys overlay, SecretNodeComparer comparer) {
        this.declared = table.byPath();
        this.overlay = overlay;
        this.comparer = comparer;
    }

    /**
     * @throws ReadBackRefusedException {@code AMBIGUOUS_KEY} when one declared path is spelled two ways
     * @throws IllegalArgumentException when the table claims one path twice
     */
    static Walked walk(JsonNode root, FieldPathTable table, SmOverlayKeys overlay, SecretNodeComparer comparer) {
        ReadBackMapper mapper = new ReadBackMapper(Objects.requireNonNull(table, "table"),
                Objects.requireNonNull(overlay, "overlay"), Objects.requireNonNull(comparer, "comparer"));
        mapper.children(Objects.requireNonNull(root, "root"), "");
        return mapper.finish();
    }

    private void children(JsonNode object, String prefix) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            visit(prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(), field.getValue());
        }
    }

    private void visit(String path, JsonNode value) {
        if (overlay.owns(path)) {
            leaves(path, value, NotAcceptedReason.SM_OWNED_OVERLAY);
            return;
        }
        FieldPathTable.Entry field = declared.get(path);
        if (field != null) {
            if (!claimed.add(path)) {
                throw new ReadBackRefusedException(ReadBackRefusedException.Reason.AMBIGUOUS_KEY);
            }
            if (field.yaml()) {
                notAccepted.add(new ReadBackCandidate.NotAccepted(safe(path), field.instanceKey(), field.fieldKey(),
                        NotAcceptedReason.YAML_FIELD_NOT_ACCEPTED, null));
            } else if (field.secret()) {
                SecretCompare.Outcome outcome = comparer.compare(field, value);
                notAccepted.add(new ReadBackCandidate.NotAccepted(safe(path), field.instanceKey(), field.fieldKey(),
                        NotAcceptedReason.SECRET_NOT_READ,
                        outcome == SecretCompare.Outcome.UNAVAILABLE ? null : outcome));
            } else {
                values.add(new Mapped(field, value));
            }
            return;
        }
        if (value.isObject()) {
            children(value, path);
            return;
        }
        notAccepted.add(new ReadBackCandidate.NotAccepted(safe(path), null, null, NotAcceptedReason.UNDECLARED_KEY, null));
    }

    /** One entry per leaf under a node that is not adopted: the path of each, never what it holds. */
    private void leaves(String path, JsonNode value, NotAcceptedReason reason) {
        if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                leaves(path + "." + field.getKey(), field.getValue(), reason);
            }
            return;
        }
        notAccepted.add(new ReadBackCandidate.NotAccepted(safe(path), null, null, reason, null));
    }

    private Walked finish() {
        notAccepted.sort(Comparator.comparing(ReadBackCandidate.NotAccepted::path));
        boolean truncated = notAccepted.size() > ReadBackCandidate.MAX_NOT_ACCEPTED;
        List<ReadBackCandidate.NotAccepted> capped = truncated
                ? new ArrayList<>(notAccepted.subList(0, ReadBackCandidate.MAX_NOT_ACCEPTED)) : notAccepted;
        return new Walked(List.copyOf(values), List.copyOf(capped), truncated);
    }

    /** A path as reported: bounded, and with no control character a client could be confused by. */
    static String safe(String path) {
        String bounded = path.length() > MAX_PATH ? path.substring(0, MAX_PATH) : path;
        StringBuilder out = new StringBuilder(bounded.length());
        for (int i = 0; i < bounded.length(); i++) {
            char c = bounded.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        return out.toString();
    }
}

package com.solace.sm.dp.api.readback;

import com.solace.sm.dp.api.secret.SecretCompare;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the data plane read back from one placement's file and could map (D-ACCEPT, {@code BE-D39}/{@code BE-D41}).
 *
 * <p>⛔ <b>{@code I6}: it contains no secret value, by construction.</b> {@link #values()} holds only non-secret, non-yaml
 * declared fields; a secret field appears in {@link #notAccepted()} as a path and, when compared, as {@code SAME} or
 * {@code DIFFERS}, never as text. {@link #notAccepted()} lists paths only, never a value.
 *
 * @param values         declared non-secret fields the file carried, as JSON text; an absent field is simply not here
 * @param notAccepted    nodes not adopted, by path, capped at {@link #MAX_NOT_ACCEPTED}
 * @param truncated      whether {@code notAccepted} was cut at the cap
 * @param readBackHash   the keyed hash (OR-32) of the bytes read, for comparing with a rendering without holding the bytes
 * @param placementId    the placement whose file was read
 */
public record ReadBackCandidate(UUID placementId, List<MappedValue> values, List<NotAccepted> notAccepted,
        boolean truncated, String readBackHash) {

    /** The contract's {@code maxItems}. */
    public static final int MAX_NOT_ACCEPTED = 200;

    public record MappedValue(String instanceKey, String fieldKey, String valueJson) {

        public MappedValue {
            Objects.requireNonNull(instanceKey, "instanceKey");
            Objects.requireNonNull(fieldKey, "fieldKey");
            Objects.requireNonNull(valueJson, "valueJson");
        }

        /** The value is configuration: the key and field only. */
        @Override
        public String toString() {
            return "MappedValue[" + instanceKey + ", " + fieldKey + "]";
        }
    }

    /**
     * @param path        the dotted path as the file spells it, bounded; never a value
     * @param instanceKey the declared field's instance, when the path is a declared field
     * @param fieldKey    the declared field, when the path is one
     * @param comparison  for {@code SECRET_NOT_READ}: {@code SAME}/{@code DIFFERS} from the in-memory comparison, or null
     *                    when none was made (and then no claim is made about the file's value either way)
     */
    public record NotAccepted(String path, String instanceKey, String fieldKey, NotAcceptedReason reason,
            SecretCompare.Outcome comparison) {

        public NotAccepted {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(reason, "reason");
            if (comparison != null && reason != NotAcceptedReason.SECRET_NOT_READ) {
                throw new IllegalArgumentException("only a secret field is compared");
            }
        }
    }

    public ReadBackCandidate {
        Objects.requireNonNull(placementId, "placementId");
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        notAccepted = List.copyOf(Objects.requireNonNull(notAccepted, "notAccepted"));
        Objects.requireNonNull(readBackHash, "readBackHash");
        if (notAccepted.size() > MAX_NOT_ACCEPTED) {
            throw new IllegalArgumentException("notAccepted is capped");
        }
    }

    /** Sizes only: the values are configuration. */
    @Override
    public String toString() {
        return "ReadBackCandidate[" + values.size() + " values, " + notAccepted.size() + " not accepted]";
    }
}

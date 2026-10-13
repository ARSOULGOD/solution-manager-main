package com.solace.sm.dp.api.config;

import java.util.Objects;
import java.util.UUID;

/**
 * One entry of a generation's rendered set, as the SERVER re-projected it (OR-34): the component instance, the
 * rendered path, and EITHER a plain value (as JSON text) OR a reference to a secret in the DP secret store.
 *
 * <p><b>{@code I6}: the type cannot carry both.</b> A secret field is stored as {@code {"secretRef": "<uuid>"}}
 * and never as a value; a resolved secret exists only as ciphertext in {@code sm_secret} and, transiently, in the
 * bytes written to the host at render time. The caller (the gateway, T5.3) decides which entries are secret from
 * the package's own classification, and refuses a literal before it gets here.
 *
 * @param valueJson the plain value as JSON text; null exactly when {@code secretRef} is set
 * @param secretRef the secret reference; null exactly when {@code valueJson} is set
 */
public record RenderedEntryValue(String instanceKey, String renderedPath, String valueJson, UUID secretRef) {

    public RenderedEntryValue {
        Objects.requireNonNull(instanceKey, "instance key");
        Objects.requireNonNull(renderedPath, "rendered path");
        if ((valueJson == null) == (secretRef == null)) {
            throw new IllegalArgumentException(
                    "a rendered entry carries exactly one of a plain value or a secret reference");
        }
    }

    public static RenderedEntryValue plain(String instanceKey, String renderedPath, String valueJson) {
        return new RenderedEntryValue(instanceKey, renderedPath, valueJson, null);
    }

    public static RenderedEntryValue secret(String instanceKey, String renderedPath, UUID secretRef) {
        return new RenderedEntryValue(instanceKey, renderedPath, null, secretRef);
    }

    /** Never prints the value (it may be configuration): the key, the path and which kind. */
    @Override
    public String toString() {
        return "RenderedEntryValue[" + instanceKey + ", " + renderedPath + ", "
                + (secretRef != null ? "secretRef" : "value") + "]";
    }
}

package com.solace.sm.cp.api.catalog;

import java.util.Objects;

/**
 * One member of a package's {@code packageVersion} array. Mirrors {@code solution-package.schema.json}'s
 * {@code $defs/packageVersionId} exactly, so the vocabulary keeps one canonical home ({@code UI-D14}): an
 * integer is never {@code 0} or negative, and a string is never empty. {@code cp-api} carries no JSON library, so
 * the two shapes are one {@link Long} or {@link String} value, which a wire layer serializes as-is.
 */
public record PackageVersionId(Object value) {

    public PackageVersionId {
        Objects.requireNonNull(value, "value");
        if (value instanceof Long number) {
            if (number < 1) {
                throw new IllegalArgumentException("an integer package version id is at least 1");
            }
        } else if (value instanceof String text) {
            if (text.isEmpty()) {
                throw new IllegalArgumentException("a string package version id is not empty");
            }
        } else {
            throw new IllegalArgumentException("a package version id is an integer or a string");
        }
    }

    public static PackageVersionId of(long number) {
        return new PackageVersionId(number);
    }

    public static PackageVersionId of(String text) {
        return new PackageVersionId(text);
    }
}

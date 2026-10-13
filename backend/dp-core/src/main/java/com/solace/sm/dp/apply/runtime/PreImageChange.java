package com.solace.sm.dp.apply.runtime;

import java.util.List;
import java.util.Objects;

/**
 * What differs between the retained pre-apply copy of a config file as it was recorded and as it is now (OR-102).
 *
 * <p><b>Paths only, never a value, and never a secret-flagged path</b> ({@code I6}). {@code declaredPathsChanged} lists
 * the dotted paths the package declares non-secret whose recorded keyed digest no longer matches; a secret-flagged path
 * is never digested by name at retention, so it can never appear here; its value sits inside the keyed remainder
 * digest, so an edit to it is detected and read as other content (OR-113 (5)). A change anywhere else in the file (a secret-flagged
 * path, an undeclared key, a comment, whitespace, or a file that no longer parses) is reported only as
 * {@link #otherContentChanged}.
 *
 * @param declaredPathsChanged sorted, declared non-secret dotted paths whose content differs
 * @param otherContentChanged  whether the file also differs somewhere outside those paths
 */
public record PreImageChange(List<String> declaredPathsChanged, boolean otherContentChanged) {

    public PreImageChange {
        Objects.requireNonNull(declaredPathsChanged, "changed paths");
        declaredPathsChanged = List.copyOf(declaredPathsChanged);
        if (declaredPathsChanged.isEmpty() && !otherContentChanged) {
            throw new IllegalArgumentException("a change must name a path or say that other content changed");
        }
    }

    /** One line for a log: the paths, and the words "other content changed" when it did. No value. */
    public String describe() {
        StringBuilder text = new StringBuilder("retained pre-image changed");
        if (!declaredPathsChanged.isEmpty()) {
            text.append("; declared paths differing: ").append(String.join(", ", declaredPathsChanged));
        }
        if (otherContentChanged) {
            text.append("; other content changed");
        }
        return text.toString();
    }
}

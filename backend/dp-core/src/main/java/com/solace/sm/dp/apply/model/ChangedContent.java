package com.solace.sm.dp.apply.model;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What an apply observation says about a retained pre-apply copy of a config file that changed since it was retained
 * ({@code OR-108} (1), carrying {@code OR-102}'s report to the console): the declared, non-secret dotted paths whose
 * content differs, and whether the file also differs anywhere else.
 *
 * <p><b>Paths only, never a value ({@code I6}).</b> A string is kept as a path only if it has the grammar of one
 * (letters, digits and {@code . _ : / @ + -}, at most {@value #MAX_PATH_LENGTH} characters), which cannot express a space,
 * a quote, {@code =} or a newline; a string that does not is not stored and counts as {@code otherContentChanged}. The
 * list is bounded at {@value #MAX_PATHS}; a longer one is cut in path order and {@code pathsTruncated} says so, so a cut
 * list is never presented as the whole.
 *
 * @param paths                sorted, distinct, at most {@value #MAX_PATHS}
 * @param pathsTruncated       more declared paths differed than {@code paths} lists
 * @param otherContentChanged  the file also differs somewhere that is not a listed declared path
 */
public record ChangedContent(List<String> paths, boolean pathsTruncated, boolean otherContentChanged) {

    /** The contract's {@code changedPaths} {@code maxItems}, and the table's CHECK. */
    public static final int MAX_PATHS = 50;
    public static final int MAX_PATH_LENGTH = 255;

    /** The same grammar as the {@code pre_image_change_shape} CHECK. */
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9._:/@+-]{1," + MAX_PATH_LENGTH + "}$");

    public ChangedContent {
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        if (paths.size() > MAX_PATHS) {
            throw new IllegalArgumentException("at most " + MAX_PATHS + " paths");
        }
        for (String path : paths) {
            if (!PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("a changed path must be a dotted path, never content");
            }
        }
        if (paths.isEmpty() && !otherContentChanged) {
            throw new IllegalArgumentException("a change must name a path or say that other content changed");
        }
        if (pathsTruncated && paths.size() != MAX_PATHS) {
            throw new IllegalArgumentException("only a full list can be truncated");
        }
    }

    /**
     * Builds the observation of a change from the adapter's report: sorted, de-duplicated, grammar-checked and bounded.
     * Never throws for a path it cannot keep; it reports it as other content.
     */
    public static ChangedContent of(List<String> declaredPathsChanged, boolean otherContentChanged) {
        TreeSet<String> kept = new TreeSet<>();
        boolean other = otherContentChanged;
        for (String path : Objects.requireNonNull(declaredPathsChanged, "paths")) {
            if (path != null && PATH.matcher(path).matches()) {
                kept.add(path);
            } else {
                other = true;
            }
        }
        boolean truncated = kept.size() > MAX_PATHS;
        List<String> listed = kept.stream().limit(MAX_PATHS).toList();
        return new ChangedContent(listed, truncated, other);
    }
}

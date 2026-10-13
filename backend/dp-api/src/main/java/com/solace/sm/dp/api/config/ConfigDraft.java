package com.solace.sm.dp.api.config;

/**
 * What {@code getDesigner} re-projects (OR-34): the latest generation's component selection and draft values,
 * which is why the draft is stored at all. Secret fields hold references only (I6).
 *
 * @param layoutJson OR-96: the designer's canvas layout from the per-instance presentation record, as a JSON object
 *                   of instance key to {@code {"x","y"}}; {@code "{}"} when none was saved. It is NOT part of the
 *                   generation: it is read from outside {@code config_generation}.
 */
public record ConfigDraft(long generation, String packageSolution, String packageVersion,
        String componentSelectionJson, String draftValuesJson, String contentDigest, String layoutJson) {

    public ConfigDraft {
        java.util.Objects.requireNonNull(layoutJson, "layout");
    }

    /** A draft with no saved layout. */
    public ConfigDraft(long generation, String packageSolution, String packageVersion,
            String componentSelectionJson, String draftValuesJson, String contentDigest) {
        this(generation, packageSolution, packageVersion, componentSelectionJson, draftValuesJson, contentDigest, "{}");
    }

    /** The draft values are configuration: never in a log line. */
    @Override
    public String toString() {
        return "ConfigDraft[generation=" + generation + ", " + packageSolution + "@" + packageVersion + "]";
    }
}

package com.solace.sm.dp.api.readback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Every declared field of the instance's selected components, active or not, keyed by the dotted path the renderer
 * writes it at (D-ACCEPT §3.3). The gateway builds it from the package and the selection; the data plane never reads the
 * package, so this is how it learns which paths mean what and which are secret.
 *
 * <p>It carries <b>no value</b>. A secret field's entry carries the {@code secretRef} the instance currently holds (a
 * reference, never a value), which is what the in-memory comparison ({@code V-4}) is made against.
 *
 * @param entries every declared, non-virtual field; a duplicate rendered path is refused (two instances of one component
 *        type cannot both be rendered at one path, and the renderer refuses the same)
 */
public record FieldPathTable(List<Entry> entries) {

    /**
     * @param type       the package's field type ({@code string}, {@code int}, {@code yaml}, ...)
     * @param secret     whether the package marks the field secret ({@code UI-D18}'s two spellings)
     * @param secretRef  for a secret field, the reference the instance's current draft holds, or null
     */
    public record Entry(String instanceKey, String fieldKey, String renderedPath, String type, boolean secret,
            UUID secretRef) {

        public Entry {
            Objects.requireNonNull(instanceKey, "instanceKey");
            Objects.requireNonNull(fieldKey, "fieldKey");
            Objects.requireNonNull(renderedPath, "renderedPath");
            Objects.requireNonNull(type, "type");
            if (!secret && secretRef != null) {
                throw new IllegalArgumentException("only a secret field carries a secret reference");
            }
        }

        public boolean yaml() {
            return "yaml".equals(type);
        }
    }

    public FieldPathTable {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** By rendered path; throws {@link IllegalArgumentException} when two entries claim one path. */
    public Map<String, Entry> byPath() {
        Map<String, Entry> out = new HashMap<>();
        for (Entry e : entries) {
            if (out.put(e.renderedPath(), e) != null) {
                throw new IllegalArgumentException("a rendered path is claimed twice");
            }
        }
        return out;
    }

    /** Names the size, never a path or a reference. */
    @Override
    public String toString() {
        return "FieldPathTable[" + entries.size() + " fields]";
    }
}

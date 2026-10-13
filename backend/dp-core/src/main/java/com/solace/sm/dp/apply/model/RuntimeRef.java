package com.solace.sm.dp.apply.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code apply_attempt.runtime_ref} — the adapter-scoped reference fields of design §2.3.
 *
 * <p>§2.3 is explicit about what belongs here: "runtime-specific identifiers (container id, unit name,
 * namespace/workload ref) live in adapter-scoped reference fields, never as columns the core model or
 * API depends on". {@link Key} is that list, closed: the nine runtime identifiers §2.3's adapter table
 * names and nothing else. There is no free-{@code String} key any more, so an adapter cannot invent a
 * tenth field, and {@code Evidence} refuses an unknown key on the way back out of the {@code jsonb} too
 * — a row written by some other build cannot introduce one either.
 *
 * <p><b>What this type is: a SHAPE control.</b> Values must match
 * {@code ^[A-Za-z0-9._:/@+-]{1,128}$}, which admits {@code 9f2c1e…}, {@code solace-cache.service},
 * {@code prod/cache-engine} and {@code cache-config-g17}, and admits neither whitespace, nor quotes,
 * nor {@code =}, nor a backslash. That excludes an environment line ({@code KEY=value}), a
 * {@code Bearer …} header, padded base64, PEM, a JSON blob, a stack trace and any exception text.
 *
 * <p><b>What this type is NOT: a content control.</b> A bare identifier-shaped token passes — a compact
 * JWT ({@code eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig}) has only base64url characters and dots, a hex
 * secret is hex. If a secret is placed in a value, the grammar accepts it. The {@code I6} CONTENT
 * obligation on {@code runtime_ref} (and on {@code pre_baseline} and {@code error_class}) therefore
 * belongs to the ADAPTER: each adapter's {@code RuntimeRef} builder is the only writer of these
 * fields, and each must be tested against sentinel secrets drawn from its own inputs — docker inspect
 * output, the process environment, mounted files. That is a named obligation Plan 005 carries, not
 * something this record discharges.
 *
 * <p>The map is defensively copied, unmodifiable, and keeps insertion order (a {@link LinkedHashMap})
 * so the JSON this value serializes to is stable and diffable. That is a property of the VALUE, not of
 * the column: PostgreSQL's {@code jsonb} normalises key order on storage (shortest key first), which is
 * why equality here is map equality and no test asserts a stored key order.
 *
 * @param fields the adapter's own identifiers for the thing an attempt touched
 */
public record RuntimeRef(Map<Key, String> fields) {

    /**
     * The closed set of runtime identifiers — design §2.3's adapter table names, and the whole of what
     * {@code runtime_ref} may hold.
     *
     * <p>Each member carries the {@link #wire()} name used as the JSON key, so the Java constant can be
     * {@code SCREAMING_SNAKE} while the stored document keeps the lower-camel names the design writes.
     * The wire names are part of the stored contract: renaming one changes every {@code jsonb} document
     * this build reads.
     */
    public enum Key {

        /** Docker/OCI container id. */
        CONTAINER_ID("containerId"),

        /** The image the container was started from, as {@code sha256:…}. */
        IMAGE_DIGEST("imageDigest"),

        /** systemd unit, for the VM adapter. */
        UNIT_NAME("unitName"),

        /** The host the unit or container runs on. */
        HOST("host"),

        /** Kubernetes namespace. */
        NAMESPACE("namespace"),

        /** The Deployment/StatefulSet the pod belongs to. */
        WORKLOAD("workload"),

        /** The individual pod. */
        POD_NAME("podName"),

        /** The generation-named ConfigMap a materialize step wrote. */
        CONFIG_MAP_NAME("configMapName"),

        /** Where the rendered config was placed. */
        MOUNT_PATH("mountPath");

        private final String wire;

        Key(String wire) {
            this.wire = wire;
        }

        /** The JSON key this member is stored under. */
        public String wire() {
            return wire;
        }

        /**
         * @param wire a key read out of a stored {@code runtime_ref} document
         * @return the member that owns it
         * @throws IllegalArgumentException naming the closed set — never echoing the value, which
         *                                  came from a database column and may be anything
         */
        public static Key ofWire(String wire) {
            for (Key k : values()) {
                if (k.wire.equals(wire)) {
                    return k;
                }
            }
            throw new IllegalArgumentException("a runtime ref key must be one of " + wireNames()
                    + " (design §2.3's adapter identifiers) — the offending key is deliberately not "
                    + "repeated here (I6)");
        }

        /** The wire names, in declaration order, for a refusal message. */
        static String wireNames() {
            StringBuilder b = new StringBuilder();
            for (Key k : values()) {
                b.append(b.length() == 0 ? "" : ", ").append(k.wire);
            }
            return b.toString();
        }
    }

    public RuntimeRef {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("a runtime ref carries at least one of design §2.3's "
                    + "adapter identifiers, was " + (fields == null ? "null" : "empty"));
        }
        Map<Key, String> copy = new LinkedHashMap<>();
        for (Map.Entry<Key, String> e : fields.entrySet()) {
            Key key = e.getKey();
            if (key == null) {
                throw new IllegalArgumentException("a runtime ref key must be one of "
                        + Key.wireNames() + ", was null");
            }
            ValueGrammar.require(ValueGrammar.VALUE, e.getValue(),
                    "the runtime ref value of '" + key.wire() + "'",
                    "an adapter-scoped IDENTIFIER (design §2.3) — a container id, a unit name, a "
                            + "namespace, a generation-named ConfigMap. The grammar is a SHAPE check; "
                            + "keeping a secret out of the VALUE is the adapter's obligation (I6)");
            copy.put(key, e.getValue());
        }
        fields = Collections.unmodifiableMap(copy);
    }

    /** One identifier. */
    public static RuntimeRef of(Key key, String value) {
        return new RuntimeRef(pairs(key, value));
    }

    /** Two. Overloads rather than varargs, because alternating types are not expressible as one. */
    public static RuntimeRef of(Key k1, String v1, Key k2, String v2) {
        Map<Key, String> m = pairs(k1, v1);
        m.put(k2, v2);
        return new RuntimeRef(m);
    }

    /** Three. */
    public static RuntimeRef of(Key k1, String v1, Key k2, String v2, Key k3, String v3) {
        Map<Key, String> m = pairs(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return new RuntimeRef(m);
    }

    /** Four. Beyond this, an adapter builds the map and calls the canonical constructor. */
    public static RuntimeRef of(Key k1, String v1, Key k2, String v2, Key k3, String v3, Key k4,
            String v4) {
        Map<Key, String> m = pairs(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        return new RuntimeRef(m);
    }

    private static Map<Key, String> pairs(Key key, String value) {
        Map<Key, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}

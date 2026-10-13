package com.solace.sm.dp.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads the master key from {@code SM_DP_MASTER_KEY}, or from the file named by {@code SM_DP_MASTER_KEY_FILE}
 * (owner ruling {@code OR-85}, D-KEY OQ-2: identical validation for both), with its label in
 * {@code SM_DP_MASTER_KEY_ID}. During a rotation, {@code SM_DP_MASTER_KEY_PREVIOUS} (or {@code _FILE}) and
 * {@code SM_DP_MASTER_KEY_PREVIOUS_ID} name the old one.
 *
 * <p><b>The value is a plain string decoded here</b> (D-KEY 4.1, 9 note 3): never bound into a typed
 * configuration field, so no framework conversion failure can echo it. Every refusal is this class's own
 * {@link IllegalStateException}; it names the variable and the rule, prints no character of the value and
 * not the length it found, and chains no cause. The standard base64 decoder is strict (no trimming, no
 * URL-safe alphabet), so a pasted trailing newline is a loud failure and not a silent success.
 *
 * <p>The lookup is injected, so the Spring wiring can hand it {@code Environment::getProperty} and the
 * standalone commands can hand it {@code System::getenv}. An empty variable counts as unset, so a container
 * runtime that declares both forms with one empty still works.
 */
public final class EnvironmentMasterKeyProvider implements MasterKeyProvider {

    public static final String KEY = "SM_DP_MASTER_KEY";
    public static final String KEY_FILE = KEY + "_FILE";
    public static final String KEY_ID = KEY + "_ID";
    public static final String PREVIOUS = KEY + "_PREVIOUS";
    public static final String PREVIOUS_FILE = PREVIOUS + "_FILE";
    public static final String PREVIOUS_ID = PREVIOUS + "_ID";

    /** A 32-byte key is 44 base64 characters; this is generous, and keeps a wrong file from being read whole. */
    static final int MAX_FILE_BYTES = 1024;

    private static final String RULE = "must be standard base64 of exactly 32 bytes, with no surrounding whitespace";

    private final Function<String, String> lookup;

    public EnvironmentMasterKeyProvider(Function<String, String> lookup) {
        this.lookup = lookup;
    }

    @Override
    public MasterKeyRing load() {
        KeyId currentId = id(KEY_ID, true).orElseThrow();
        byte[] currentBytes = read(KEY, KEY_FILE, true).orElseThrow();
        MasterKey current = MasterKey.of(currentId, currentBytes);
        Arrays.fill(currentBytes, (byte) 0);
        Optional<byte[]> previousBytes = read(PREVIOUS, PREVIOUS_FILE, false);
        Optional<KeyId> previousId = id(PREVIOUS_ID, false);
        if (previousBytes.isPresent() != previousId.isPresent()) {
            throw refusal(previousBytes.isPresent() ? PREVIOUS_ID + " is required when " + PREVIOUS + " (or "
                    + PREVIOUS_FILE + ") is set" : PREVIOUS + " (or " + PREVIOUS_FILE + ") is required when "
                    + PREVIOUS_ID + " is set");
        }
        if (previousId.isPresent() && previousId.get().equals(current.id())) {
            throw refusal(PREVIOUS_ID + " must differ from " + KEY_ID + ": two keys cannot share one id");
        }
        Optional<MasterKey> previous = previousId.map(i -> MasterKey.of(i, previousBytes.get()));
        previousBytes.ifPresent(b -> Arrays.fill(b, (byte) 0));
        return new MasterKeyRing(current, previous);
    }

    private Optional<KeyId> id(String variable, boolean required) {
        String raw = lookup.apply(variable);
        if (raw == null || raw.isEmpty()) {
            if (required) {
                throw refusal(variable + " is not set: it labels the master key (" + KeyId.GRAMMAR.pattern() + ")");
            }
            return Optional.empty();
        }
        try {
            return Optional.of(new KeyId(raw));
        } catch (IllegalArgumentException e) {
            throw refusal(variable + " must match " + KeyId.GRAMMAR.pattern());
        }
    }

    /** The decoded key from {@code variable} or {@code fileVariable}; the caller zeroes the returned bytes. */
    private Optional<byte[]> read(String variable, String fileVariable, boolean required) {
        String direct = unsetIfEmpty(lookup.apply(variable));
        String path = unsetIfEmpty(lookup.apply(fileVariable));
        if (direct != null && path != null) {
            throw refusal("set exactly one of " + variable + " and " + fileVariable + ", not both");
        }
        if (direct == null && path == null) {
            if (required) {
                throw refusal("neither " + variable + " nor " + fileVariable + " is set: the DP secret store "
                        + "refuses to start without its master key");
            }
            return Optional.empty();
        }
        if (direct != null) {
            return Optional.of(decode(direct, variable));
        }
        return Optional.of(decode(readFile(path, fileVariable), fileVariable));
    }

    private static String unsetIfEmpty(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private static String readFile(String path, String variable) {
        try {
            Path p = Path.of(path);
            if (Files.size(p) > MAX_FILE_BYTES) {
                throw refusal(variable + " names a file that is too large to hold a key");
            }
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            // not chained: an IOException's message carries the path, and a path is not for logs we do not control
            throw refusal(variable + " names a file that cannot be read");
        }
    }

    private static byte[] decode(String value, String variable) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw refusal(variable + " " + RULE);
        }
        if (bytes.length != MasterKey.LENGTH) {
            Arrays.fill(bytes, (byte) 0);
            throw refusal(variable + " " + RULE);
        }
        return bytes;
    }

    private static IllegalStateException refusal(String message) {
        return new IllegalStateException(message);
    }
}

package com.solace.sm.dp.metrics.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The storage gate's evidence lines (plan 011 P11-8.1): {@code MEASURED}, {@code EXTRAPOLATED}, {@code FINDING} and
 * {@code NOTE}. Each is printed, appended to a log in the output directory, and (MEASURED and EXTRAPOLATED) stored in
 * {@link BenchResults} as {@code <step>.<key>} for a later step. A registered secret is masked in every line (I6).
 */
public final class Evidence {

    public static final String LOG = "storage-gate-evidence.log";

    private final BenchResults results;
    private final PrintStream out;
    private final List<String> secrets = new ArrayList<>();

    public Evidence(BenchResults results, PrintStream out) {
        this.results = results;
        this.out = out;
    }

    /** Masks this value in everything printed or logged from now on. */
    public void redact(String secret) {
        if (secret != null && !secret.isBlank()) {
            secrets.add(secret);
        }
    }

    public void measured(String step, String key, Object value) {
        results.put(step + "." + key, String.valueOf(value));
        emit("MEASURED step=" + step + " " + key + "=" + value);
    }

    public void extrapolated(String step, String key, Object value, String label) {
        results.put(step + "." + key, String.valueOf(value));
        emit("EXTRAPOLATED step=" + step + " " + key + "=" + value + " (" + label + ")");
    }

    public void finding(String step, String text) {
        emit("FINDING step=" + step + " " + text);
    }

    public void note(String step, String text) {
        emit("NOTE step=" + step + " " + text);
    }

    private void emit(String line) {
        String masked = line;
        for (String secret : secrets) {
            masked = masked.replace(secret, "***");
        }
        out.println(masked);
        try {
            Files.writeString(results.dir().resolve(LOG), Instant.now() + " " + masked + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

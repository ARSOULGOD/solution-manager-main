package com.solace.sm.dp.metrics.bench;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * What the storage gate measured, kept in a file (plan 011 P11-8.1). Each step may run in its own JVM (the integrator
 * stops after Step 0b to read the disk projection), so a later step reads an earlier step's figures from here and never
 * from memory. Holds measurements and ids only: never a credential.
 */
public final class BenchResults {

    static final String FILE = "bench-results.properties";

    private final Path file;

    public BenchResults(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.file = dir.resolve(FILE);
    }

    public Path dir() {
        return file.getParent();
    }

    public synchronized void put(String key, String value) {
        Properties all = load();
        all.setProperty(key, value);
        try (OutputStream out = Files.newOutputStream(file)) {
            all.store(out, "plan 011 P11-8.1 storage gate measurements");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Optional<String> get(String key) {
        return Optional.ofNullable(load().getProperty(key));
    }

    public OptionalLong getLong(String key) {
        return get(key).map(v -> OptionalLong.of(Long.parseLong(v))).orElse(OptionalLong.empty());
    }

    public Optional<Double> getDouble(String key) {
        return get(key).map(Double::parseDouble);
    }

    private Properties load() {
        Properties all = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                all.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return all;
    }
}

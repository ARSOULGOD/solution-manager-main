package com.solace.sm.dp.metrics.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs the operator's own command that prints {@code vmstat} lines (for example {@code ssh user@host vmstat 5}) while a
 * rollup runs (plan 011 P11-8.1 Step 2b), and keeps what it printed. The command is split on whitespace and run WITHOUT a
 * shell, and its output is never echoed: only {@link Vmstat}'s summary is reported. With no command it is inert.
 */
public final class CpuSampler implements AutoCloseable {

    static final int FIRST_LINE_SECONDS = 15;

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private Process process;
    private Thread reader;
    private final CountDownLatch firstLine = new CountDownLatch(1);

    public static CpuSampler start(List<String> command) {
        CpuSampler sampler = new CpuSampler();
        if (command.isEmpty()) {
            return sampler;
        }
        try {
            sampler.process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the CPU command (sm.bench.cpu-command): " + e.getClass().getSimpleName(), e);
        }
        sampler.reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(sampler.process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    sampler.lines.add(line);
                    sampler.firstLine.countDown();
                }
            } catch (IOException ended) {
                // the process was stopped
            }
        }, "bench-cpu-sampler");
        sampler.reader.setDaemon(true);
        sampler.reader.start();
        try {
            // A command that prints nothing is found out NOW, not after an hour's rollup has run unobserved.
            if (!sampler.firstLine.await(FIRST_LINE_SECONDS, TimeUnit.SECONDS)) {
                sampler.close();
                throw new IllegalStateException("the CPU command (sm.bench.cpu-command) printed nothing in " + FIRST_LINE_SECONDS + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sampler.close();
            throw new IllegalStateException("interrupted while waiting for the CPU command", e);
        }
        return sampler;
    }

    /** What the command has printed so far, for a caller that waits for its first samples. */
    List<String> linesSoFar() {
        return List.copyOf(lines);
    }

    public boolean active() {
        return process != null;
    }

    /** Stops the command and summarises what it printed. */
    public Vmstat.Summary stop() {
        close();
        return Vmstat.summarize(lines);
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                reader.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeDiagnostics;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded, redacted Docker logs over the pinned SSH transport. */
public final class SshDockerDiagnostics implements RuntimeDiagnostics {
    private static final Pattern SENSITIVE_LINE = Pattern.compile(
            "(?i).*(password|secret|token|authorization|private[ _-]?key|ssh key).*" );
    private final SshDockerPlacementCatalog catalog;
    private final SshDockerTransport transport;

    public SshDockerDiagnostics(SshDockerPlacementCatalog catalog, SshCredentialSource credentials,
            TargetAddressPolicy addressPolicy) {
        this(catalog, credentials, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    public SshDockerDiagnostics(SshDockerPlacementCatalog catalog, SshCredentialSource credentials,
            TargetAddressPolicy addressPolicy, HostKeyTypeRecorder recorder) {
        this(catalog, new SshjDockerTransport(credentials, addressPolicy, recorder));
    }

    SshDockerDiagnostics(SshDockerPlacementCatalog catalog, SshDockerTransport transport) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    @Override
    public DiagnosticLog recentLogs(PlacementRef placement, int requestedLines) {
        int limit = Math.min(Math.max(requestedLines, 1), 200);
        String output = transport.logs(catalog.lookup(placement), limit);
        String[] raw = output.split("\\R", -1);
        List<String> lines = new ArrayList<>();
        for (String line : raw) {
            if (!line.isEmpty()) {
                lines.add(SENSITIVE_LINE.matcher(line).matches() ? "[REDACTED]" : line);
            }
        }
        boolean truncated = lines.size() > limit;
        return new DiagnosticLog(lines.subList(Math.max(0, lines.size() - limit), lines.size()), truncated);
    }

    public LogPage logsPage(PlacementRef placement, int requestedLines, Instant after, Instant before) {
        if (after != null && before != null) {
            throw new IllegalArgumentException("Only one log cursor may be supplied");
        }
        int limit = Math.min(Math.max(requestedLines, 1), 500);
        String output = transport.logs(catalog.lookup(placement), limit + 1, after, before);
        List<LogLine> parsed = new ArrayList<>();
        for (String raw : output.split("\\R", -1)) {
            if (raw.isBlank()) {
                continue;
            }
            int separator = raw.indexOf(' ');
            if (separator <= 0) {
                continue;
            }
            try {
                Instant timestamp = Instant.parse(raw.substring(0, separator));
                if ((after != null && !timestamp.isAfter(after)) || (before != null && !timestamp.isBefore(before))) {
                    continue;
                }
                String message = raw.substring(separator + 1);
                parsed.add(new LogLine(timestamp,
                        SENSITIVE_LINE.matcher(message).matches() ? "[REDACTED]" : message));
            } catch (RuntimeException ignored) {
                // Docker can interleave daemon diagnostics; only timestamped container lines are exposed.
            }
        }
        parsed.sort(java.util.Comparator.comparing(LogLine::timestamp));
        boolean overflow = parsed.size() > limit;
        boolean hasOlder = after == null && overflow;
        List<LogLine> lines;
        if (!overflow) {
            lines = List.copyOf(parsed);
        } else if (after != null) {
            // Advance from the oldest unread line so a busy interval can be drained over multiple polls.
            lines = List.copyOf(parsed.subList(0, limit));
        } else {
            lines = List.copyOf(parsed.subList(parsed.size() - limit, parsed.size()));
        }
        Instant nextBefore = hasOlder && !lines.isEmpty() ? lines.get(0).timestamp() : null;
        return new LogPage(lines, nextBefore, hasOlder);
    }

    public record LogLine(Instant timestamp, String message) {}

    public record LogPage(List<LogLine> lines, Instant nextBefore, boolean hasOlder) {}
}

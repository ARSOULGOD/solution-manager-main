package com.solace.sm.dp.apply.runtime;

/**
 * Read-only, bounded runtime observations that do not widen {@link RuntimeAdapter}'s apply seam.
 * Every future transport, including an agent route, implements this sibling port.
 */
public interface RuntimeDiagnostics {
    DiagnosticLog recentLogs(PlacementRef placement, int requestedLines);

    record DiagnosticLog(java.util.List<String> lines, boolean truncated) {
        public DiagnosticLog { lines = java.util.List.copyOf(lines); }
    }
}

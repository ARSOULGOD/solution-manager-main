package com.solace.sm.dp.metrics.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * The host's CPU while a rollup runs (plan 011 P11-8.1 Step 2b), read from {@code vmstat} lines the operator's own command
 * printed (for example over SSH). Busy is {@code us + sy}. The first sample {@code vmstat} prints is the average since
 * boot, so it is dropped when there are others.
 */
public final class Vmstat {

    private Vmstat() {
    }

    public record Summary(int samples, double meanBusyPercent, double maxBusyPercent, double meanStealPercent) {
    }

    public static Summary summarize(List<String> lines) {
        int us = -1;
        int sy = -1;
        int st = -1;
        List<double[]> rows = new ArrayList<>();
        boolean sinceBootDrop = false;
        boolean headerSeen = false;
        for (String line : lines) {
            String[] cells = line.trim().split("\\s+");
            if (java.util.Arrays.asList(cells).contains("us") && java.util.Arrays.asList(cells).contains("sy")) {
                List<String> names = java.util.Arrays.asList(cells);
                us = names.indexOf("us");
                sy = names.indexOf("sy");
                st = names.indexOf("st");
                if (!headerSeen) {
                    sinceBootDrop = true;               // only the first banner is followed by the since-boot line
                    headerSeen = true;
                }
                continue;
            }
            if (us < 0 || cells.length <= Math.max(us, sy)) {
                continue;
            }
            try {
                double busy = Double.parseDouble(cells[us]) + Double.parseDouble(cells[sy]);
                double steal = st >= 0 && cells.length > st ? Double.parseDouble(cells[st]) : 0.0;
                rows.add(new double[] {busy, steal});
            } catch (NumberFormatException notASample) {
                // a banner or a stray line: not a sample
            }
        }
        if (rows.size() > 1 && sinceBootDrop) {
            rows.remove(0);                                    // the since-boot average
        }
        if (rows.isEmpty()) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN);
        }
        double busy = 0;
        double max = 0;
        double steal = 0;
        for (double[] r : rows) {
            busy += r[0];
            steal += r[1];
            max = Math.max(max, r[0]);
        }
        return new Summary(rows.size(), busy / rows.size(), max, steal / rows.size());
    }
}

package com.solace.sm.dp.metrics.bench;

import java.util.ArrayList;
import java.util.List;

/** The storage gate's arithmetic (plan 011 P11-8.1), apart from any database so it can be tested and mutated alone. */
public final class GateMath {

    private GateMath() {
    }

    /** Nearest-rank percentile; {@code p} in (0, 1]. Does not reorder the caller's list. */
    public static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("no values");
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.min(sorted.size(), Math.max(1, rank)) - 1);
    }

    /**
     * Step 0b: the month's disk need before it is loaded: {@code days} x the day's bytes (with indexes) plus {@code days} x
     * the day's WAL (the plan's conservative reading: the month's WAL "until checkpointed") plus the floor that is never
     * to be crossed.
     */
    public static long monthProjection(long dayBytes, long dayWalBytes, int days, long floorBytes) {
        return days * dayBytes + days * dayWalBytes + floorBytes;
    }

    /** A projection (which already includes the floor) fits when it does not exceed the measured free disk. */
    public static boolean fits(long projectionBytes, long freeBytes) {
        return projectionBytes <= freeBytes;
    }

    /** The scaled option put to the owner when the month does not fit: how many whole days do. */
    public static int largestFittingDays(long dayBytes, long dayWalBytes, long freeBytes, long floorBytes) {
        long perDay = dayBytes + dayWalBytes;
        if (perDay <= 0 || freeBytes <= floorBytes) {
            return 0;
        }
        return (int) ((freeBytes - floorBytes) / perDay);
    }

    /** Step 6a: what the 105 M-row stream's data needs on the host, and whether the free disk covers it. */
    public record StreamDisk(long rows, long rowBytes, long walBytes, long floorBytes, long requiredBytes, long freeBytes) {
        public boolean fits() {
            return GateMath.fits(requiredBytes, freeBytes);
        }
    }

    public static StreamDisk streamDisk(long rows, double bytesPerRow, double walBytesPerRow, long floorBytes,
            long freeAfterDropBytes) {
        long rowBytes = Math.round(rows * bytesPerRow);
        long walBytes = Math.round(rows * walBytesPerRow);
        return new StreamDisk(rows, rowBytes, walBytes, floorBytes, rowBytes + walBytes + floorBytes, freeAfterDropBytes);
    }

    /** A size or time carried to six months, with the label that says it is not a measurement. */
    public record Extrapolation(long bytes, String label) {
    }

    public static Extrapolation sixMonths(long monthBytes) {
        return new Extrapolation(monthBytes * 6, "EXTRAPOLATED: the measured month x 6, not a measurement");
    }
}

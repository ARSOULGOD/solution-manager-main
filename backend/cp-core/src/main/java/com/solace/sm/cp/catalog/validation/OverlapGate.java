package com.solace.sm.cp.catalog.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate 3 — two packages of ONE solution must not claim overlapping {@code appliesTo} ranges
 * ({@code validate-package.py:gate3_overlap}). It runs across the packages already loaded for a catalog entry
 * plus the candidate, not per file. Ranges are half-open {@code [lo, hi)}, so {@code >=2.0 <2.4} and
 * {@code >=2.4 <3.0} touch without overlapping.
 *
 * <p>The range parser is the Python's minimal one on purpose ("enough for gate 3"): tokens starting {@code >=}
 * set the lower bound, tokens starting {@code <} the upper, numeric components only; an absent or unreadable
 * bound is open. It is a second implementation of a parser the Python calls minimal — a malformed range reads
 * as open rather than as an error, exactly as there.
 */
public final class OverlapGate {

    /** A package under its caller's name (a file name or a catalog id); the name is what failures cite. */
    public record NamedPackage(String name, JsonNode pkg) {
    }

    private OverlapGate() {
    }

    public static List<GateFailure> check(List<NamedPackage> packages) {
        Map<String, List<NamedPackage>> bySolution = new LinkedHashMap<>();
        for (NamedPackage p : packages) {
            JsonNode solution = p.pkg().get("solution");
            bySolution.computeIfAbsent(solution == null ? "null" : solution.asText(), k -> new ArrayList<>()).add(p);
        }
        List<GateFailure> errs = new ArrayList<>();
        for (Map.Entry<String, List<NamedPackage>> entry : bySolution.entrySet()) {
            List<NamedPackage> items = entry.getValue();
            for (int i = 0; i < items.size(); i++) {
                for (int j = i + 1; j < items.size(); j++) {
                    NamedPackage a = items.get(i);
                    NamedPackage b = items.get(j);
                    Range ra = range(a.pkg());
                    Range rb = range(b.pkg());
                    if (compare(ra.lo, rb.hi) < 0 && compare(rb.lo, ra.hi) < 0) {
                        errs.add(new GateFailure("3", "overlap: " + entry.getKey() + ": " + a.name()
                                + " and " + b.name() + " claim overlapping appliesTo ranges"));
                    }
                }
            }
        }
        return errs;
    }

    private record Range(List<BigInteger> lo, List<BigInteger> hi) {
    }

    private static Range range(JsonNode pkg) {
        JsonNode applies = pkg.get("appliesTo");
        String spec = applies != null && applies.isTextual() ? applies.textValue() : "";
        List<BigInteger> lo = null;
        List<BigInteger> hi = null;
        for (String tok : spec.trim().split("\\s+")) {
            if (tok.isEmpty()) {
                continue;
            }
            List<BigInteger> num = numbers(tok);
            if (tok.startsWith(">=")) {
                lo = num;
            } else if (tok.startsWith("<")) {
                hi = num;
            }
        }
        // Python: `lo or (0,)` - an empty tuple is falsy, so an unreadable bound is open too.
        return new Range(lo == null || lo.isEmpty() ? List.of(BigInteger.ZERO) : lo,
                hi == null || hi.isEmpty() ? List.of(BigInteger.valueOf(9999)) : hi);
    }

    /** {@code tok.lstrip("><=").split(".")} keeping only all-digit parts. */
    private static List<BigInteger> numbers(String tok) {
        int start = 0;
        while (start < tok.length() && "><=".indexOf(tok.charAt(start)) >= 0) {
            start++;
        }
        List<BigInteger> out = new ArrayList<>();
        for (String part : tok.substring(start).split("\\.", -1)) {
            if (!part.isEmpty() && part.chars().allMatch(c -> c >= '0' && c <= '9')) {
                out.add(new BigInteger(part));
            }
        }
        return out;
    }

    /** Python tuple ordering: element-wise, and a proper prefix sorts first. */
    private static int compare(List<BigInteger> a, List<BigInteger> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }
}

package com.solace.sm.dp.apply.persistence;

import java.util.ArrayList;
import java.util.List;

/**
 * One SQL splitter, used by BOTH sides of {@link SketchMirrorTest} and by the harness that executes
 * Phase 0. Two splitters would make the mirror test compare two parsers instead of two scripts.
 *
 * <p>It is quote-aware in the two ways this DDL needs: single-quoted literals (so a {@code --}
 * inside one is not read as a comment, and a {@code ;} inside one does not end a statement) and
 * {@code $$} dollar quoting (the PL/pgSQL bodies of §3.1 are full of semicolons). It is NOT a
 * general PostgreSQL parser — no tagged dollar quotes, no C-style block comments, no {@code E''}
 * escapes — because §3.1 uses none of those, and {@link #assertNoDialectSurprises} fails loudly if
 * that ever stops being true.
 */
final class SqlStatements {

    private SqlStatements() {
    }

    /** Comment-stripped, whitespace-normalised, non-empty statements, in file order. */
    static List<String> of(String sql) {
        String stripped = stripLineComments(sql);
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        boolean inDollar = false;
        int i = 0;
        while (i < stripped.length()) {
            char c = stripped.charAt(i);
            if (inDollar) {
                if (stripped.startsWith("$$", i)) {
                    current.append("$$");
                    inDollar = false;
                    i += 2;
                    continue;
                }
                current.append(c);
                i++;
                continue;
            }
            if (inQuote) {
                current.append(c);
                inQuote = c != '\'';
                i++;
                continue;
            }
            if (c == '\'') {
                inQuote = true;
                current.append(c);
                i++;
                continue;
            }
            if (stripped.startsWith("$$", i)) {
                current.append("$$");
                inDollar = true;
                i += 2;
                continue;
            }
            if (c == ';') {
                add(statements, current);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        add(statements, current);
        return statements;
    }

    private static void add(List<String> out, CharSequence raw) {
        String s = raw.toString().replaceAll("\\s+", " ").trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    /** Drops {@code --} to end of line, except inside a single-quoted literal. */
    private static String stripLineComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inQuote = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (inQuote) {
                out.append(c);
                inQuote = c != '\'';
                i++;
                continue;
            }
            if (c == '\'') {
                inQuote = true;
                out.append(c);
                i++;
                continue;
            }
            if (sql.startsWith("--", i)) {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? sql.length() : nl;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The splitter's own preconditions, asserted rather than assumed: no single-quoted literal in
     * the script contains {@code --} (which the comment stripper would have to reason about beyond
     * quote state) and no tagged dollar quote appears. Returns a human-readable complaint, or null.
     */
    static String assertNoDialectSurprises(String rawSql) {
        // Comments must be removed BEFORE any quote-tracking pass, and that ordering is measured,
        // not stylistic: §3.1's prose comments contain English apostrophes ("PG 14's default left
        // CREATE on schema public open to PUBLIC"), so a quote tracker run over the raw text opens a
        // string literal on a comment and reports nonsense for the rest of the file.
        String sql = stripLineComments(rawSql);
        boolean inQuote = false;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inQuote) {
                if (c == '\'') {
                    if (literal.indexOf("--") >= 0) {
                        return "a single-quoted literal contains '--': " + literal;
                    }
                    literal.setLength(0);
                    inQuote = false;
                } else {
                    literal.append(c);
                }
                continue;
            }
            if (c == '\'') {
                inQuote = true;
            } else if (c == '$' && i + 1 < sql.length() && sql.charAt(i + 1) != '$'
                    && Character.isLetterOrDigit(sql.charAt(i + 1))) {
                return "a tagged dollar quote appears near: "
                        + sql.substring(i, Math.min(sql.length(), i + 40));
            }
        }
        return null;
    }
}

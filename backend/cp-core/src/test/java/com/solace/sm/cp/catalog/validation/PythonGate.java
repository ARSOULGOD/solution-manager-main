package com.solace.sm.cp.catalog.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the normative {@code contracts/validation/validate-package.py} as a subprocess and reads its text.
 * The Python has no machine-readable mode (it prints text only), so this parses exactly the lines it prints:
 * <ul>
 *   <li>{@code --expect-fail}: {@code "  PASS (rejected as intended) <name>"} then {@code "         -> gateN ..."};
 *       a mismatch prints {@code "  FAIL (rejected by gateN, expected gateM) <name>"}; an accepted file prints
 *       {@code "  FAIL (accepted, should have been rejected) <name>"}.
 *   <li>plain: {@code "  PASS <name>  components=..."} or {@code "  FAIL <name>  (n gate violations)"} followed by up to
 *       twelve {@code "         gateN ..."} lines.
 * </ul>
 */
final class PythonGate {

    /** The Python's verdict on one file: gate tokens as printed ({@code gate1}, {@code gate5b}), in order. */
    record Verdict(boolean passed, List<String> gateTokens) {
        String first() {
            return gateTokens.isEmpty() ? null : gateTokens.get(0);
        }
    }

    private PythonGate() {
    }

    /** Null when python3 and jsonschema are usable, else the reason they are not. */
    static String unavailableReason() {
        try {
            Process p = new ProcessBuilder("python3", "-c", "import jsonschema").redirectErrorStream(true).start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "python3 timed out";
            }
            return p.exitValue() == 0 ? null : "python3 cannot import jsonschema";
        } catch (IOException | InterruptedException e) {
            return "python3 is not runnable: " + e.getClass().getSimpleName();
        }
    }

    /** Positive run (no --expect-fail), reading every violation line the Python prints. */
    static Verdict run(Path file, boolean devMode) {
        List<String> lines = exec(file, false, devMode);
        List<String> tokens = new ArrayList<>();
        boolean passed = false;
        for (String line : lines) {
            if (line.startsWith("  PASS ")) {
                passed = true;
            } else if (line.startsWith("         gate")) {
                tokens.add(line.trim().split("\\s+")[0]);
            }
        }
        return new Verdict(passed, tokens);
    }

    /** {@code --expect-fail} run: the gate token of the first error, or {@code ACCEPTED} when it was not rejected. */
    static String expectFailFirstToken(Path file) {
        List<String> lines = exec(file, true, false);
        for (String line : lines) {
            if (line.startsWith("         -> ")) {
                return line.substring("         -> ".length()).trim().split("\\s+")[0];
            }
        }
        return "ACCEPTED";
    }

    /** The Python's gate 3 over several files given together: the number of {@code FAIL gate3  overlap:} lines. */
    static long overlapFailures(List<Path> files) {
        List<String> cmd = new ArrayList<>(List.of("python3", ValidationFixtures.validation().resolve("validate-package.py").toString()));
        files.forEach(f -> cmd.add(f.toString()));
        return output(cmd).stream().filter(l -> l.startsWith("  FAIL gate3  overlap:")).count();
    }

    private static List<String> exec(Path file, boolean expectFail, boolean devMode) {
        List<String> cmd = new ArrayList<>(List.of("python3", ValidationFixtures.validation().resolve("validate-package.py").toString(), file.toString()));
        if (expectFail) {
            cmd.add("--expect-fail");
        }
        if (devMode) {
            cmd.add("--dev-mode");
        }
        return output(cmd);
    }

    private static List<String> output(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("validate-package.py timed out");
            }
            return out.lines().toList();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("could not run validate-package.py", e);
        }
    }
}

package com.solace.sm.cp.identity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Prints the Argon2id hash of one secret read from standard input — the value the install runbook passes to the migration job as
 * {@code SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH}. The secret is read from stdin, never from an argument (an argument is visible to
 * every process on the host and lands in shell history), and is neither printed nor logged. Exit {@code 2} for an empty secret.
 */
public final class CredentialHashMain {

    private CredentialHashMain() {
    }

    public static void main(String[] args) {
        System.exit(run(System.in, System.out, System.err));
    }

    static int run(InputStream in, PrintStream out, PrintStream err) {
        try {
            String secret = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
            if (secret == null || secret.isBlank()) {
                err.println("no secret on standard input: pipe it in, e.g. printf '%s' \"$SECRET\" | sm-hash-credential");
                return 2;
            }
            out.println(new CredentialHasher().hash(secret.toCharArray()));
            return 0;
        } catch (java.io.IOException | RuntimeException e) {
            err.println("could not hash the credential: " + e.getClass().getSimpleName());
            return 1;
        }
    }
}

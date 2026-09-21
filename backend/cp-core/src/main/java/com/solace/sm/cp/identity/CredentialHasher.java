package com.solace.sm.cp.identity;

import java.nio.CharBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Argon2id credential hashing with bounded verification capacity. */
public final class CredentialHasher implements CredentialVerifier {

    private static final int MEMORY_KIB = 384 * 1024;
    private static final int ITERATIONS = 4;
    private static final int PARALLELISM = 1;
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int MAX_CONCURRENT_VERIFICATIONS = 4;
    private static final Pattern PARAMETERS = Pattern.compile(
            "^\\{argon2id}\\$argon2id\\$v=\\d+\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$.*$");

    private final PasswordEncoder encoder;
    private final Semaphore credentialWorkCapacity;
    private final int maxConcurrentVerifications;

    public CredentialHasher() {
        this(argon2idEncoder(), MAX_CONCURRENT_VERIFICATIONS);
    }

    CredentialHasher(PasswordEncoder encoder, int maxConcurrentVerifications) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        if (maxConcurrentVerifications < 1) {
            throw new IllegalArgumentException("max concurrent verifications must be positive");
        }
        this.credentialWorkCapacity = new Semaphore(maxConcurrentVerifications);
        this.maxConcurrentVerifications = maxConcurrentVerifications;
    }

    @Override
    public String hash(char[] credential) {
        acquireCapacity();
        try {
            return encoder.encode(CharBuffer.wrap(Objects.requireNonNull(credential, "credential")));
        } finally {
            credentialWorkCapacity.release();
        }
    }

    @Override
    public boolean verify(char[] candidate, String encodedCredential) {
        acquireCapacity();
        try {
            return encoder.matches(CharBuffer.wrap(Objects.requireNonNull(candidate, "candidate")),
                    Objects.requireNonNull(encodedCredential, "encoded credential"));
        } finally {
            credentialWorkCapacity.release();
        }
    }

    public int maxConcurrentVerifications() {
        return maxConcurrentVerifications;
    }

    public static Parameters parametersOf(String encodedCredential) {
        Matcher matcher = PARAMETERS.matcher(Objects.requireNonNull(encodedCredential, "encoded credential"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("credential hash is not an {argon2id} hash with encoded parameters");
        }
        return new Parameters(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    private void acquireCapacity() {
        if (!credentialWorkCapacity.tryAcquire()) {
            throw new VerificationCapacityExceededException();
        }
    }

    private static PasswordEncoder argon2idEncoder() {
        PasswordEncoder argon2id = new Argon2PasswordEncoder(
                SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
        return new DelegatingPasswordEncoder("argon2id", Map.of("argon2id", argon2id));
    }

    public record Parameters(int memoryKib, int iterations, int parallelism) {
    }

    public static final class VerificationCapacityExceededException extends RuntimeException {
        public VerificationCapacityExceededException() {
            super("credential verification capacity is exhausted");
        }
    }
}

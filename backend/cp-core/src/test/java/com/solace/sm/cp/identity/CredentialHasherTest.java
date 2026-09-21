package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class CredentialHasherTest {

    private final CredentialHasher hasher = new CredentialHasher();

    @Test
    void theEncoderIsArgon2idAndNotBcrypt() {
        assertThat(hasher.hash("pw".toCharArray())).startsWith("{argon2id}$argon2id$");
    }

    @Test
    void theParametersAreTheMeasuredOnesAndNeverBelowTheFloor() {
        CredentialHasher.Parameters parameters = CredentialHasher.parametersOf(hasher.hash("pw".toCharArray()));

        assertThat(parameters.memoryKib()).isEqualTo(384 * 1024);
        assertThat(parameters.iterations()).isEqualTo(4);
        assertThat(parameters.parallelism()).isEqualTo(1);
        assertThat(parameters.memoryKib()).isGreaterThanOrEqualTo(19 * 1024);
        assertThat(parameters.iterations()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theHashRecordsItsOwnParametersAndUsesASalt() {
        String first = hasher.hash("correct horse".toCharArray());
        String second = hasher.hash("correct horse".toCharArray());

        assertThat(CredentialHasher.parametersOf(first)).isNotNull();
        assertThat(hasher.verify("correct horse".toCharArray(), first)).isTrue();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void verificationRefusesImmediatelyWhenTheConcurrencyCapIsSaturated() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CredentialHasher capped = new CredentialHasher(new BlockingEncoder(entered, release), 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> inFlight = executor.submit(() -> capped.verify("first".toCharArray(), "stored"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> capped.verify("second".toCharArray(), "stored"))
                    .isInstanceOf(CredentialHasher.VerificationCapacityExceededException.class);

            release.countDown();
            assertThat(inFlight.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void hashingRefusesImmediatelyWhenTheSharedConcurrencyCapIsSaturated() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CredentialHasher capped = new CredentialHasher(new BlockingEncoder(entered, release), 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> inFlight = executor.submit(() -> capped.hash("first".toCharArray()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> capped.hash("second".toCharArray()))
                    .isInstanceOf(CredentialHasher.VerificationCapacityExceededException.class);

            release.countDown();
            assertThat(inFlight.get(5, TimeUnit.SECONDS)).isEqualTo("stored");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void theHasherHasNoLoggerField() {
        assertThat(CredentialHasher.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getName().contains("Logger"));
    }

    private static final class BlockingEncoder implements PasswordEncoder {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingEncoder(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            awaitRelease();
            return "stored";
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            awaitRelease();
            return true;
        }

        private void awaitRelease() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("credential work was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}

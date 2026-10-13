package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code Credentials} binds the {@link JdbcUrls} guard and never prints the query string, even though
 * the guard already guarantees it carries no credential — a fix for a URL credential leak: nothing
 * previously stopped {@code ?password=}/{@code ?user=} from reaching the URL, and {@code toString}
 * printed the whole thing.
 */
class SmControlPropertiesTest {

    private static final String SENTINEL = "SNTL-props-4b7e";

    @Test
    void aCleanUrlIsAccepted() {
        assertThat(new SmControlProperties.Credentials(
                "jdbc:postgresql://db:5432/sm_control?sslmode=verify-full", "sm_cp_runtime", "secret").url())
                .isEqualTo("jdbc:postgresql://db:5432/sm_control?sslmode=verify-full");
    }

    @Test
    void aUrlCarryingAPasswordParameterIsRefusedAtBindingTime() {
        assertThatThrownBy(() -> new SmControlProperties.Credentials(
                "jdbc:postgresql://db:5432/sm_control?password=" + SENTINEL, "sm_cp_runtime", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the allowlist")
                .hasMessageNotContaining(SENTINEL);
    }

    @Test
    void aUrlCarryingAUserParameterIsRefusedAtBindingTime() {
        assertThatThrownBy(() -> new SmControlProperties.Credentials(
                "jdbc:postgresql://db:5432/sm_control?user=" + SENTINEL, "sm_cp_runtime", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the allowlist")
                .hasMessageNotContaining(SENTINEL);
    }

    @Test
    void aUrlCarryingUserinfoIsRefusedAtBindingTime() {
        assertThatThrownBy(() -> new SmControlProperties.Credentials(
                "jdbc:postgresql://alice:" + SENTINEL + "@db:5432/sm_control", "sm_cp_runtime", "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host part")
                .hasMessageNotContaining(SENTINEL);
    }

    @Test
    void toStringContainsNoQueryStringEvenForAnAllowlistedParameter() {
        SmControlProperties.Credentials credentials = new SmControlProperties.Credentials(
                "jdbc:postgresql://db:5432/sm_control?sslmode=verify-full&ApplicationName=" + SENTINEL,
                "sm_cp_runtime", "secret-password-" + SENTINEL);

        String text = credentials.toString();

        assertThat(text).doesNotContain("?").doesNotContain(SENTINEL).doesNotContain("secret-password")
                .contains("jdbc:postgresql://db:5432/sm_control").contains("username=****").contains("password=****");
    }
}

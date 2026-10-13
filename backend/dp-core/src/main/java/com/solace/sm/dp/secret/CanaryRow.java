package com.solace.sm.dp.secret;

import java.time.Instant;

/** One {@code sm_secret.kek_canary} row: install-level, so it carries no tenant (D-KEY 4.3, B4). */
public record CanaryRow(SealedSecret sealed, Instant createdAt) {
}

package com.solace.sm.dp.api.apply;

import java.util.UUID;

/** An admitted request: its id, and the {@code stateVersion} it was stamped with when it committed. */
public record ApplyAcceptedView(UUID applyId, long stateVersion) {
}

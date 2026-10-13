package com.solace.sm.dp.api.apply;

import com.solace.sm.dp.api.logs.LoggerLevel;
import java.util.Objects;
import java.util.UUID;

/**
 * A log level changed through configuration (OR-103, P2): one placement, the package-declared logger, the level. The
 * apply strategy is not a member: a level change restarts nothing ({@code manual}), so it takes effect at the next restart.
 *
 * @param placementId  required: v1 refuses instance-wide apply (BE-D23, OR-60)
 * @param loggerPrefix the component's package-declared {@code loggerPrefix}, resolved by the gateway; the data plane
 *                     checks it against the logger-name grammar and never reads a package
 * @param level        one of the contract's five levels
 * @param actor        the session's principal id - never a request field
 */
public record SetLogLevelCommand(UUID placementId, String loggerPrefix, LoggerLevel level, UUID actor) {

    public SetLogLevelCommand {
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(loggerPrefix, "loggerPrefix");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(actor, "actor");
    }
}

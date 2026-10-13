package com.solace.sm.gateway.http;

import java.util.List;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code AcceptedRuntimeConfig}: {@code StagedConfig} plus {@code unchanged} (as
 * {@code stageConfig}'s 200), and what D-ACCEPT adds ({@code BE-D42}).
 *
 * <p>⛔ <b>{@code I6}: nothing here is a value.</b> {@code notAccepted} lists PATHS and closed reasons; a secret field is
 * listed as {@code secret-not-read} with, at most, {@code comparison: same|differs}, the answer to an in-memory comparison
 * of the file's value with the stored one. The value itself never leaves the data plane.
 *
 * @param stateVersion                the instance's AFTER the call; when {@code unchanged} nothing was written and it is
 *                                    the version the call started from
 * @param unchanged                   OR-33 guard 1: the mappable content equals the latest generation, so no row was written
 * @param placementId                 the placement whose file was read (the config of record is per instance, so accepting
 *                                    one placement's file changes the instance's desired configuration for every placement)
 * @param fileBytesDifferFromRendered X-1: accept writes nothing to the host, so the file still differs from SM's rendering
 *                                    of {@code generation} until that generation is applied
 * @param truncated                   {@code notAccepted} was cut at 200
 * @param notAccepted                 what the file carried that was not adopted, by path; the next apply rewrites the file
 *                                    from the generation, so everything listed here is removed from the host by it
 */
public record AcceptedRuntimeConfig(long stateVersion, @WireOptional Availability availability, long generation,
        boolean unchanged, UUID placementId, boolean fileBytesDifferFromRendered, boolean truncated,
        List<NotAccepted> notAccepted) {

    public AcceptedRuntimeConfig {
        notAccepted = List.copyOf(notAccepted);
    }

    /** One node of the file that was not adopted. {@code comparison} is present only for {@code secret-not-read}. */
    public record NotAccepted(String path, @WireOptional String fieldKey, String reason, @WireOptional String comparison) {
    }
}

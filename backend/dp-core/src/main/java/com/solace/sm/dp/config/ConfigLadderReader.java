package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.ConfigLadderView;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads an instance's place on the ladder from the config of record and the apply journal (plan 010 T5.3, T6.5;
 * OR-37). Reads only: it never writes and never touches a host.
 *
 * <p>{@code desired} and {@code rendered} come from the newest generation; {@code persisted} and
 * {@code restart-observed} from the journal's own evidence ({@link ConfigLadder} states the rules); and
 * {@code effective-verified} is declared absent with {@link ConfigLadder#EFFECTIVE_UNVERIFIED}. The ladder is derived
 * on every read, so a stage after an apply drops it back to {@code rendered} with no write.
 */
public final class ConfigLadderReader {

    private final ConfigRecordService config;
    private final LadderRows rows;

    public ConfigLadderReader(ConfigRecordService config, LadderRows rows) {
        this.config = Objects.requireNonNull(config, "config");
        this.rows = Objects.requireNonNull(rows, "rows");
    }

    public ConfigLadderView read(UUID organizationId, UUID instanceId) {
        var latest = config.latestDraft(organizationId, instanceId);
        LadderEvidence evidence = latest.isEmpty() ? LadderEvidence.none()
                : rows.evidence(new TenantId(organizationId), new InstanceId(instanceId));
        return ConfigLadder.view(latest, evidence);
    }
}

package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.agent.AgentCommandPayloadSource;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.CommandPayload;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Where the bytes of a command come from at {@code pull} (plan 015 T3.1; {@code OR-126} A5, {@code AGW-D26}): the in-memory
 * hand-off first (the render the apply engine already made, parked by {@link AgentRuntimeAdapter}), and only on a MISS (SM
 * restarted since it was parked, or the entry was dropped at the bound) a re-render through {@link RenderedConfigSource}.
 *
 * <p>A re-render is sent only if its KEYED hash equals the hash recorded on the command ({@code agent_command.config_hash});
 * otherwise the pull is refused {@code agent.payload.render.changed} and the command ends {@code REFUSED}, so the agent is never
 * handed bytes the journal did not promise (the same rule {@code ApplyExecutor#redrive} applies to a re-drive). The generation to
 * re-render is the apply request's, recovered from the intent key's leading {@code applyId} through
 * {@link AgentConfigGenerations}: a command row carries the WIRE generation, which is not a config generation.
 *
 * <p>A REMATERIALIZE cannot be rebuilt after a miss: its expected-current digest lived only in the hand-off, and the row stores
 * neither it nor any plain digest ({@code OR-32}). It is refused the same way; the indeterminate reconciler re-drives it with a
 * fresh observation.
 *
 * <p>Operations with no payload (PREFLIGHT, RESTART, STOP) carry zero bytes and the digest of zero bytes, which is what the
 * agent verifies for every command ({@code commands.go:242}). That digest is a constant of the empty input, not of any config.
 */
public final class HandoffAgentCommandPayloadSource implements AgentCommandPayloadSource {

    /** {@code sha256:} of zero bytes. */
    static final String EMPTY_SHA256 = AgentHashBridge.plainSha256(new byte[0]);

    private final AgentPayloadHandoff handoff;
    private final RenderedConfigSource renderer;
    private final ConfigHasher hasher;
    private final AgentConfigGenerations generations;
    private final AgentHashBridge bridge;

    public HandoffAgentCommandPayloadSource(AgentPayloadHandoff handoff, RenderedConfigSource renderer, ConfigHasher hasher,
            AgentConfigGenerations generations, AgentHashBridge bridge) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.generations = Objects.requireNonNull(generations, "generations");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public CommandPayload payloadFor(Command command) {
        if (!isConfig(command.operation())) {
            return new CommandPayload(new byte[0], EMPTY_SHA256, null);
        }
        UUID tenant = command.placement().tenantId();
        Optional<AgentPayloadHandoff.Held> held = handoff.find(tenant, command.commandId().value(), command.intentKey(), command.operation());
        if (held.isPresent()) {
            AgentPayloadHandoff.Held h = held.get();
            return new CommandPayload(h.bytes(), h.plainSha256(), h.expectedCurrentSha256());
        }
        if (command.operation() == AgentOperation.REMATERIALIZE) {
            throw changed();
        }
        return reRendered(command);
    }

    /** The digest a notice carries: known only while the hand-off holds the payload; after a restart the agent compares at pull. */
    @Override
    public Optional<String> plainDigestFor(Command command) {
        if (!isConfig(command.operation())) {
            return Optional.of(EMPTY_SHA256);
        }
        return handoff.find(command.placement().tenantId(), command.commandId().value(), command.intentKey(), command.operation())
                .map(AgentPayloadHandoff.Held::plainSha256);
    }

    private CommandPayload reRendered(Command command) {
        PlacementRef placement = new PlacementRef(new TenantId(command.placement().tenantId()),
                new InstanceId(command.placement().instanceId()), new PlacementId(command.placement().placementId()));
        OptionalLong generation = generationOf(command, placement.tenant());
        if (generation.isEmpty() || command.keyedConfigHash() == null) {
            throw changed();
        }
        byte[] bytes = null;
        try {
            RenderedConfig rendered = renderer.render(placement, generation.getAsLong());
            bytes = rendered.bytes();
            if (!command.keyedConfigHash().equals(hasher.hash(placement.tenant(), bytes))) {
                throw changed();
            }
            String plain = AgentHashBridge.plainSha256(bytes);
            bridge.record(placement, plain, command.keyedConfigHash());
            return new CommandPayload(bytes, plain, null);
        } catch (GatewayPortException refused) {
            throw refused;
        } catch (RuntimeException unrenderable) {
            throw changed();                // the renderer's message is never read (I6)
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);       // CommandPayload copied what it needs
            }
        }
    }

    private OptionalLong generationOf(Command command, TenantId tenant) {
        int colon = command.intentKey().indexOf(':');
        if (colon <= 0) {
            return OptionalLong.empty();
        }
        try {
            return generations.generationOfApply(tenant, new ApplyId(UUID.fromString(command.intentKey().substring(0, colon))));
        } catch (IllegalArgumentException malformed) {
            return OptionalLong.empty();
        }
    }

    private static boolean isConfig(AgentOperation operation) {
        return operation == AgentOperation.APPLY || operation == AgentOperation.REMATERIALIZE;
    }

    private static GatewayPortException changed() {
        return new GatewayPortException(GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED, "the payload is no longer available");
    }

    @Override
    public String toString() {
        return "HandoffAgentCommandPayloadSource";
    }
}

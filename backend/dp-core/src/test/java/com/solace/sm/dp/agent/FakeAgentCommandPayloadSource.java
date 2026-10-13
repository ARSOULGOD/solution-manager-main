package com.solace.sm.dp.agent;

import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandPayload;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fake {@link AgentCommandPayloadSource} plan 015 T2.1b ships for every test that pulls a command: an in-memory hand-off keyed
 * by command id, exactly the shape T3.1's real source has before an SM restart. A command nobody registered bytes for is refused
 * {@code agent.payload.render.changed}, which is what the real source answers when a re-render's keyed hash differs.
 */
public final class FakeAgentCommandPayloadSource implements AgentCommandPayloadSource {

    private record Held(byte[] bytes, String plainSha256, String expectedCurrentSha256) {
    }

    private final Map<CommandId, Held> held = new ConcurrentHashMap<>();

    /** Hands {@code bytes} (and the plain digest the agent compares) over for {@code id}. */
    public void register(CommandId id, byte[] bytes, String plainSha256) {
        held.put(id, new Held(bytes.clone(), plainSha256, null));
    }

    @Override
    public CommandPayload payloadFor(Command command) {
        Held h = held.get(command.commandId());
        if (h == null) {
            throw new GatewayPortException(GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED, "no payload is held for the command");
        }
        return new CommandPayload(h.bytes(), h.plainSha256(), h.expectedCurrentSha256());
    }

    @Override
    public Optional<String> plainDigestFor(Command command) {
        return Optional.ofNullable(held.get(command.commandId())).map(Held::plainSha256);
    }
}

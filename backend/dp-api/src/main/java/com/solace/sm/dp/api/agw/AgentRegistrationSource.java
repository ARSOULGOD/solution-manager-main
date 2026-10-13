package com.solace.sm.dp.api.agw;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface AgentRegistrationSource {
    Map<UUID, AgentRegistrationState> current(Collection<UUID> agentIds);
}

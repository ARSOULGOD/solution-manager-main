package com.solace.sm.cp.agent.archfixture;

import com.solace.sm.cp.agent.AgentRegistry;
import java.util.List;
import java.util.UUID;

/** Deliberate violation: a second caller of the registry's one read that is not bound by organization. */
public final class BadFindAllCaller {

    public Object read(AgentRegistry registry, UUID agent) {
        return registry.findAll(List.of(agent));
    }
}

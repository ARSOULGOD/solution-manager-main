package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import java.util.List;
import java.util.Optional;

/**
 * The whole agent service contract, run through {@link RoutingLogService}: the wired bean is the router, so its answers
 * (an unknown placement included) must meet the same contract as the implementations behind it.
 */
class RoutingLogServiceContractTest extends AgentLogServiceTest {

    @Override
    LogService wrap(AgentLogService agent) {
        LogService ssh = (o, i, p, limit, a, b) ->
                new LogPageView(List.of(), Optional.of(AgentLogService.UNRESOLVABLE));
        return new RoutingLogService(KNOWN, ssh, agent);
    }
}

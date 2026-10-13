package com.solace.sm.dp.api.agw;

import java.util.List;

public interface ActualStatePort {
    List<ReportResult> report(AgentSessionRef session, List<ReportItem> items, AgentRequestOrigin origin);
}

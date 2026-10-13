package com.solace.sm.dp.api.agw;

public record ReportResult(String itemId, long sequence, String replicaId, String incarnationId,
        String trust, String disposition, GatewayCode refusedReason) { }

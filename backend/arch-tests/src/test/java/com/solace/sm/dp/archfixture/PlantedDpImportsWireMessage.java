package com.solace.sm.dp.archfixture;

import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

/**
 * Planted violation (T1.2 review finding 1): a data-plane class using the agent wire's generated MESSAGE classes. The proto has no
 * java_package, so they live in {@code solace.sm.agentgateway.v1draft}, outside {@code com.solace.sm.agw..}, and a message class
 * depends on {@code com.google.protobuf}, not {@code io.grpc}: only a rule naming that package can see this edge.
 */
public final class PlantedDpImportsWireMessage {
    public Class<?> forbidden() {
        return AgentGatewayOuterClass.Envelope.class;
    }
}

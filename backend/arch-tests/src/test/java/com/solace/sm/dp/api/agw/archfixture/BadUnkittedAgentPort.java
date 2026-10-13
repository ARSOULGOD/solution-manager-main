package com.solace.sm.dp.api.agw.archfixture;

/**
 * A planted {@code dp.api.agw} port interface that has no contract kit and no contracted stub.
 *
 * <p>It exists so {@code everyAgentGatewayPortInterfaceHasAKitOrIsListedAsUnkitted} can be shown to SEE a new port: without it that
 * check is a comparison of a hard-coded set against a scan nobody has proved is looking in the right place (plan 015 T2.1b, L19).
 */
public interface BadUnkittedAgentPort {

    void doSomethingNobodyProved();
}

package com.solace.sm.launcher;

import com.solace.sm.agw.AgentGatewayConfiguration;
import com.solace.sm.cp.config.ControlPlaneConfiguration;
import com.solace.sm.dp.DataPlaneConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Composes the two planes and nothing finer (plan 010 T2.6).
 *
 * <p>The launcher constructs NO repository, service, {@code DataSource}, {@code RuntimeAdapter}
 * or business bean of its own — every bean the console needs comes from importing the
 * aggregator that already owns it: {@link ControlPlaneConfiguration} supplies cp-core's
 * identity/session/authorization services (and, transitively via its own {@code @Import}, the
 * {@code sm_control} access graph), and {@link DataPlaneConfiguration} supplies dp-core's
 * {@code sm_data} access graph, apply engine, durable worker and startup recovery sweep (all
 * dp-core's own — {@code arch-tests} forbids the launcher from holding an {@code ApplyExecutor}
 * or a {@code DataSource} directly), the DP secret store and the inventory. A bean added to
 * either plane reaches production through its aggregator, never through a line here. The
 * {@code com.solace.sm.gateway} component scan below picks up the authenticated HTTP boundary,
 * which depends on cp-api/dp-api ports only and therefore needs nothing declared here beyond
 * those imports. ({@code AuthRateGate} — {@code OR-24} — runs on {@code System.nanoTime} here:
 * nothing in production declares the optional {@code authRateClock} test hook it accepts.)
 *
 * <p>The agent-facing gateway ({@link AgentGatewayConfiguration}, plan 015 T3.2) is composed here too, together with the three
 * control-plane adapters that feed its data-plane ports ({@link AgentGatewayControlPlaneAdapterConfiguration}); it is built only
 * when {@code sm.agent.gateway.enabled=true}, so a {@code dp-remote}-only installation opens no agent port.
 *
 * <p>Agent auto-registration ({@code OR-27}, plan 015 T4.1/T4.2) needs no line here: the data plane's discovery intake
 * ({@code AgentGatewayPortsConfiguration}, imported by {@code DataPlaneConfiguration}) takes its two console-gateway collaborators
 * as optional beans, and both are {@code @Component}s of the {@code com.solace.sm.gateway} scan above - the control plane's
 * {@code AgentRegistrationCompleter} ({@code AgentRegistrationCompletion}) and {@code AgentPackageDeclarations}
 * ({@code PackageDeclarationSource}). The composition is therefore the scan and the two imports, and nothing else.
 *
 * <p>Because the data plane aggregator imports the secret store, every boot of this application
 * needs the DP master key ({@code SM_DP_MASTER_KEY}, {@code SM_DP_MASTER_KEY_ID}) and refuses to
 * start naming the variable when it is missing or wrong.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "com.solace.sm.gateway")
@Import({ControlPlaneConfiguration.class, DataPlaneConfiguration.class, AgentGatewayConfiguration.class,
        AgentGatewayControlPlaneAdapterConfiguration.class, AgentGatewayBootGuard.class})
public class SolutionManagerConfiguration {
}

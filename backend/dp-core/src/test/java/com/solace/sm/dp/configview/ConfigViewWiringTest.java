package com.solace.sm.dp.configview;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.DataPlaneConfiguration;
import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.AgentConfigViewPort;
import com.solace.sm.dp.api.configview.ConfigViewResult;
import com.solace.sm.dp.api.configview.ConfigViewService;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import com.solace.sm.dp.inventory.PlacementRows;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.support.GenericApplicationContext;

/** Plan 017 task 5: the config view is wired exactly once through the aggregator, and its properties reach the port. */
class ConfigViewWiringTest {

    @Test
    void theAggregatorRegistersExactlyOneConfigViewServiceWithoutAnOverride() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setAllowBeanDefinitionOverriding(false);
        new AnnotatedBeanDefinitionReader(context).register(DataPlaneConfiguration.class);
        ConfigurationClassPostProcessor processor = new ConfigurationClassPostProcessor();
        processor.setEnvironment(context.getEnvironment());
        processor.setResourceLoader(context);
        assertDoesNotThrow(() -> processor.postProcessBeanDefinitionRegistry(context));

        var factory = context.getBeanFactory();
        assertEquals(1, factory.getBeanNamesForType(ConfigViewService.class, true, false).length);
        assertEquals(1, factory.getBeanNamesForType(AgentConfigViewService.class, true, false).length);
        assertEquals(1, factory.getBeanNamesForType(ConfigViewConfiguration.class, true, false).length);
    }

    /** Records what the gateway port was asked. */
    static final class RecordingPort implements AgentConfigViewPort {
        final List<List<String>> allows = new CopyOnWriteArrayList<>();
        final List<Integer> maxBytes = new CopyOnWriteArrayList<>();

        @Override
        public AgentConfigView read(String tenantId, String agentId, String placementId, List<String> allow, int max) {
            allows.add(List.copyOf(allow));
            maxBytes.add(max);
            return AgentConfigView.ok(List.of(), 0, false);
        }
    }

    private final AgentConfigViewServiceTest fixtures = new AgentConfigViewServiceTest();

    private ApplicationContextRunner runner(RecordingPort port) {
        return new ApplicationContextRunner()
                .withInitializer(c -> c.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(ConfigViewConfiguration.class)
                .withBean(PlacementRows.class, () -> fixtures.placements)
                .withBean(PlacementTransports.class, () -> fixtures.transports)
                .withBean(AgentPlacementCatalog.class, () -> fixtures.agents)
                .withBean(AgentConfigViewPort.class, () -> port);
    }

    @Test
    void theDefaultAllowListAndLimitAreSent() {
        RecordingPort port = new RecordingPort();
        runner(port).run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, () -> String.valueOf(ctx.getStartupFailure()));
            assertInstanceOf(ConfigViewResult.Ok.class, ctx.getBean(ConfigViewService.class)
                    .view(AgentConfigViewServiceTest.ORGANIZATION, AgentConfigViewServiceTest.INSTANCE, AgentConfigViewServiceTest.PLACEMENT));
        });
        assertEquals(List.of(List.of("server.port", "management.*", "logging.*", "info.*")), port.allows);
        assertEquals(List.of(262_144), port.maxBytes);
    }

    @Test
    void theAllowListPropertyIsWhatIsSent() {
        RecordingPort port = new RecordingPort();
        runner(port).withPropertyValues("sm.dp.config-view.allow=server.port, app.feature.*", "sm.dp.config-view.max-bytes=4096",
                "sm.dp.config-view.timeout=2s").run(ctx -> {
                    assertTrue(ctx.getStartupFailure() == null, () -> String.valueOf(ctx.getStartupFailure()));
                    ctx.getBean(ConfigViewService.class).view(AgentConfigViewServiceTest.ORGANIZATION,
                            AgentConfigViewServiceTest.INSTANCE, AgentConfigViewServiceTest.PLACEMENT);
                });
        assertEquals(List.of(List.of("server.port", "app.feature.*")), port.allows);
        assertEquals(List.of(4096), port.maxBytes);
    }

    @Test
    void anAmbiguousAllowListFailsTheContext() {
        runner(new RecordingPort()).withPropertyValues("sm.dp.config-view.allow=server.port,a.*.b")
                .run(ctx -> assertTrue(ctx.getStartupFailure() != null, "a bad allow list is refused at startup"));
    }

    @Test
    void withoutAGatewayPortAnAgentPlacementIsUnavailable() {
        new ApplicationContextRunner()
                .withInitializer(c -> c.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(ConfigViewConfiguration.class)
                .withBean(PlacementRows.class, () -> fixtures.placements)
                .withBean(PlacementTransports.class, () -> fixtures.transports)
                .withBean(AgentPlacementCatalog.class, () -> fixtures.agents)
                .run(ctx -> assertEquals(new ConfigViewResult.Refused(ConfigViewResult.Reason.AGENT_UNAVAILABLE),
                        ctx.getBean(ConfigViewService.class).view(AgentConfigViewServiceTest.ORGANIZATION,
                                AgentConfigViewServiceTest.INSTANCE, AgentConfigViewServiceTest.PLACEMENT)));
    }

    @Test
    void withoutTheAgentPathEveryPlacementIsNotAnAgentPlacement() {
        new ApplicationContextRunner()
                .withInitializer(c -> c.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(ConfigViewConfiguration.class)
                .withBean(PlacementRows.class, () -> fixtures.placements)
                .run(ctx -> assertEquals(new ConfigViewResult.Refused(ConfigViewResult.Reason.NOT_AN_AGENT_PLACEMENT),
                        ctx.getBean(ConfigViewService.class).view(AgentConfigViewServiceTest.ORGANIZATION,
                                AgentConfigViewServiceTest.INSTANCE, AgentConfigViewServiceTest.PLACEMENT)));
    }
}

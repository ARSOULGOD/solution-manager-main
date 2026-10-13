package com.solace.sm.cp.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput;
import com.solace.sm.cp.api.dashboard.DefaultLayerInput.DeclaredMetric;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultLayerDeriverTest {

    private final DefaultLayerDeriver deriver = new DefaultLayerDeriver();
    private final UUID instance = UUID.randomUUID();

    @Test
    void theDefaultLayerIsDerivedFromTheActiveDeclaredMetrics() {
        List<ObjectNode> widgets = deriver.derive(instance, DashboardFixtures.input());

        // m.requests (counter) and m.depth (gauge): declared, defaultChart, active. m.inactive is declared and wants
        // a chart but is not collected; m.latency is a histogram; m.quiet opted out.
        assertThat(widgets).extracting(w -> w.path("query").path("metric").asText())
                .containsExactly("m.requests", "m.depth");
        assertThat(widgets).allSatisfy(w -> {
            assertThat(w.path("layer").asText()).isEqualTo("product-default");
            assertThat(w.path("mark").asText()).isEqualTo("line");
            assertThat(w.path("query").path("selector").path("entityType").asText())
                    .isEqualTo(DashboardFixtures.ENTITY_TYPE);
            assertThat(w.path("query").path("selector").path("instanceIds")).hasSize(1);
            assertThat(w.path("query").path("selector").path("instanceIds").get(0).asText())
                    .isEqualTo(instance.toString());
        });
    }

    @Test
    void aMetricThatBecomesActiveGainsAWidgetAndOneThatStopsLosesIt() {
        DefaultLayerInput input = DashboardFixtures.input();
        DefaultLayerInput withInactiveNowActive = new DefaultLayerInput(input.entityType(), input.declared(),
                Set.of("m.inactive"));

        assertThat(deriver.derive(instance, withInactiveNowActive)).extracting(w -> w.path("query").path("metric")
                .asText()).containsExactly("m.inactive");
    }

    @Test
    void aDefaultChartFalseMetricHasNoDefaultWidget() {
        DefaultLayerInput onlyOptedOut = new DefaultLayerInput(DashboardFixtures.ENTITY_TYPE,
                List.of(new DeclaredMetric("m.quiet", "gauge", "items", false)), Set.of("m.quiet"));

        assertThat(deriver.derive(instance, onlyOptedOut)).isEmpty();
    }

    @Test
    void aWidgetIdIsAFunctionOfTheMetricNameAlone() {
        List<ObjectNode> first = deriver.derive(UUID.randomUUID(), DashboardFixtures.input());
        List<ObjectNode> second = deriver.derive(UUID.randomUUID(), DashboardFixtures.input());

        assertThat(first.get(0).path("id").asText()).isEqualTo(second.get(0).path("id").asText())
                .isEqualTo(DefaultLayerDeriver.widgetId("m.requests")).startsWith("d_");
        assertThat(first.get(0).path("id").asText()).isNotEqualTo(first.get(1).path("id").asText());
    }
}

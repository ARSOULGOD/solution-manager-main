package com.solace.sm.cp.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DashboardDocumentValidatorTest {

    private final DashboardDocumentValidator validator = new DashboardDocumentValidator();
    private final UUID instance = UUID.randomUUID();

    @Test
    void aValidWidgetIsAcceptedAndNormalisedForTheSharedLayer() {
        ObjectNode shared = validator.shared(DashboardFixtures.widget(instance, "m.depth"), instance,
                DashboardFixtures.input());

        assertThat(shared.path("title").asText()).as("trimmed").isEqualTo("m.depth over time");
        assertThat(shared.path("layer").asText()).isEqualTo("shared");
        assertThat(shared.has("hidden")).as("a client cannot submit a hidden flag").isFalse();
        assertThat(shared.has("id")).as("the server assigns the id, never the client").isFalse();
    }

    @Test
    void aWidgetForAMetricOutsideThePackageVersionIsRefused() {
        assertInvalid(DashboardFixtures.widget(instance, "m.from_another_version"));
        // A metric of the SAME package that is not declared for this component is outside the version too.
        assertInvalid(DashboardFixtures.widget(instance, ""));
    }

    @Test
    void aWidgetForADeclaredHistogramIsRefusedBecauseNoSeriesWillExist() {
        // OR-75 (M-Q6): a histogram is skipped by the collector and the catalog shows it unsupported
        assertThatThrownBy(() -> validator.shared(DashboardFixtures.widget(instance, "m.latency"), instance,
                DashboardFixtures.input()))
                .isInstanceOfSatisfying(InvalidDashboardException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.INVALID);
                    assertThat(e.getMessage()).isEqualTo(
                            "The widget names a histogram metric, which is not collected and cannot be charted.");
                });
    }

    @Test
    void aWidgetForAnotherInstanceIsRefused() {
        assertInvalid(DashboardFixtures.widget(DashboardFixtures.ENTITY_TYPE, "[\"" + UUID.randomUUID() + "\"]",
                "m.depth", "t", "{}"));
    }

    @Test
    void aWidgetNamingTwoInstancesIsRefused() {
        // M-Q10: one instance per saved widget. The first is ours; the second is what is refused.
        assertInvalid(DashboardFixtures.widget(DashboardFixtures.ENTITY_TYPE,
                "[\"" + instance + "\",\"" + UUID.randomUUID() + "\"]", "m.depth", "t", "{}"));
        assertInvalid(DashboardFixtures.widget(DashboardFixtures.ENTITY_TYPE, "[]", "m.depth", "t", "{}"));
        assertInvalid(DashboardFixtures.widget(DashboardFixtures.ENTITY_TYPE, "\"" + instance + "\"", "m.depth", "t",
                "{}"));
    }

    @Test
    void aWidgetForAnotherEntityTypeIsRefused() {
        assertInvalid(DashboardFixtures.widget("cache-engine", "[\"" + instance + "\"]", "m.depth", "t", "{}"));
    }

    @Test
    void aMalformedOrIncompleteWidgetIsRefusedWithoutEchoingIt() {
        String secret = "do-not-echo-" + UUID.randomUUID();
        for (String bad : List.of("", "not json", "[]", "{\"title\":\"" + secret + "\"",
                DashboardFixtures.widget(instance, "m.depth") + " trailing",
                DashboardFixtures.widget(instance, "m.depth").replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                DashboardFixtures.widget(instance, "m.depth").replace("\"mark\": \"line\"", "\"mark\": \"pie\""),
                DashboardFixtures.widget(instance, "m.depth").replace("\"layer\": \"shared\"",
                        "\"layer\": \"product-default\""),
                DashboardFixtures.widget(instance, "m.depth").replace("\"thresholds\": []", "\"thresholds\": {}"),
                DashboardFixtures.widget(DashboardFixtures.ENTITY_TYPE, "[\"" + instance + "\"]", "m.depth",
                        "x".repeat(121), "{}"),
                // a duplicate key is a refusal, never a silent last-wins
                DashboardFixtures.widget(instance, "m.depth").replace("\"mark\": \"line\"",
                        "\"mark\": \"line\", \"mark\": \"gauge\""),
                DashboardFixtures.widget(DashboardFixtures.ENTITY_TYPE, "[\"" + instance + "\"]", "m.depth", "t",
                        "{\"pad\":\"" + "x".repeat(70_000) + "\"}"))) {
            assertThatThrownBy(() -> validator.shared(bad, instance, DashboardFixtures.input()))
                    .isInstanceOfSatisfying(InvalidDashboardException.class, e -> {
                        assertThat(e.kind()).isEqualTo(Kind.INVALID);
                        assertThat(e.getMessage()).doesNotContain(secret).doesNotContain("m.depth");
                    });
        }
    }

    @Test
    void noCacheLiteralIsInTheValidator() throws IOException {
        // I1/SM-D16: no `if (solution == "cache")`, no Cache-only constant on a production path. The demo's
        // validator carried the Cache metric set and entity type; this package must carry neither.
        Path dashboardPackage = Path.of("src/main/java/com/solace/sm/cp/dashboard");
        assertThat(dashboardPackage).as("the test runs from the module directory").isDirectory();
        try (Stream<Path> sources = Files.list(dashboardPackage)) {
            List<Path> files = sources.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(files).extracting(path -> path.getFileName().toString())
                    .contains("DashboardDocumentValidator.java", "DefaultLayerDeriver.java", "DashboardService.java");
            for (Path file : files) {
                assertThat(Files.readString(file).toLowerCase(Locale.ROOT))
                        .as(file.getFileName() + " must not name a Cache").doesNotContain("cache");
            }
        }
    }

    // ---------------------------------------------------------------- layout

    @Test
    void aLayoutPlacesEveryWidgetExactlyOnceOnTheGrid() {
        ArrayNode normalized = validator.layout("""
                [{"widgetId":"a","x":0,"y":0,"w":6,"h":2,"order":0,"extra":"dropped"},
                 {"widgetId":"b","x":6,"y":0,"w":6,"h":2,"order":1}]""", Set.of("a", "b"));

        assertThat(normalized).hasSize(2);
        assertThat(normalized.get(0).has("extra")).isFalse();
        assertThat(normalized.get(0).fieldNames()).toIterable()
                .containsExactly("widgetId", "x", "y", "w", "h", "order");
    }

    @Test
    void aLayoutThatMissesDuplicatesOrOverflowsIsRefused() {
        Set<String> ids = Set.of("a", "b");
        for (String bad : List.of(
                "[{\"widgetId\":\"a\",\"x\":0,\"y\":0,\"w\":6,\"h\":2,\"order\":0}]",
                "[{\"widgetId\":\"a\",\"x\":0,\"y\":0,\"w\":6,\"h\":2,\"order\":0},"
                        + "{\"widgetId\":\"a\",\"x\":6,\"y\":0,\"w\":6,\"h\":2,\"order\":1}]",
                "[{\"widgetId\":\"a\",\"x\":0,\"y\":0,\"w\":6,\"h\":2,\"order\":0},"
                        + "{\"widgetId\":\"c\",\"x\":6,\"y\":0,\"w\":6,\"h\":2,\"order\":1}]",
                "[{\"widgetId\":\"a\",\"x\":0,\"y\":0,\"w\":6,\"h\":2,\"order\":0},"
                        + "{\"widgetId\":\"b\",\"x\":6,\"y\":0,\"w\":7,\"h\":2,\"order\":1}]",
                "[{\"widgetId\":\"a\",\"x\":0,\"y\":0,\"w\":6,\"h\":2,\"order\":0},"
                        + "{\"widgetId\":\"b\",\"x\":6,\"y\":0,\"w\":6,\"h\":2,\"order\":0}]",
                "[{\"widgetId\":\"a\",\"x\":0,\"y\":0,\"w\":6,\"h\":2,\"order\":0},"
                        + "{\"widgetId\":\"b\",\"x\":\"6\",\"y\":0,\"w\":6,\"h\":2,\"order\":1}]",
                "{}", "not json")) {
            assertThatThrownBy(() -> validator.layout(bad, ids))
                    .isInstanceOfSatisfying(InvalidDashboardException.class,
                            e -> assertThat(e.kind()).isEqualTo(Kind.INVALID));
        }
    }

    private void assertInvalid(String widgetJson) {
        assertThatThrownBy(() -> validator.shared(widgetJson, instance, DashboardFixtures.input()))
                .isInstanceOfSatisfying(InvalidDashboardException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.INVALID));
    }
}

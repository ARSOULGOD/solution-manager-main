package com.solace.sm.cp.designer;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.designer.PackageModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * P11-1.1: the two schema additions the metrics plan reads, {@code metrics[*].defaultChart} (OQ-7 (b)) and
 * {@code components[*].restartSignal} (OQ-1 (a)), must SURVIVE the read. The reader sets
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, so a record without a component for a key drops it silently, and
 * the symptom would be direct-mode restart detection quietly becoming {@code inferred} and a
 * {@code defaultChart: false} being ignored. A parity test cannot catch that; only a read of the fixture can.
 */
class PackageJsonReaderMetricsFieldsTest {

    private static Path cacheFixture() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("contracts/validation/fixtures/cache-2.4.package.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate the Cache fixture from " + Path.of("").toAbsolutePath());
    }

    @Test
    void aDefaultChartFalseAndADeclaredRestartSignalSurviveTheRead() throws IOException {
        PackageModel.SolutionPackage pkg = PackageJsonReader.readFile(cacheFixture());
        PackageModel.PackageComponent component = pkg.components().get(0);

        PackageModel.PackageMetric hitRate = component.metrics().stream()
                .filter(m -> m.name().equals("cache.hit_rate")).findFirst().orElseThrow();
        assertThat(hitRate.defaultChart()).isFalse();

        assertThat(component.restartSignal()).isNotNull();
        assertThat(component.restartSignal())
                .containsEntry("kind", "http_json")
                .containsEntry("path", "/actuator/metrics/process.start.time")
                .containsEntry("port", 8081)
                .containsEntry("pointer", "/measurements/0/value");
    }

    @Test
    void anAbsentDefaultChartReadsAsNull() throws IOException {
        PackageModel.SolutionPackage pkg = PackageJsonReader.readFile(cacheFixture());

        PackageModel.PackageMetric hitCount = pkg.components().get(0).metrics().stream()
                .filter(m -> m.name().equals("cache.hit_count")).findFirst().orElseThrow();
        // null means "true": the derived default layer treats an absent flag as a chart (OQ-7 (b)).
        assertThat(hitCount.defaultChart()).isNull();
    }

    @Test
    void anAbsentRestartSignalReadsAsNull() throws IOException {
        PackageModel.SolutionPackage pkg = PackageJsonReader.read("""
                {"solution":"reader-fixture","packageVersion":[1],"appliesTo":">=1.0",
                 "components":[{"type":"widget","cardinality":"exactly-one","configPath":"widget"}]}
                """);
        assertThat(pkg.components().get(0).restartSignal()).isNull();
    }
}

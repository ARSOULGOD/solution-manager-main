package com.solace.sm.cp.designer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * {@link DesignerCodec} (plan 010 T5.3): the port the gateway serves a view model through. The byte claim T1.2 proved
 * is about {@code ViewModelJson}; this test proves it about what a JSON WRITER emits from the port's tree, which is
 * what reaches a browser: Jackson's output (compact, the way Spring writes it), re-read in order and re-written with
 * the generator's own canonical writer, must equal the fixture's {@code expected} block byte for byte.
 */
class DesignerCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final DesignerCodec CODEC = new DesignerCodecImpl();
    private static final DesignerProjectorImpl PROJECTOR = new DesignerProjectorImpl();

    @Test
    void theContextHasExactlyOneCodecByItsApiType() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DesignerConfiguration.class)) {
            assertThat(ctx.getBeansOfType(DesignerCodec.class)).hasSize(1);
        }
    }

    @Test
    void readsAPackageDocumentExactlyAsThePackageReaderDoes() throws IOException {
        Path file = contractsRoot().resolve("validation/fixtures/cache-2.4.package.json");

        PackageModel.SolutionPackage viaCodec = CODEC.readPackage(Files.readString(file));

        assertThat(viaCodec).isEqualTo(PackageJsonReader.readFile(file));
        assertThat(viaCodec.components()).isNotEmpty();
    }

    @Test
    void aDocumentThatIsNotAPackageIsRefusedWithoutQuotingIt() {
        assertThatThrownBy(() -> CODEC.readPackage("{\"components\": \"hunter2\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("hunter2");
        assertThatThrownBy(() -> CODEC.readPackage("not json hunter2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("hunter2");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureNames")
    void whatAJsonWriterEmitsFromTheTreeIsTheFixtureBytes(String fixtureName) throws IOException {
        Path file = viewModelFixtureDir().resolve(fixtureName + ".json");
        String fileText = Files.readString(file);
        String key = "\n  \"expected\": ";
        int at = fileText.indexOf(key);
        String expectedBytes = fileText.substring(at + key.length(), fileText.length() - "\n}\n".length());

        Map<String, Object> tree = CODEC.viewModelTree(projectFixture(file));
        String wire = MAPPER.writeValueAsString(tree);
        Object reread = MAPPER.readValue(wire, new TypeReference<LinkedHashMap<String, Object>>() {});

        assertThat(CanonicalJson.value(reread, 1))
                .as("fixture %s: the wire form of the port's tree, key order included", fixtureName)
                .isEqualTo(expectedBytes);
    }

    private static ViewModel projectFixture(Path file) throws IOException {
        Map<String, Object> fixture = MAPPER.readValue(Files.readString(file),
                new TypeReference<Map<String, Object>>() {});
        PackageModel.SolutionPackage pkg = PackageJsonReader.readFile(
                contractsRoot().resolve("validation/fixtures").resolve(fixture.get("package") + ".package.json"));
        StateModel.ComponentSelection selection =
                MAPPER.convertValue(fixture.get("selection"), StateModel.ComponentSelection.class);
        Map<String, Map<String, Object>> draft = MAPPER.convertValue(fixture.get("draft"),
                new TypeReference<Map<String, Map<String, Object>>>() {});
        StateModel.ObservedState observed =
                MAPPER.convertValue(fixture.get("observed"), StateModel.ObservedState.class);
        Map<String, List<Object>> catalog = fixture.containsKey("catalog")
                ? MAPPER.convertValue(fixture.get("catalog"), new TypeReference<Map<String, List<Object>>>() {})
                : Map.of();
        return PROJECTOR.project(pkg, selection, draft, observed, catalog, null);
    }

    static Stream<String> fixtureNames() throws IOException {
        try (var stream = Files.list(viewModelFixtureDir())) {
            return stream.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.json$", "")).sorted().toList().stream();
        }
    }

    private static Path viewModelFixtureDir() {
        return contractsRoot().resolve("view-models/fixtures/viewmodel");
    }

    private static Path contractsRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("contracts/view-models/fixtures/viewmodel"))) {
                return dir.resolve("contracts");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("contracts/ not found above " + Path.of("").toAbsolutePath());
    }
}

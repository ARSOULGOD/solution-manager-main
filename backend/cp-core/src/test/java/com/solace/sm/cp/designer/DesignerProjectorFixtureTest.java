package com.solace.sm.cp.designer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@code OR-34} golden test: {@code DesignerProjectorImpl} must match {@code
 * contracts/view-models/src/project.ts} exactly on every one of the 33 shared fixtures under
 * {@code contracts/view-models/fixtures/viewmodel/} — the fixtures ARE the specification here,
 * never edited from this module (see the task's ownership boundary; {@code contracts/} is
 * read-only from {@code cp-core}).
 *
 * <p>{@link #thereAreExactlyThirtyThreeSharedFixtures()} is deliberately its own test, not a
 * side effect of the parameterized run below: a fixture silently deleted from the shared
 * directory would otherwise just shrink the parameterized test count and pass green, which is
 * exactly the failure mode {@code OR-34}'s "locked to the 33 shared fixtures" is there to catch.
 *
 * <p>{@code assertThat(actualTree).isEqualTo(expected)} compares two plain {@code Map}/{@code
 * List} trees with ordinary {@code Object.equals}, so every normalization the comparison relies
 * on to still call a byte-faithful port "equal" to its fixture is spelled out here rather than
 * left implicit:
 * <ul>
 *   <li><b>Map key order is insignificant.</b> {@code Map.equals} ignores insertion order by
 *       contract, so {@code actualTree}'s and {@code expected}'s object keys never need to
 *       appear in the same order — only the same key/value pairs.
 *   <li><b>List element order IS significant</b> — the opposite of the map case. {@code
 *       List.equals} compares positionally, so every engine-determined ordering ({@code
 *       rendered}/{@code excluded} sorted by instance key then path/reason, section order then
 *       title, field {@code ui.order} then declaration index) must land in exactly the fixture's
 *       array order, not merely contain the same elements.
 *   <li><b>An optional (TS {@code field?:}) property absent from the fixture must be OMITTED
 *       from {@code actualTree} entirely</b> — never emitted as an explicit {@code null} — which
 *       is what {@code ViewModelJson#putIfNotNull} exists to do; getting this backwards changes
 *       the object's key count and {@code Map.equals} catches it as a spurious extra/missing key.
 *   <li><b>A handful of fields are declared non-optional yet nullable</b> (for example {@code
 *       ViewModel.packageVersion} and {@code FieldView.renderedPath}) and DO serialize to an
 *       explicit {@code null} in both trees — {@code ViewModelJson} uses a plain {@code m.put}
 *       for exactly these, never {@code putIfNotNull}, so their presence-as-null still matches.
 *   <li><b>Boxed numeric type must match exactly, with no JS-style coercion.</b> {@code
 *       Integer(8080).equals(Long(8080L))} is {@code false}, so a value the {@code int}
 *       normalizer boxes as {@code Integer} must be compared against a fixture-parsed number
 *       Jackson's own untyped binding ALSO boxes as {@code Integer} (small enough to fit) —
 *       {@code DesignerProjectorImpl#normalize}'s {@code SAFE_MAX}/{@code SAFE_MIN} int-vs-long
 *       boxing rule exists to keep that alignment, and this is the one place a JS-number-aware
 *       {@code strictEquals} would be the WRONG tool: the fixture comparison must stay exact
 *       boxed equality precisely because both sides are meant to already agree on boxing.
 * </ul>
 */
class DesignerProjectorFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final DesignerProjectorImpl PROJECTOR = new DesignerProjectorImpl();

    @Test
    void thereAreExactlyThirtyThreeSharedFixtures() throws IOException {
        assertThat(listFixtureFiles())
                .as("contracts/view-models/fixtures/viewmodel/*.json — OR-34 locks the port to "
                        + "exactly these 33; a count drift means a fixture was added or lost "
                        + "without this port being updated to match")
                .hasSize(33);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureNames")
    void projectsExactlyLikeTheTypeScriptEngine(String fixtureName) throws IOException {
        Map<String, Object> fixture = readGenericObject(viewModelFixtureDir().resolve(fixtureName + ".json"));
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = (Map<String, Object>) fixture.get("expected");

        assertThat(projectFixture(fixture))
                .as("fixture %s (%s)", fixtureName, fixture.get("pins"))
                .isEqualTo(expected);
    }

    /**
     * The byte-level half of {@code OR-34}. {@link #projectsExactlyLikeTheTypeScriptEngine} is
     * structural — {@code Map.equals} ignores key order — so it cannot see an object whose keys
     * the port emits in a different order than the TS engine, yet key order IS part of the
     * contract ({@code fixtures.ts}: "Pinned so a byte compare is meaningful"). This writes the
     * port's tree with {@link CanonicalJson} (the twin of the generator's {@code canonical()}) and
     * compares it, byte for byte, with the {@code expected} block as it sits in the fixture file.
     * {@code expected} is the last key of every fixture, so its block runs from its key to the
     * file's closing brace.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureNames")
    void projectsTheFixtureBytesExactly(String fixtureName) throws IOException {
        Path file = viewModelFixtureDir().resolve(fixtureName + ".json");
        String fileText = Files.readString(file);

        String key = "\n  \"expected\": ";
        int at = fileText.indexOf(key);
        assertThat(at).as("fixture %s must carry an `expected` block", fixtureName).isGreaterThanOrEqualTo(0);
        assertThat(fileText.indexOf(key, at + 1))
                .as("fixture %s: the top-level `expected` key must appear exactly once", fixtureName)
                .isEqualTo(-1);
        assertThat(fileText).as("fixture %s: `expected` must be the last key", fixtureName).endsWith("\n}\n");
        String expectedBytes = fileText.substring(at + key.length(), fileText.length() - "\n}\n".length());

        String actualBytes = CanonicalJson.value(projectFixture(readGenericObject(file)), 1);

        assertThat(actualBytes)
                .as("fixture %s: the port's `expected` block, byte for byte (key order included)", fixtureName)
                .isEqualTo(expectedBytes);
    }

    /**
     * Calibrates {@link CanonicalJson} against the generator's own output, independently of the
     * projector: the parsed fixture, re-written, must reproduce the file exactly. Without this a
     * writer that drifted from {@code JSON.stringify} would make the byte test above fail (or,
     * worse, agree) for a reason that has nothing to do with the port.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureNames")
    void theCanonicalWriterReproducesTheFixtureFileExactly(String fixtureName) throws IOException {
        Path file = viewModelFixtureDir().resolve(fixtureName + ".json");

        assertThat(CanonicalJson.document(readGenericObject(file))).isEqualTo(Files.readString(file));
    }

    private static Map<String, Object> projectFixture(Map<String, Object> fixture) throws IOException {
        String packageName = (String) fixture.get("package");
        PackageModel.SolutionPackage pkg =
                PackageJsonReader.readFile(packageFixtureDir().resolve(packageName + ".package.json"));

        StateModel.ComponentSelection selection =
                MAPPER.convertValue(fixture.get("selection"), StateModel.ComponentSelection.class);
        Map<String, Map<String, Object>> draft =
                MAPPER.convertValue(fixture.get("draft"), new TypeReference<Map<String, Map<String, Object>>>() {});
        StateModel.ObservedState observed =
                MAPPER.convertValue(fixture.get("observed"), StateModel.ObservedState.class);
        Map<String, List<Object>> catalog = fixture.containsKey("catalog")
                ? MAPPER.convertValue(fixture.get("catalog"), new TypeReference<Map<String, List<Object>>>() {})
                : Map.of();

        ViewModel actual = PROJECTOR.project(pkg, selection, draft, observed, catalog, null);
        return ViewModelJson.of(actual);
    }

    static Stream<String> fixtureNames() throws IOException {
        return listFixtureFiles().stream()
                .map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
                .sorted();
    }

    private static List<Path> listFixtureFiles() throws IOException {
        try (var stream = Files.list(viewModelFixtureDir())) {
            return stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Map<String, Object> readGenericObject(Path file) throws IOException {
        return MAPPER.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() {});
    }

    private static Path viewModelFixtureDir() {
        return contractsRoot().resolve("view-models/fixtures/viewmodel");
    }

    private static Path packageFixtureDir() {
        return contractsRoot().resolve("validation/fixtures");
    }

    /**
     * Walks up from the working directory to find the repo's {@code contracts/} folder,
     * regardless of whether the test runner's cwd is the repo root or this module's own
     * directory (Surefire/Failsafe do not guarantee which).
     */
    private static Path contractsRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("contracts/view-models/fixtures/viewmodel");
            if (Files.isDirectory(candidate)) {
                return dir.resolve("contracts");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate contracts/view-models/fixtures/viewmodel by walking up from " + Path.of("").toAbsolutePath());
    }
}

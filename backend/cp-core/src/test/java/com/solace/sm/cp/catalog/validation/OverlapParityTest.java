package com.solace.sm.cp.catalog.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.catalog.validation.OverlapGate.NamedPackage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Gate 3: the two overlap fixtures serve one solution with overlapping {@code appliesTo} ranges. */
class OverlapParityTest {

    private static final PackageValidator VALIDATOR = ValidationFixtures.validator();

    private static List<NamedPackage> overlapPackages() {
        return ValidationFixtures.overlaps().stream()
                .map(p -> new NamedPackage(p.getFileName().toString(), ValidationFixtures.read(p))).toList();
    }

    @Test
    void overlappingAppliesToIsRejectedByGate3() {
        List<GateFailure> failures = OverlapGate.check(overlapPackages());
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).gate()).isEqualTo("3");
        assertThat(failures.get(0).message()).contains("cache-2.0.package.json").contains("cache-2.4.package.json");
    }

    @Test
    void theOverlapFixturesPassEveryPerFileGateSoOnlyGate3Fires() {
        // run-checks.sh step 3's B-1 point: a per-file violation would mask the overlap detector never firing.
        for (NamedPackage p : overlapPackages()) {
            assertThat(VALIDATOR.validate(p.pkg(), false).failures()).as(p.name()).isEmpty();
        }
    }

    @Test
    void disjointRangesAreAcceptedAndDifferentSolutionsNeverOverlap() {
        List<NamedPackage> two = overlapPackages();
        ObjectNode later = two.get(1).pkg().deepCopy();
        later.put("appliesTo", ">=2.4 <3.0");     // [2.0,2.4) and [2.4,3.0) touch but do not overlap
        assertThat(OverlapGate.check(List.of(two.get(0), new NamedPackage("later", later)))).isEmpty();

        ObjectNode other = two.get(1).pkg().deepCopy();
        other.put("solution", "someone-else");     // same range, different solution
        assertThat(OverlapGate.check(List.of(two.get(0), new NamedPackage("other", other)))).isEmpty();
    }

    @Test
    void theValidatorAndThePythonGateAgreeOnTheOverlapFixtures() {
        String reason = PythonGate.unavailableReason();
        assumeTrue(reason == null, "python parity oracle unavailable: " + reason);
        List<Path> files = ValidationFixtures.overlaps();
        assertThat((long) OverlapGate.check(overlapPackages()).size()).isEqualTo(PythonGate.overlapFailures(files));
        // and on the positives together: cache-2.4 alone for its solution, so no overlap on either side
        List<Path> positives = ValidationFixtures.positives();
        List<NamedPackage> named = positives.stream()
                .map(p -> new NamedPackage(p.getFileName().toString(), ValidationFixtures.read(p))).toList();
        assertThat((long) OverlapGate.check(named).size()).isEqualTo(PythonGate.overlapFailures(positives));
    }
}

package com.solace.sm.cp.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T2.3: the gateway answers {@code 400} for a solution name outside {@link
 * EntitlementAdministration#SOLUTION_NAME}, because {@link EntitlementAdministrationService#set} would otherwise throw
 * {@code IllegalArgumentException} (a {@code 500}). The two must be the same grammar, and both the operator command's
 * ({@link EntitlementService#isValidSolution}) and the table's {@code catalog_solution_name} CHECK.
 */
class EntitlementSolutionNameParityTest {

    @Test
    void theApisPatternAndTheOperatorCommandsPatternAcceptExactlyTheSameNames() {
        for (String name : List.of("cache", "file-mesh", "a", "a1", "a-b-c", "a".repeat(64), "a".repeat(65), "", "A",
                "Cache", "1a", "-a", "a_b", "a.b", "a b", "a\n", "é", "a--", "a-")) {
            assertThat(EntitlementAdministration.SOLUTION_NAME.matcher(name).matches())
                    .as("%s", name.length() > 20 ? name.length() + " chars" : name)
                    .isEqualTo(EntitlementService.isValidSolution(name));
        }
        assertThat(EntitlementService.isValidSolution(null)).isFalse();
    }
}

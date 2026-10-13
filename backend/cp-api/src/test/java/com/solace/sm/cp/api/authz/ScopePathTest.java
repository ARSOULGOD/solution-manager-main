package com.solace.sm.cp.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The five-level grant hierarchy and its prefix rule (ruling {@code OR-16}). */
class ScopePathTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();
    private static final UUID SOLUTION = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();

    @Test
    void theGrantHierarchyHasFiveLevelsAndEndsAtInstance() {
        ScopePath instance = ScopePath.instance(ORG, UNIT, ENV, SOLUTION, INSTANCE);
        assertThat(instance.depth())
                .as("Organization, Sub-organization, Environment, Solution, Instance — OR-16")
                .isEqualTo(5);
        assertThat(ScopePath.INSTALL.depth()).isZero();
    }

    @Test
    void solutionIsGrantable() {
        ScopePath solution = ScopePath.solution(ORG, UNIT, ENV, SOLUTION);
        ScopePath instance = ScopePath.instance(ORG, UNIT, ENV, SOLUTION, INSTANCE);

        assertThat(solution.authorizes(instance))
                .as("a solution-scoped binding reaches that solution's instances — this is the "
                        + "level OR-16 added, and before it the binding was not expressible")
                .isTrue();
        assertThat(solution.depth()).isEqualTo(4);
    }

    @Test
    void aSolutionScopedBindingDoesNotReachASiblingSolution() {
        ScopePath mine = ScopePath.solution(ORG, UNIT, ENV, SOLUTION);
        ScopePath sibling = ScopePath.instance(ORG, UNIT, ENV, UUID.randomUUID(), INSTANCE);
        assertThat(mine.authorizes(sibling)).isFalse();
    }

    @Test
    void anEnvironmentScopedBindingDoesNotReachASiblingEnvironment() {
        ScopePath mine = ScopePath.environment(ORG, UNIT, ENV);
        ScopePath sibling = ScopePath.environment(ORG, UNIT, UUID.randomUUID());
        assertThat(mine.authorizes(sibling)).isFalse();
    }

    @Test
    void inheritanceIsDownwardOnly() {
        ScopePath env = ScopePath.environment(ORG, UNIT, ENV);
        ScopePath instance = ScopePath.instance(ORG, UNIT, ENV, SOLUTION, INSTANCE);

        assertThat(env.authorizes(instance)).isTrue();
        assertThat(instance.authorizes(env))
                .as("an instance-scoped binding must not reach its own environment")
                .isFalse();
    }

    @Test
    void theInstallScopeReachesEverythingAndNothingElseReachesIt() {
        ScopePath instance = ScopePath.instance(ORG, UNIT, ENV, SOLUTION, INSTANCE);

        assertThat(ScopePath.INSTALL.authorizes(instance))
                .as("isPrefix([], p) holds for every p — this is what makes the seeded bootstrap "
                        + "admin work")
                .isTrue();
        assertThat(ScopePath.INSTALL.authorizes(ScopePath.INSTALL)).isTrue();
        assertThat(ScopePath.organization(ORG).authorizes(ScopePath.INSTALL))
                .as("an organization administrator must not be able to administer the install")
                .isFalse();
    }

    @Test
    void aPathWithAHoleIsRefused() {
        assertThatThrownBy(() -> new ScopePath(ORG, null, ENV, null, null))
                .as("a hole would let the path match further than it should")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    void aHoleAtEveryLevelIsRefused() {
        // Added after a planted-violation run: dropping `solution` from the contiguity check
        // passed every other test in this class, which meant a hole at the level OR-16 just
        // added would have shipped unnoticed. One case per level, so no level can be omitted
        // from the check without a test going red.
        assertThatThrownBy(() -> new ScopePath(null, UNIT, null, null, null))
                .as("sub-organization set, organization null")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopePath(ORG, null, ENV, null, null))
                .as("environment set, sub-organization null")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopePath(ORG, UNIT, null, SOLUTION, null))
                .as("solution set, environment null")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopePath(ORG, UNIT, ENV, null, INSTANCE))
                .as("instance set, SOLUTION null — the level OR-16 added, and the case a "
                        + "planted violation proved was unguarded")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thereIsNoPlacementComponent() {
        // OR-16: Placement is an operational child identity, never a grant level. The record
        // has five components and the compiler is the assertion — this test documents the
        // consequence a reader would otherwise have to infer.
        assertThat(ScopePath.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("organization", "subOrganization", "environment", "solution",
                        "instance")
                .doesNotContain("placement");
    }
}

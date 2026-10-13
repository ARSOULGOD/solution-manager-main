package com.solace.sm.cp.designer;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.designer.DesignerStageValidator.StageOutcome;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageRefusalReason;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageRequest;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code OR-34}'s stage-time re-projection check — no fixture backs this one
 * directly (the 33 shared fixtures pin {@code project()} itself, not the stage comparison built
 * on top of it), so this test builds its own minimal package rather than reading one from
 * {@code contracts/}.
 */
class DesignerStageValidatorTest {

    /** All optional {@code PackageField} components left {@code null} except key/type. */
    private static PackageModel.PackageField field(String key, String type) {
        // version, virtual, options, optionsSource, columns          (5)
        // defaultValue, required, secret, discriminator, when        (5)
        // min, max, pattern, yamlSubtree, ui                         (5)  -- 15 nulls total
        return new PackageModel.PackageField(
                key, type,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
    }

    private static final PackageModel.SolutionPackage PKG = new PackageModel.SolutionPackage(
            "stage-fixture",
            List.of(1),
            ">=1.0",
            null,
            List.of(new PackageModel.PackageComponent(
                    "widget",
                    "exactly-one",
                    null,
                    "none",
                    "widget",
                    null,
                    null,
                    List.of(field("name", "string"), field("port", "int")),
                    null, null, null, null, null)));

    private static final StateModel.ComponentSelection SELECTION =
            new StateModel.ComponentSelection(List.of(new StateModel.ComponentInstance("w1", "widget", null)));

    private static final StateModel.ObservedState OBSERVED = new StateModel.ObservedState(
            new StateModel.VersionObservation(null, null, "OK"), null, null, null);

    private final DesignerStageValidatorImpl validator =
            new DesignerStageValidatorImpl(new DesignerProjectorImpl());

    @Test
    void acceptsWhenTheClaimedRenderedSetMatchesTheServersOwnProjection() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of("w1", Map.of("name", "eu-west", "port", 8080)),
                OBSERVED, Map.of(), null,
                List.of(
                        new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"),
                        new ViewModel.RenderedEntry("w1", "widget.port", 8080))));

        assertThat(outcome).isInstanceOf(StageOutcome.Accepted.class);
    }

    @Test
    void refusesAnUndeclaredDraftKey() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of("w1", Map.of("name", "eu-west", "does-not-exist", "x")),
                OBSERVED, Map.of(), null,
                List.of(new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"))));

        assertThat(outcome).isInstanceOf(StageOutcome.Refused.class);
        assertThat(((StageOutcome.Refused) outcome).reasons())
                .contains(new StageRefusalReason.UndeclaredDraftKey("w1", "does-not-exist"));
    }

    /**
     * Escape route 1: a draft key namespaced under an instance key that {@code SELECTION} never
     * mentions at all. {@code project()}'s {@code ORPHAN_DRAFT_KEY} diagnostic is only ever
     * raised from inside its loop over {@code selection.instances()}, so a draft namespace with
     * no matching selected instance is never visited by {@code project()} at all — no diagnostic
     * is emitted, and (before the fix) nothing refused it either.
     */
    @Test
    void refusesADraftKeyNamespacedUnderAnInstanceThatWasNeverSelected() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of(
                        "w1", Map.of("name", "eu-west"),
                        "ghost-instance", Map.of("name", "should-not-be-accepted")),
                OBSERVED, Map.of(), null,
                List.of(new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"))));

        assertThat(outcome).isInstanceOf(StageOutcome.Refused.class);
        assertThat(((StageOutcome.Refused) outcome).reasons())
                .contains(new StageRefusalReason.UndeclaredDraftKey("ghost-instance", "name"));
    }

    /**
     * Escape route 2: a draft key namespaced under an instance that IS selected, but whose
     * declared {@code type} the package does not know about. {@code project()} diagnoses this
     * instance with {@code UNKNOWN_COMPONENT} and then {@code continue}s its loop BEFORE the
     * orphan-key check for that instance ever runs — so, again, no {@code ORPHAN_DRAFT_KEY}
     * diagnostic and (before the fix) no refusal.
     */
    @Test
    void refusesADraftKeyNamespacedUnderASelectedInstanceOfAnUnknownComponentType() {
        StateModel.ComponentSelection selectionWithUnknownType = new StateModel.ComponentSelection(List.of(
                new StateModel.ComponentInstance("w1", "widget", null),
                new StateModel.ComponentInstance("w2", "not-a-declared-type", null)));

        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, selectionWithUnknownType,
                Map.of(
                        "w1", Map.of("name", "eu-west"),
                        "w2", Map.of("anything", "at-all")),
                OBSERVED, Map.of(), null,
                List.of(new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"))));

        assertThat(outcome).isInstanceOf(StageOutcome.Refused.class);
        assertThat(((StageOutcome.Refused) outcome).reasons())
                .contains(new StageRefusalReason.UndeclaredDraftKey("w2", "anything"));
    }

    /**
     * The rendered-value comparison must use the engine's JS-number semantics, not Java's boxed
     * {@code equals}: a client that claims a rendered value as a {@code Long} must not be
     * refused against a server value the {@code int} normalizer produced as an {@code Integer}
     * — {@code Integer.valueOf(8080).equals(Long.valueOf(8080L))} is {@code false} in Java, but
     * the engine has exactly one numeric type and {@code 8080 === 8080} there is simply true.
     */
    @Test
    void acceptsWhenTheClaimedValueIsALongAndTheServersIsAnIntegerButNumericallyEqual() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of("w1", Map.of("name", "eu-west", "port", 8080)),
                OBSERVED, Map.of(), null,
                List.of(
                        new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"),
                        // The server's int normalizer produces a boxed Integer for 8080; this
                        // claims the numerically identical value as a boxed Long instead.
                        new ViewModel.RenderedEntry("w1", "widget.port", 8080L))));

        assertThat(outcome).isInstanceOf(StageOutcome.Accepted.class);
    }

    @Test
    void refusesAValueMismatchBetweenTheClaimAndTheServersReprojection() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of("w1", Map.of("name", "eu-west")),
                OBSERVED, Map.of(), null,
                // client claims a different value than the server would actually render
                List.of(new ViewModel.RenderedEntry("w1", "widget.name", "us-east"))));

        assertThat(outcome).isInstanceOf(StageOutcome.Refused.class);
        assertThat(((StageOutcome.Refused) outcome).reasons())
                .contains(new StageRefusalReason.ValueMismatch("w1", "widget.name", "eu-west", "us-east"));
    }

    /** Strict equality across types (S1 review MAJOR): a stringly claim never equals a rendered number. */
    @Test
    void refusesAClaimOfTheNumericStringWhereTheServerRendersTheNumber() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of("w1", Map.of("port", 8080)),
                OBSERVED, Map.of(), null,
                List.of(new ViewModel.RenderedEntry("w1", "widget.port", "8080"))));

        assertThat(outcome).isInstanceOf(StageOutcome.Refused.class);
        assertThat(((StageOutcome.Refused) outcome).reasons())
                .contains(new StageRefusalReason.ValueMismatch("w1", "widget.port", 8080, "8080"));
    }

    @Test
    void refusesWhenTheServerRendersAnEntryTheClaimOmitted() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of("w1", Map.of("name", "eu-west", "port", 8080)),
                OBSERVED, Map.of(), null,
                // omits widget.port, which the server does render
                List.of(new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"))));

        assertThat(outcome).isInstanceOf(StageOutcome.Refused.class);
        assertThat(((StageOutcome.Refused) outcome).reasons())
                .contains(new StageRefusalReason.MissingFromClaim("w1", "widget.port", 8080));
    }

    @Test
    void refusesWhenTheClaimInventsAnEntryTheServerDoesNotRender() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of("w1", Map.of("name", "eu-west")),
                OBSERVED, Map.of(), null,
                List.of(
                        new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"),
                        // port was never in the draft, so the server never renders it
                        new ViewModel.RenderedEntry("w1", "widget.port", 9999))));

        assertThat(outcome).isInstanceOf(StageOutcome.Refused.class);
        assertThat(((StageOutcome.Refused) outcome).reasons())
                .contains(new StageRefusalReason.UnexpectedInClaim("w1", "widget.port", 9999));
    }

    @Test
    void theAcceptedResultIsTheServersOwnProjectionNotTheClaim() {
        StageOutcome outcome = validator.validateStage(new StageRequest(
                PKG, SELECTION,
                Map.of("w1", Map.of("name", "eu-west")),
                OBSERVED, Map.of(), null,
                List.of(new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"))));

        ViewModel stored = ((StageOutcome.Accepted) outcome).serverResult();
        assertThat(stored.rendered()).containsExactly(new ViewModel.RenderedEntry("w1", "widget.name", "eu-west"));
        assertThat(stored.engineVersion()).isEqualTo(ViewModel.ENGINE_VERSION);
    }
}

package com.solace.sm.cp.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The catalog enum against the ratified amendment, read from the document at test time.
 *
 * <p><b>Why read the file rather than hard-code a list.</b> A hard-coded expectation is a
 * second copy of the catalog, and two copies drift — that is precisely how this tree ended up
 * with three different permission vocabularies. Reading
 * {@code docs/design/D10-G2-APPROVAL-2026-09-15.md} means the amendment is the single source
 * and this test is a mirror, so an edit to either side fails the build.
 */
class PermissionCatalogMirrorTest {

    /** §1.1 rows 1–35 and §1.7 rows 36–38 are the only numbered verb rows in the document. */
    private static final Pattern ROW =
            Pattern.compile("^\\|\\s*(\\d+)\\s*\\|\\s*`([a-zA-Z.]+)`\\s*\\|");

    private static Path approvalRecord() {
        // The test runs from backend/cp-api; the document is four levels up.
        Path fromModule = Path.of("../../docs/design/D10-G2-APPROVAL-2026-09-15.md");
        if (Files.exists(fromModule)) {
            return fromModule.normalize();
        }
        return Path.of("docs/design/D10-G2-APPROVAL-2026-09-15.md");
    }

    private static List<String> ratifiedVerbs() throws IOException {
        Path doc = approvalRecord();
        assertThat(doc)
                .as("the approval record must be readable — it is the authority this enum mirrors")
                .exists();
        List<String> verbs = new ArrayList<>();
        for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            Matcher m = ROW.matcher(line);
            if (m.find()) {
                verbs.add(m.group(2));
            }
        }
        return verbs;
    }

    @Test
    void theCatalogIsExactlyTheThirtyEightTheAmendmentEnumerates() throws IOException {
        List<String> ratified = ratifiedVerbs();

        assertThat(ratified)
                .as("§1.1's 35 plus §1.7's 3. If this is not 38, the document changed and the "
                        + "change needs an owner ruling, not a test edit.")
                .hasSize(38);

        assertThat(Permission.values())
                .extracting(Permission::id)
                .as("the enum and the ratified table must be the same set, in the same order")
                .containsExactlyElementsOf(ratified);
    }

    @Test
    void everyVerbHasARuledConsequenceClass() {
        assertThat(Permission.values())
                .as("ruling OR-13 settled the last UNDECIDED member (user.deactivate = HIGH), so "
                        + "the UNDECIDED set is empty today. The value stays in the enum so the "
                        + "NEXT unruled verb cannot arrive as STANDARD.")
                .noneMatch(p -> p.consequence() == ConsequenceClass.UNDECIDED);
    }

    @Test
    void permissionDoesNotCollapseTheThreeValuedConsequenceToBoolean() {
        assertThat(Permission.class.getDeclaredMethods())
                .noneMatch(method -> method.getReturnType() == boolean.class
                        && method.getName().toLowerCase().matches(".*(consequence|highrisk|ishigh).*"));
    }

    @Test
    void theThreeVerbsRulingOr9AddedArePresentAndHigh() {
        assertThat(Permission.INSTANCE_STOP.id()).isEqualTo("instance.stop");
        assertThat(Permission.USER_EDIT.id()).isEqualTo("user.edit");
        assertThat(Permission.AUDIT_VIEW.id()).isEqualTo("audit.view");

        assertThat(Permission.INSTANCE_STOP.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.USER_EDIT.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.AUDIT_VIEW.consequence())
                .as("audit.view is STANDARD — scope-filtered and server-redacted, not high-consequence")
                .isEqualTo(ConsequenceClass.STANDARD);
    }

    @Test
    void userDeactivateIsHighConsequencePerRulingOr13() {
        assertThat(Permission.USER_DEACTIVATE.consequence())
                .as("neither catalog.ts nor architecture.md §16 had marked it; OR-13 ruled it HIGH")
                .isEqualTo(ConsequenceClass.HIGH);
    }

    @Test
    void theVerbsTheApprovalWithheldAreAbsent() {
        List<String> withheld = List.of(
                "instance.undeclare", "instance.version.redeclare",
                "instance.restart.overrideSafety", "package.action.execute",
                "group.view", "group.create", "group.edit", "group.status.edit",
                "group.member.manage", "group.deactivate");

        for (String verb : withheld) {
            assertThatThrownBy(() -> Permission.byId(verb))
                    .as("'%s' is an open item 39 proposal, not an approval. An operation needing "
                            + "it answers 501.", verb)
                    .isInstanceOf(UnknownPermissionException.class);
        }
    }

    @Test
    void lookupByIdRefusesRatherThanReturningNull() {
        assertThat(Permission.byId("instance.stop")).isEqualTo(Permission.INSTANCE_STOP);
        assertThatThrownBy(() -> Permission.byId("instance.explode"))
                .isInstanceOf(UnknownPermissionException.class)
                .hasMessageContaining("501");
    }

    @Test
    void noTwoVerbsShareAnId() {
        assertThat(Permission.values()).extracting(Permission::id).doesNotHaveDuplicates();
    }
}

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

    /**
     * §1.1 rows 1–35, §1.7 rows 36–38, §1.9.1 rows 39–43 (ruling {@code OR-121}, confirmed by name in
     * {@code OR-122}), §1.10.1 rows 44–47 (ruling {@code OR-130}) and §1.11.1 rows 48–51 (ruling {@code OR-126} A8,
     * level and scope {@code OR-128} (4); written 44–47 by plan 015 and renumbered by the {@code OR-147} reconciliation)
     * are the only numbered verb rows in the document. §1.9's own table lists the verb first, so it is not counted:
     * §1.9.1, §1.10.1 and §1.11.1 are the same rows in the counted form, each appended in the commit that adds them here.
     */
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
    void theCatalogIsExactlyTheFiftyOneTheAmendmentEnumerates() throws IOException {
        List<String> ratified = ratifiedVerbs();

        assertThat(ratified)
                .as("§1.1's 35, §1.7's 3, §1.9.1's 5, §1.10.1's 4 and §1.11.1's 4. If this is not 51, the document "
                        + "changed and the change needs an owner ruling, not a test edit.")
                .hasSize(51);

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
    void theFiveVerbsRuling121AddedArePresentWithTheirRuledConsequenceClass() {
        // §1.9 rows 39-43, in that order, after row 38 and before §1.10.1's rows 44-47.
        assertThat(Permission.values()).extracting(Permission::id).containsSequence(
                "instance.undeclare", "instance.restart.overrideSafety", "target.edit", "hierarchy.edit",
                "entitlement.set");

        assertThat(Permission.INSTANCE_UNDECLARE.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.TARGET_EDIT.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.ENTITLEMENT_SET.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.HIERARCHY_EDIT.consequence())
                .as("hierarchy.edit is STANDARD in §1.9: creating and renaming a node is not an outage or a quota act")
                .isEqualTo(ConsequenceClass.STANDARD);
        assertThat(Permission.byId("instance.restart.overrideSafety"))
                .isEqualTo(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY);
    }

    @Test
    void theFourVerbsRuling130AddedArePresentWithTheirRuledConsequenceClass() {
        // §1.10.1 rows 44-47, in that order, after row 43 and before §1.11.1's rows 48-51.
        assertThat(Permission.values()).extracting(Permission::id).containsSequence(
                "entitlement.set", "connection.view", "connection.create", "connection.edit", "connection.delete",
                "agent.enrol");

        assertThat(Permission.CONNECTION_VIEW.consequence())
                .as("connection.view is STANDARD — shaped exactly like target.view")
                .isEqualTo(ConsequenceClass.STANDARD);
        assertThat(Permission.CONNECTION_CREATE.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.CONNECTION_EDIT.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.CONNECTION_DELETE.consequence()).isEqualTo(ConsequenceClass.HIGH);
    }

    @Test
    void theFourAgentVerbsRuling126AddedArePresentWithTheirRuledConsequenceClass() {
        // §1.11.1 rows 48-51 (44-47 before the OR-147 reconciliation), in that order, last.
        assertThat(Permission.values()).extracting(Permission::id).endsWith(
                "agent.enrol", "agent.revoke", "agent.drain", "agent.view");

        assertThat(Permission.AGENT_ENROL.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.AGENT_REVOKE.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(Permission.AGENT_DRAIN.consequence())
                .as("agent.drain is STANDARD (OR-128 (4)): a planned drain is recoverable")
                .isEqualTo(ConsequenceClass.STANDARD);
        assertThat(Permission.AGENT_VIEW.consequence()).isEqualTo(ConsequenceClass.STANDARD);
        assertThat(Permission.byId("agent.view")).isEqualTo(Permission.AGENT_VIEW);
    }

    @Test
    void userDeactivateIsHighConsequencePerRulingOr13() {
        assertThat(Permission.USER_DEACTIVATE.consequence())
                .as("neither catalog.ts nor architecture.md §16 had marked it; OR-13 ruled it HIGH")
                .isEqualTo(ConsequenceClass.HIGH);
    }

    @Test
    void theVerbsTheApprovalWithheldAreAbsent() {
        // OR-121 (confirmed by OR-122) moved instance.undeclare and instance.restart.overrideSafety out of this list
        // and into §1.9.1; the three open item 39 letters (c), (e), (i) that remain stay withheld (letter (h), the agent verbs, was answered by §1.11, written §1.10 before the OR-147 reconciliation).
        List<String> withheld = List.of(
                "instance.version.redeclare", "package.action.execute",
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

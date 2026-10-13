package com.solace.sm.cp.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.hierarchy.HierarchyAdministration;
import com.solace.sm.cp.api.hierarchy.HierarchyNameConflictException;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link HierarchyAdministrationService} with every collaborator a fake: the order of validate, authorize, write; the
 * name's shape; and the one rule that is easy to get wrong, which is that the service never compares two names itself
 * ({@code OR-123}): uniqueness is the database's, and a refused write is read as an identical replay or a conflict.
 * The database half is {@code HierarchyAdministrationServiceIT}.
 */
class HierarchyAdministrationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();
    private static final PrincipalRef ACTOR = new PrincipalRef(UUID.randomUUID());
    private static final RequestOrigin ORIGIN = new RequestOrigin("203.0.113.10", UUID.randomUUID());

    private final List<String> calls = new ArrayList<>();
    private final List<ResourceRef> required = new ArrayList<>();
    private final List<String> audits = new ArrayList<>();
    private RuntimeException refuse;
    /** When set, only a require on this kind of resource is refused; null refuses every require. */
    private ResourceKind refuseKind;
    private final FakeNodes nodes = new FakeNodes();
    private final UUID minted = UUID.randomUUID();

    private final AuthorizationService authorization = (principal, permission, resource) -> {
        calls.add("authorize " + permission.id() + " " + resource.kind());
        required.add(resource);
        if (refuse != null && (refuseKind == null || refuseKind == resource.kind())) {
            throw refuse;
        }
    };
    private final AuditContext auditContext =
            (actor, operation, correlation, client) -> audits.add(operation + "@" + client + "/" + actor);
    private final HierarchyAdministration service = new HierarchyAdministrationService(nodes, authorization,
            auditContext, TransactionOperations.withoutTransaction(), () -> minted);

    // ---------------------------------------------------------------- authorization comes first, at the right node

    @Test
    void aCreateIsAuthorizedAtTheParentBeforeAnyWriteOrRead() {
        service.createSubOrganization(ACTOR, ORIGIN, ORG, "Payments");
        service.createEnvironment(ACTOR, ORIGIN, ORG, UNIT, "Dev");

        assertThat(calls).startsWith("authorize hierarchy.edit ORGANIZATION");
        assertThat(required).extracting(ResourceRef::kind).containsExactly(ResourceKind.ORGANIZATION,
                ResourceKind.SUB_ORGANIZATION);
        assertThat(required).extracting(ResourceRef::id).containsExactly(ORG, UNIT);
        assertThat(calls.indexOf("authorize hierarchy.edit ORGANIZATION")).isZero();
    }

    @Test
    void aSubOrganizationAndAnEnvironmentRenameAreAuthorizedAtTheNodeItselfAndNowhereElse() {
        service.renameSubOrganization(ACTOR, ORIGIN, ORG, UNIT, "Payments");
        service.renameEnvironment(ACTOR, ORIGIN, ORG, UNIT, ENV, "Prod");

        assertThat(required).extracting(ResourceRef::kind).containsExactly(ResourceKind.SUB_ORGANIZATION,
                ResourceKind.ENVIRONMENT);
        assertThat(required).extracting(ResourceRef::id).containsExactly(UNIT, ENV);
    }

    @Test
    void anOrganizationRenameIsAuthorizedAtTheInstallAnchorFirstAndThenAtTheNamedOrganization() {
        // OR-125 (3): hierarchy.edit at INSTALL scope decides who may rename an organization at all; the second require
        // is the tenant check (a foreign or nonexistent organization is a 404, I5), the same two requires as entitlement.set
        service.renameOrganization(ACTOR, ORIGIN, ORG, "Acme");

        assertThat(calls).containsExactly("authorize hierarchy.edit ROLE_DEFINITION",
                "authorize hierarchy.edit ORGANIZATION");
        assertThat(required).extracting(ResourceRef::kind).containsExactly(ResourceKind.ROLE_DEFINITION,
                ResourceKind.ORGANIZATION);
        assertThat(required).extracting(ResourceRef::id).containsExactly(
                EntitlementAdministration.INSTALL_ANCHOR_ROLE, ORG);
        assertThat(required).extracting(r -> r.tenant().value()).containsOnly(ORG);
    }

    @Test
    void anOrganizationBoundCallerIsRefusedAtTheInstallAnchorBeforeAnythingIsReadWrittenOrAuditedWhateverOrganizationItNames() {
        // the refusal does not depend on the named organization existing: the same exception for the caller's own
        // organization, another existing one and a nonexistent one, so the refusal is no existence oracle (OR-125 (3))
        refuse = new AccessDeniedException(Permission.HIERARCHY_EDIT);
        for (UUID named : List.of(ORG, UUID.randomUUID(), UUID.randomUUID())) {
            assertThatThrownBy(() -> service.renameOrganization(ACTOR, ORIGIN, named, "Acme"))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThat(calls).as("only the install anchor was asked, once per call").containsExactly(
                "authorize hierarchy.edit ROLE_DEFINITION", "authorize hierarchy.edit ROLE_DEFINITION",
                "authorize hierarchy.edit ROLE_DEFINITION");
        assertThat(nodes.calls).isEmpty();
        assertThat(audits).isEmpty();
    }

    @Test
    void anInstallBoundCallerNamingAForeignOrganizationFindsItAbsentAtTheSecondRequireAndWritesNothing() {
        // the install anchor passes; the organization-scoped require (tenant) refuses as absent
        refuseKind = ResourceKind.ORGANIZATION;
        refuse = new ResourceNotFoundException(ResourceKind.ORGANIZATION);

        assertThatThrownBy(() -> service.renameOrganization(ACTOR, ORIGIN, ORG, "Acme"))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(calls).containsExactly("authorize hierarchy.edit ROLE_DEFINITION",
                "authorize hierarchy.edit ORGANIZATION");
        assertThat(nodes.calls).isEmpty();
        assertThat(audits).isEmpty();
    }

    @Test
    void aRefusedAuthorizationReadsAndWritesNothingAndWritesNoAuditContext() {
        refuse = new AccessDeniedException(Permission.HIERARCHY_EDIT);

        assertThatThrownBy(() -> service.createSubOrganization(ACTOR, ORIGIN, ORG, "Payments"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.renameEnvironment(ACTOR, ORIGIN, ORG, UNIT, ENV, "Prod"))
                .isInstanceOf(AccessDeniedException.class);
        refuse = new ResourceNotFoundException(ResourceKind.SUB_ORGANIZATION);
        assertThatThrownBy(() -> service.createEnvironment(ACTOR, ORIGIN, ORG, UNIT, "Dev"))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(nodes.calls).as("nothing reached the repository: a caller who may not edit learns nothing").isEmpty();
        assertThat(audits).isEmpty();
    }

    // ---------------------------------------------------------------- writes and audit

    @Test
    void aCreateWritesOnceUnderTheMintedIdAndAuditsHierarchyNodeCreatedWithTheActorAndClientAddress() {
        HierarchyAdministration.Created created = service.createSubOrganization(ACTOR, ORIGIN, ORG, "  Payments  ");

        assertThat(created.created()).isTrue();
        assertThat(created.node()).isEqualTo(new ScopeNode(minted, "Payments"));
        assertThat(nodes.calls).containsExactly("insertSubOrganization " + ORG + " " + minted + " Payments");
        assertThat(audits).containsExactly(AuditOperations.HIERARCHY_NODE_CREATED + "@203.0.113.10/" + ACTOR.id());
    }

    @Test
    void aRenameThatChangesTheNameAuditsHierarchyNodeRenamedAndOneThatChangesNothingStillReturnsTheNode() {
        nodes.renameChanges = true;
        ScopeNode renamed = service.renameOrganization(ACTOR, ORIGIN, ORG, "Acme");
        assertThat(renamed).isEqualTo(new ScopeNode(ORG, "Acme"));
        assertThat(audits).containsExactly(AuditOperations.HIERARCHY_NODE_RENAMED + "@203.0.113.10/" + ACTOR.id());

        nodes.renameChanges = false;
        nodes.organization = Optional.of(new ScopeNode(ORG, "Acme"));
        assertThat(service.renameOrganization(ACTOR, ORIGIN, ORG, "Acme")).isEqualTo(new ScopeNode(ORG, "Acme"));
    }

    @Test
    void aRenameOfANodeThatIsNotThereUnderTheBoundParentsIsNotFound() {
        nodes.renameChanges = false;
        nodes.environment = Optional.empty();

        assertThatThrownBy(() -> service.renameEnvironment(ACTOR, ORIGIN, ORG, UNIT, ENV, "Prod"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aCreateUnderAParentThatDoesNotResolveForThisOrganizationIsNotFoundAndWritesNothing() {
        nodes.subOrganization = Optional.empty();

        assertThatThrownBy(() -> service.createEnvironment(ACTOR, ORIGIN, ORG, UNIT, "Dev"))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(nodes.calls).containsExactly("findSubOrganization " + ORG + " " + UNIT);
        assertThat(audits).isEmpty();
    }

    // ---------------------------------------------------------------- OR-123: the database's rule, never Java's

    @Test
    void theServiceNeverLooksUpANameBeforeItWritesSoItCannotApplyADifferentCaseRule() {
        service.createSubOrganization(ACTOR, ORIGIN, ORG, "Payments");
        service.createEnvironment(ACTOR, ORIGIN, ORG, UNIT, "Dev");
        service.renameSubOrganization(ACTOR, ORIGIN, ORG, UNIT, "Billing");

        assertThat(nodes.calls).noneMatch(call -> call.contains("ByName"));
    }

    @Test
    void aRefusedCreateWhoseExactNameExistsIsAnIdenticalReplayAndNothingElseIsWritten() {
        nodes.takenIndex = "organization_unit_name_ci_uq";
        ScopeNode existing = new ScopeNode(UUID.randomUUID(), "Payments");
        nodes.byName = Optional.of(existing);

        HierarchyAdministration.Created replay = service.createSubOrganization(ACTOR, ORIGIN, ORG, "Payments");

        assertThat(replay.created()).isFalse();
        assertThat(replay.node()).isEqualTo(existing);
        assertThat(nodes.calls).containsExactly("insertSubOrganization " + ORG + " " + minted + " Payments",
                "findSubOrganizationByName " + ORG + " Payments");
    }

    @Test
    void aRefusedCreateWhoseExactNameDoesNotExistIsADifferentSpellingOfASiblingAndIsTheNamedConflict() {
        nodes.takenIndex = "environment_name_ci_uq";
        nodes.byName = Optional.empty();

        assertThatThrownBy(() -> service.createEnvironment(ACTOR, ORIGIN, ORG, UNIT, "DEV"))
                .isInstanceOfSatisfying(HierarchyNameConflictException.class,
                        e -> {
                            assertThat(e.kind()).isEqualTo(ResourceKind.ENVIRONMENT);
                            assertThat(e.getMessage()).as("the name sent is never echoed").doesNotContain("DEV");
                            assertThat(e.getCause()).as("no driver text is chained: it carries the key").isNull();
                        });
    }

    @Test
    void aRefusedRenameIsTheNamedConflictForItsKind() {
        nodes.takenIndex = "organization_name_ci_uq";
        assertThatThrownBy(() -> service.renameOrganization(ACTOR, ORIGIN, ORG, "Taken"))
                .isInstanceOfSatisfying(HierarchyNameConflictException.class,
                        e -> assertThat(e.kind()).isEqualTo(ResourceKind.ORGANIZATION));
        nodes.takenIndex = "organization_unit_name_ci_uq";
        assertThatThrownBy(() -> service.renameSubOrganization(ACTOR, ORIGIN, ORG, UNIT, "Taken"))
                .isInstanceOfSatisfying(HierarchyNameConflictException.class,
                        e -> assertThat(e.kind()).isEqualTo(ResourceKind.SUB_ORGANIZATION));
    }

    // ---------------------------------------------------------------- the shape of a name

    @Test
    void aNameOfTheWrongShapeIsRefusedBeforeAnyAuthorizationOrWrite() {
        List<String> bad = List.of("", "   ", "x".repeat(101), "tab\there", "nul\u0000x", "bell\u0007", "del\u007f",
                "c1\u0085x", "\n");
        for (String name : bad) {
            assertThatThrownBy(() -> service.createSubOrganization(ACTOR, ORIGIN, ORG, name))
                    .as("name %s", name.replace("\u0000", "\\0")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.renameOrganization(ACTOR, ORIGIN, ORG, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(calls).as("shape is judged before authorization, and reveals nothing").isEmpty();
        assertThat(nodes.calls).isEmpty();
    }

    @Test
    void aHundredCharactersAreAcceptedIncludingOnesOutsideTheBasicPlaneCountedAsCharacters() {
        service.createSubOrganization(ACTOR, ORIGIN, ORG, "x".repeat(100));
        service.createSubOrganization(ACTOR, ORIGIN, ORG, "😀".repeat(100));   // 100 code points, 200 chars

        assertThat(nodes.calls).hasSize(2);
        assertThatThrownBy(() -> service.createSubOrganization(ACTOR, ORIGIN, ORG, "😀".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationDoesNotEchoTheNameInItsMessage() {
        assertThatThrownBy(() -> service.createSubOrganization(ACTOR, ORIGIN, ORG, "secret\u0007name"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret"));
    }

    // ---------------------------------------------------------------- the fake repository

    /** A repository that records its calls and refuses a write with a configurable index. */
    private final class FakeNodes implements HierarchyWriteRepository {

        final List<String> calls = new ArrayList<>();
        String takenIndex;
        boolean renameChanges = true;
        Optional<ScopeNode> organization = Optional.empty();
        Optional<ScopeNode> subOrganization = Optional.of(new ScopeNode(UNIT, "unit"));
        Optional<ScopeNode> environment = Optional.of(new ScopeNode(ENV, "env"));
        Optional<ScopeNode> byName = Optional.empty();

        private void write(String call) {
            calls.add(call);
            if (takenIndex != null) {
                throw new NameTakenException(takenIndex);
            }
        }

        @Override
        public void insertSubOrganization(UUID organizationId, UUID subOrganizationId, String name) {
            write("insertSubOrganization " + organizationId + " " + subOrganizationId + " " + name);
        }

        @Override
        public void insertEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId, String name) {
            write("insertEnvironment " + organizationId + " " + subOrganizationId + " " + environmentId + " " + name);
        }

        @Override
        public boolean renameOrganization(UUID organizationId, String name) {
            write("renameOrganization " + organizationId + " " + name);
            return renameChanges;
        }

        @Override
        public boolean renameSubOrganization(UUID organizationId, UUID subOrganizationId, String name) {
            write("renameSubOrganization " + organizationId + " " + subOrganizationId + " " + name);
            return renameChanges;
        }

        @Override
        public boolean renameEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId,
                String name) {
            write("renameEnvironment " + organizationId + " " + subOrganizationId + " " + environmentId + " " + name);
            return renameChanges;
        }

        @Override
        public Optional<ScopeNode> findOrganization(UUID organizationId) {
            calls.add("findOrganization " + organizationId);
            return organization;
        }

        @Override
        public Optional<ScopeNode> findSubOrganization(UUID organizationId, UUID subOrganizationId) {
            calls.add("findSubOrganization " + organizationId + " " + subOrganizationId);
            return subOrganization;
        }

        @Override
        public Optional<ScopeNode> findEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId) {
            calls.add("findEnvironment " + organizationId + " " + subOrganizationId + " " + environmentId);
            return environment;
        }

        @Override
        public Optional<ScopeNode> findSubOrganizationByName(UUID organizationId, String name) {
            calls.add("findSubOrganizationByName " + organizationId + " " + name);
            return byName;
        }

        @Override
        public Optional<ScopeNode> findEnvironmentByName(UUID organizationId, UUID subOrganizationId, String name) {
            calls.add("findEnvironmentByName " + organizationId + " " + subOrganizationId + " " + name);
            return byName;
        }
    }
}

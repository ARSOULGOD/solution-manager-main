package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalLoginService#loginSubjectHash} and {@link CredentialRotationService#rotationSubjectHash}
 * key the SAME {@code sm_control.login_throttle} table, so they must never produce the same key for
 * two different subjects. Before the {@code "local-login:"} prefix existed, login hashed the bare
 * username — a local username literally equal to {@code "credential-rotation:<principal-id>"}
 * hashed to exactly the value {@link CredentialRotationService#rotationSubjectHash} computes for
 * that principal, so a brute-force lockout on one row silently locked out the other operation for
 * an unrelated subject.
 */
class ThrottleKeyDomainSeparationTest {

    @Test
    void loginAndRotationSubjectHashesAreDisjointForTheAdversarialUsername() {
        UUID principalId = UUID.randomUUID();
        String adversarialUsername = "credential-rotation:" + principalId;

        String loginHash = LocalLoginService.loginSubjectHash(UUID.randomUUID(), adversarialUsername);
        String rotationHash = CredentialRotationService.rotationSubjectHash(principalId);

        assertThat(loginHash).isNotEqualTo(rotationHash);
    }

    @Test
    void loginAndRotationSubjectHashesAreDisjointForOrdinaryInputsToo() {
        UUID principalId = UUID.randomUUID();
        String ordinaryUsername = "operator@example.test";

        String loginHash = LocalLoginService.loginSubjectHash(UUID.randomUUID(), ordinaryUsername);
        String rotationHash = CredentialRotationService.rotationSubjectHash(principalId);

        assertThat(loginHash).isNotEqualTo(rotationHash);
    }

    /** OR-125 (4): login matches a username without regard to case, so its counter must too, or case would be a bypass. */
    @Test
    void theSpellingOfTheUsernameInCaseIsOneCounter() {
        UUID organization = UUID.randomUUID();

        assertThat(LocalLoginService.loginSubjectHash(organization, "Operator"))
                .isEqualTo(LocalLoginService.loginSubjectHash(organization, "operator"))
                .isEqualTo(LocalLoginService.loginSubjectHash(organization, "OPERATOR"));
    }

    /** OR-125 (4): a username is per organization, so the same name of two organizations is two counters. */
    @Test
    void theSameUsernameOfTwoOrganizationsIsTwoCounters() {
        assertThat(LocalLoginService.loginSubjectHash(UUID.randomUUID(), "operator"))
                .isNotEqualTo(LocalLoginService.loginSubjectHash(UUID.randomUUID(), "operator"));
    }

    /** An organization that names nothing is counted too, in a space of its own that no resolved organization shares. */
    @Test
    void anUnresolvedOrganizationIsItsOwnCounterAndItsTextCannotBeReadAsAUsername() {
        UUID organization = UUID.randomUUID();

        assertThat(LocalLoginService.unresolvedLoginSubjectHash(organization.toString(), "operator"))
                .isNotEqualTo(LocalLoginService.loginSubjectHash(organization, "operator"));
        assertThat(LocalLoginService.unresolvedLoginSubjectHash("a:b", "c"))
                .isNotEqualTo(LocalLoginService.unresolvedLoginSubjectHash("a", "b:c"));
        assertThat(LocalLoginService.unresolvedLoginSubjectHash("Acme", "Operator"))
                .isEqualTo(LocalLoginService.unresolvedLoginSubjectHash("ACME", "operator"));
    }

    /** W5D-3: a principal that exists is counted by its id, so no spelling of its name can open a second counter. */
    @Test
    void anExistingPrincipalIsOneCounterByIdInASpaceOfItsOwn() {
        UUID principal = UUID.randomUUID();

        assertThat(LocalLoginService.principalSubjectHash(principal))
                .isEqualTo(LocalLoginService.principalSubjectHash(principal))
                .isNotEqualTo(LocalLoginService.principalSubjectHash(UUID.randomUUID()))
                .isNotEqualTo(LocalLoginService.loginSubjectHash(principal, "operator"))
                .isNotEqualTo(CredentialRotationService.rotationSubjectHash(principal));
    }
}

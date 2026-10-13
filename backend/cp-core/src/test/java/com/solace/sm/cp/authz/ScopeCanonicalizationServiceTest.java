package com.solace.sm.cp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.authz.ScopePath;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopeCanonicalizationServiceTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();

    @Test
    void installScopeCannotBeCanonicalizedFromAnOrganizationSession() {
        RecordingStore store = new RecordingStore();
        ScopeCanonicalizationService service = new ScopeCanonicalizationService(store);

        assertThatThrownBy(() -> service.canonicalize(new SessionContext(ORGANIZATION), ScopeRequest.install()))
                .isInstanceOf(ScopeNotCanonicalizableException.class)
                .hasMessageContaining("install");
        assertThat(store.called).isFalse();
    }

    @Test
    void aSessionLocalPathIsCanonicalizedByTheStore() {
        RecordingStore store = new RecordingStore();
        ScopeCanonicalizationService service = new ScopeCanonicalizationService(store);
        UUID unit = UUID.randomUUID();
        UUID environment = UUID.randomUUID();

        ScopeId scopeId = service.canonicalize(new SessionContext(ORGANIZATION),
                ScopeRequest.environment(unit, environment));

        assertThat(scopeId).isEqualTo(store.result);
        assertThat(store.requested.organization()).isEqualTo(ORGANIZATION);
        assertThat(store.requested.subOrganization()).isEqualTo(unit);
        assertThat(store.requested.environment()).isEqualTo(environment);
    }

    @Test
    void anOrganizationScopeUsesTheServerDerivedOrganizationWithoutTreatingItAsInstall() {
        RecordingStore store = new RecordingStore();
        ScopeCanonicalizationService service = new ScopeCanonicalizationService(store);

        service.canonicalize(new SessionContext(ORGANIZATION), ScopeRequest.organization());

        assertThat(store.requested.organization()).isEqualTo(ORGANIZATION);
        assertThat(store.requested.depth()).isEqualTo(1);
    }

    private static final class RecordingStore implements ScopeStore {
        private final ScopeId result = new ScopeId(UUID.randomUUID());
        private boolean called;
        private ScopePath requested;

        @Override
        public ScopeId canonicalize(ScopePath path) {
            called = true;
            requested = path;
            return result;
        }
    }
}

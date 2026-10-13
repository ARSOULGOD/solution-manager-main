package com.solace.sm.cp.config;

import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.identity.CredentialRotation;
import com.solace.sm.cp.api.identity.ForcedRotationGate;
import com.solace.sm.cp.api.identity.IdentityDirectory;
import com.solace.sm.cp.api.identity.LocalAuthenticationService;
import com.solace.sm.cp.api.session.SessionAuthentication;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import com.solace.sm.cp.authz.PrincipalOrganizationResolver;
import com.solace.sm.cp.authz.ReachAuthorizationConfiguration;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import com.solace.sm.cp.authz.ScopeEvaluatingAuthorizationService;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.agent.AgentConfiguration;
import com.solace.sm.cp.audit.ExportAuditConfiguration;
import com.solace.sm.cp.catalog.CatalogConfiguration;
import com.solace.sm.cp.connection.ConnectionConfiguration;
import com.solace.sm.cp.dashboard.DashboardConfiguration;
import com.solace.sm.cp.designer.DesignerConfiguration;
import com.solace.sm.cp.designer.MetricApplicabilityConfiguration;
import com.solace.sm.cp.events.CatalogEventsConfiguration;
import com.solace.sm.cp.governance.GovernanceConfiguration;
import com.solace.sm.cp.hierarchy.HierarchyConfiguration;
import com.solace.sm.cp.hierarchy.HierarchyTreeRepository;
import com.solace.sm.cp.hierarchy.ScopeVisibleHierarchyDirectory;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.identity.CredentialRotationService;
import com.solace.sm.cp.identity.IdentityAdministrationConfiguration;
import com.solace.sm.cp.identity.IdentityService;
import com.solace.sm.cp.identity.LocalLoginService;
import com.solace.sm.cp.identity.LoginThrottleRepository;
import com.solace.sm.cp.identity.MustRotateGate;
import com.solace.sm.cp.identity.PrincipalRepository;
import com.solace.sm.cp.identity.RoleBindingRepository;
import com.solace.sm.cp.identity.SessionRepository;
import com.solace.sm.cp.identity.SessionService;
import com.solace.sm.cp.persistence.ControlPlanePersistenceConfiguration;
import com.solace.sm.cp.persistence.DashboardPersistenceConfiguration;
import com.solace.sm.cp.persistence.SmControlProperties;
import com.solace.sm.cp.target.TargetConfiguration;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The control-plane composition root: wires every CP service on top of
 * {@link ControlPlanePersistenceConfiguration}'s repositories, and exposes each one BY ITS cp-api
 * INTERFACE TYPE so a caller — the future console-gateway included — depends on {@code cp-api} only
 * ({@code I7}).
 *
 * <p><b>Why no bean here references a JDBC/Hikari/Flyway type.</b> {@code arch-tests}'
 * {@code jdbcIsConfinedToPersistencePackages} confines those to
 * {@code com.solace.sm.cp.persistence..}. Every dependency below is either a cp-api type, a cp-core
 * repository INTERFACE, or {@link Clock}/{@link TransactionOperations} — none of which resides in a
 * confined package. The repositories and the low-level {@code DataSource}/
 * {@code NamedParameterJdbcTemplate}/transaction-manager plumbing are built in
 * {@link ControlPlanePersistenceConfiguration}, which this class only {@code @Import}s.
 *
 * <p><b>Why every dependency below carries an explicit {@code @Qualifier}.</b> This context will, in
 * the launcher, sit beside dp-core's {@code ApplyPersistenceConfiguration}/
 * {@code ApplyEngineConfiguration}, which expose their OWN {@code DataSource}
 * ({@code smDataDataSource}), {@code PlatformTransactionManager} and {@code Clock}
 * ({@code smDataClock}) beans. By-type autowiring of any of those shapes — including
 * {@code TransactionOperations}/{@code TransactionTemplate}, which dp-core does not currently expose
 * but could — would be ambiguous the moment a second candidate appears; naming every CP bean and
 * qualifying every CP-side injection point closes that off structurally rather than by the accident of
 * today's bean set.
 */
@Configuration(proxyBeanMethods = false)
@Import({ControlPlanePersistenceConfiguration.class, DesignerConfiguration.class,
        ReachAuthorizationConfiguration.class, TargetConfiguration.class, ConnectionConfiguration.class,
        CatalogConfiguration.class,
        CatalogEventsConfiguration.class, GovernanceConfiguration.class, DashboardPersistenceConfiguration.class,
        DashboardConfiguration.class, ExportAuditConfiguration.class,
        MetricApplicabilityConfiguration.class, HierarchyConfiguration.class,
        IdentityAdministrationConfiguration.class, AgentConfiguration.class})
@EnableConfigurationProperties(SmControlProperties.class)
public class ControlPlaneConfiguration {

    /** Named so every CP consumer can qualify it explicitly rather than autowire {@link Clock} by type. */
    @Bean(name = "smControlClock")
    Clock smControlClock() {
        return Clock.systemUTC();
    }

    @Bean
    CredentialHasher credentialHasher() {
        return new CredentialHasher();
    }

    @Bean
    ForcedRotationGate forcedRotationGate() {
        return new MustRotateGate();
    }

    /**
     * The one bean for this object, deliberately returned under its CONCRETE type rather than split
     * into a second pass-through {@code SessionAuthentication}-typed bean: {@link LocalLoginService}
     * needs {@link SessionService#open}, which is not on the {@code cp-api} port, so the concrete
     * type has to be resolvable somewhere; a second bean of the same instance under the interface
     * type would make {@code getBean(SessionAuthentication.class)} ambiguous (two assignable beans)
     * for no gain — one bean whose class already implements {@link SessionAuthentication} satisfies
     * an interface-typed lookup on its own.
     */
    @Bean
    SessionService sessionService(SessionRepository sessionRepository, PrincipalRepository principalRepository,
            AuditContext auditContext, @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new SessionService(sessionRepository, principalRepository, auditContext, smControlTransactions,
                smControlClock);
    }

    @Bean
    LocalAuthenticationService localAuthenticationService(PrincipalRepository principalRepository,
            SessionService sessionService, CredentialHasher credentialHasher,
            LoginThrottleRepository loginThrottleRepository, AuditContext auditContext,
            SmControlProperties smControlProperties,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new LocalLoginService(principalRepository, sessionService, credentialHasher, loginThrottleRepository,
                smControlProperties.login(), smControlProperties.loginAddress(), auditContext, smControlTransactions,
                smControlClock);
    }

    @Bean
    CredentialRotation credentialRotation(PrincipalRepository principalRepository, SessionRepository sessionRepository,
            CredentialHasher credentialHasher, LoginThrottleRepository loginThrottleRepository,
            AuditContext auditContext, SmControlProperties smControlProperties,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new CredentialRotationService(principalRepository, sessionRepository, credentialHasher,
                loginThrottleRepository, smControlProperties.login(), auditContext, smControlTransactions,
                smControlClock);
    }

    @Bean
    IdentityDirectory identityDirectory(PrincipalRepository principalRepository,
            AuthorizationGrantRepository authorizationGrantRepository, RoleBindingRepository roleBindingRepository) {
        return new IdentityService(principalRepository, authorizationGrantRepository, roleBindingRepository);
    }

    @Bean
    ScopePathResolver scopePathResolver(ResourceScopeDirectory resourceScopeDirectory) {
        return new ScopePathResolver(resourceScopeDirectory);
    }

    @Bean
    AuthorizationService authorizationService(PrincipalOrganizationResolver principalOrganizationResolver,
            AuthorizationGrantRepository authorizationGrantRepository, ScopePathResolver scopePathResolver) {
        return new ScopeEvaluatingAuthorizationService(principalOrganizationResolver, authorizationGrantRepository,
                scopePathResolver);
    }

    @Bean
    HierarchyDirectory hierarchyDirectory(AuthorizationGrantRepository authorizationGrantRepository,
            HierarchyTreeRepository hierarchyTreeRepository) {
        return new ScopeVisibleHierarchyDirectory(authorizationGrantRepository, hierarchyTreeRepository);
    }
}

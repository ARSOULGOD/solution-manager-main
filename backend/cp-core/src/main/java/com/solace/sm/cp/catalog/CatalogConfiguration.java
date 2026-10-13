package com.solace.sm.cp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.catalog.validation.PackageValidator;
import com.solace.sm.cp.config.FirstLoadEntitlementProperties;
import com.solace.sm.cp.events.CatalogEventEmitter;
import com.solace.sm.cp.governance.GovernanceRepository;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.PrincipalRepository;
import com.solace.sm.cp.persistence.CatalogPersistenceConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Wires the package catalog (plan 010 T3.2) and exposes it BY ITS {@code cp-api} TYPE, so the gateway depends on
 * {@code cp-api} only ({@code I7}). Imported by {@code ControlPlaneConfiguration}, the one aggregate the launcher
 * composes through.
 *
 * <p>The load gates are supplied the schema the production code mirrors: {@value #SCHEMA_RESOURCE}, a byte-identical
 * copy of {@code contracts/solution-package.schema.json} that {@code PackageSchemaMirrorLockTest} pins, so the
 * contract keeps one canonical home ({@code UI-D14}, {@code BE-D10}) and a drifted copy fails the build rather than
 * quietly validating against yesterday's vocabulary.
 */
@Configuration(proxyBeanMethods = false)
@Import(CatalogPersistenceConfiguration.class)
@EnableConfigurationProperties(FirstLoadEntitlementProperties.class)
public class CatalogConfiguration {

    static final String SCHEMA_RESOURCE = "/contracts/solution-package.schema.json";

    @Bean
    PackageValidator packageValidator() {
        try (InputStream schema = CatalogConfiguration.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schema == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " is not on the classpath: the package load gates "
                        + "have nothing to validate against");
            }
            JsonNode document = new ObjectMapper().readTree(schema);
            return new PackageValidator(document);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to read " + SCHEMA_RESOURCE, e);
        }
    }

    @Bean
    EntitlementService entitlementService(EntitlementRepository entitlementRepository,
            PrincipalRepository principalRepository, AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new EntitlementService(entitlementRepository, principalRepository, auditContext,
                smControlTransactions, smControlClock);
    }

    /** Plan 013 T2.2: entitlements over the API, by their cp-api type. The operator command keeps {@link EntitlementService}. */
    @Bean
    EntitlementAdministration entitlementAdministration(EntitlementRepository entitlementRepository,
            GovernanceRepository governanceRepository, AuthorizationService authorizationService,
            AuditContext auditContext, @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new EntitlementAdministrationService(entitlementRepository, governanceRepository,
                authorizationService, auditContext, smControlTransactions, smControlClock);
    }

    @Bean
    SolutionCatalog solutionCatalog(PackageValidator packageValidator, CatalogRepository catalogRepository,
            EntitlementService entitlementService, AuditContext auditContext,
            @Qualifier("smControlTransactions") TransactionOperations smControlTransactions,
            @Qualifier("smControlClock") Clock smControlClock, CatalogEventEmitter catalogEventEmitter,
            FirstLoadEntitlementProperties firstLoadProperties) {
        return new CatalogService(packageValidator, catalogRepository, entitlementService, auditContext,
                smControlTransactions, smControlClock, UUID::randomUUID, catalogEventEmitter,
                new FirstLoadEntitlement(firstLoadProperties.isEnabled(), firstLoadProperties.getMaxInstances()));
    }
}

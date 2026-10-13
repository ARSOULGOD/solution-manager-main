package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.ReachAuthorization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link GrantReachAuthorization} BY ITS cp-api INTERFACE TYPE ({@code I7}), beside the
 * {@code AuthorizationService} that {@code ControlPlaneConfiguration} builds from the same three
 * collaborators. Imported by that aggregator; no bean here references a JDBC type.
 */
@Configuration(proxyBeanMethods = false)
public class ReachAuthorizationConfiguration {

    @Bean
    ReachAuthorization reachAuthorization(PrincipalOrganizationResolver principalOrganizationResolver,
            AuthorizationGrantRepository authorizationGrantRepository, ScopePathResolver scopePathResolver) {
        return new GrantReachAuthorization(principalOrganizationResolver, authorizationGrantRepository,
                scopePathResolver);
    }
}

package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LoginOrganizationRequiredException;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcLoginThrottleRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Organization resolution at login, on a database of its own (it starts with the bootstrap organization ALONE, which
 * the omitted-organization rule depends on; the last test adds a second and so runs last).
 *
 * <ul>
 *   <li>W5D-1: every refusal path costs the SAME number of statements, whether or not the organization resolves, so the
 *       work a login does is not an oracle for which of the organization and the user was wrong;</li>
 *   <li>{@code OR-131} Q3: an omitted organization is the install's only organization; with several it is a 400-shaped
 *       {@link LoginOrganizationRequiredException}; an unknown one is still a wrong credential.</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoginOrganizationResolutionIT {

    private static final String CACHE_KEY = "login-organization-resolution-it";
    private static final UUID BOOTSTRAP_ORGANIZATION = UUID.fromString("00000000-0000-4000-8000-000000000011");

    private final NamedParameterJdbcTemplate owner =
            SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
    private final AtomicInteger statements = new AtomicInteger();
    private final NamedParameterJdbcTemplate counted = new NamedParameterJdbcTemplate(
            countingDataSource(owner.getJdbcTemplate().getDataSource(), statements));
    private final JdbcPrincipalRepository principals = new JdbcPrincipalRepository(counted);
    private final CredentialHasher hasher = new CredentialHasher();
    private final LocalLoginService login = loginService();
    private int addressSequence = 0;

    @Test
    @Order(1)
    void everyRefusalPathCostsTheSameStatementsWhetherOrNotTheOrganizationResolves() {
        String known = "equal-work-known-" + UUID.randomUUID();
        principals.createLocal(BOOTSTRAP_ORGANIZATION, known, hasher.hash("correct credential".toCharArray()), false);
        String deactivated = "equal-work-deactivated-" + UUID.randomUUID();
        principals.deactivate(principals.createLocal(BOOTSTRAP_ORGANIZATION, deactivated,
                hasher.hash("correct credential".toCharArray()), false));

        Map<String, Integer> cost = new LinkedHashMap<>();
        cost.put("wrong credential", refusedCost(BOOTSTRAP_ORGANIZATION.toString(), known));
        cost.put("unknown user", refusedCost(BOOTSTRAP_ORGANIZATION.toString(), "nobody-" + UUID.randomUUID()));
        cost.put("deactivated user", refusedCost(BOOTSTRAP_ORGANIZATION.toString(), deactivated));
        cost.put("unknown organization name", refusedCost("Nowhere-" + UUID.randomUUID(), known));
        cost.put("unknown organization id", refusedCost(UUID.randomUUID().toString(), known));
        cost.put("empty organization", refusedCost("", known));
        cost.put("over-long organization", refusedCost("x".repeat(300), known));

        assertThat(cost.values()).as("statements per refusal path: %s", cost).hasSize(7).containsOnly(cost.get("wrong credential"));
        assertThat(cost.get("wrong credential")).as("a login is not free").isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(2)
    void anOmittedOrganizationOnAnInstallWithExactlyOneIsThatOrganizationAndCostsTheSame() {
        String username = "omitted-single-" + UUID.randomUUID();
        principals.createLocal(BOOTSTRAP_ORGANIZATION, username, hasher.hash("correct credential".toCharArray()), false);

        int explicit = refusedCost(BOOTSTRAP_ORGANIZATION.toString(), username);
        int omitted = refusedCost(null, username);

        assertThat(omitted).isEqualTo(explicit);
        assertThat(login.authenticate(new LocalCredentials(null, username, "correct credential".toCharArray()),
                origin()).session().organizationId()).isEqualTo(BOOTSTRAP_ORGANIZATION);
    }

    @Test
    @Order(3)
    void aNamedOrganizationThatDoesNotExistIsStillAWrongCredentialOnAnInstallWithOne() {
        String username = "omitted-unknown-" + UUID.randomUUID();
        principals.createLocal(BOOTSTRAP_ORGANIZATION, username, hasher.hash("correct credential".toCharArray()), false);

        assertThatThrownBy(() -> login.authenticate(
                new LocalCredentials("Nowhere-" + UUID.randomUUID(), username, "correct credential".toCharArray()),
                origin())).isInstanceOf(LoginRefusedException.class);
    }

    @Test
    @Order(4)
    void anOmittedOrganizationOnAnInstallWithSeveralIsRefusedAsAShapeFaultBeforeAnyCredentialWork() {
        UUID second = LoginFixtures.organization(owner);
        String username = "omitted-several-" + UUID.randomUUID();
        principals.createLocal(BOOTSTRAP_ORGANIZATION, username, hasher.hash("correct credential".toCharArray()), false);

        assertThatThrownBy(() -> login.authenticate(
                new LocalCredentials(null, username, "correct credential".toCharArray()), origin()))
                .isInstanceOf(LoginOrganizationRequiredException.class);
        assertThatThrownBy(() -> login.authenticate(
                new LocalCredentials(second.toString(), username, "correct credential".toCharArray()), origin()))
                .as("naming the other organization is a wrong credential: the user is not there")
                .isInstanceOf(LoginRefusedException.class);
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** The statements one REFUSED login costs. Each attempt comes from its own address so no earlier lock changes the path. */
    private int refusedCost(String organization, String username) {
        statements.set(0);
        assertThatThrownBy(() -> login.authenticate(
                new LocalCredentials(organization, username, "wrong credential".toCharArray()), origin()))
                .isInstanceOf(LoginRefusedException.class);
        return statements.get();
    }

    private RequestOrigin origin() {
        addressSequence++;
        return new RequestOrigin("10.77." + (addressSequence >> 8 & 0xFF) + "." + (addressSequence & 0xFF), UUID.randomUUID());
    }

    private LocalLoginService loginService() {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(
                counted.getJdbcTemplate().getDataSource()));
        JdbcAuditContext auditContext = new JdbcAuditContext(counted);
        Clock clock = Clock.systemUTC();
        SessionService sessions = new SessionService(new JdbcSessionRepository(counted), principals, auditContext,
                transactions, clock);
        return new LocalLoginService(principals, sessions, hasher, new JdbcLoginThrottleRepository(counted),
                LoginRateLimitPolicy.LOCAL_DEFAULT, LoginRateLimitPolicy.ADDRESS_DEFAULT, auditContext, transactions, clock);
    }

    /** A DataSource whose connections count every statement they prepare or create. */
    private static DataSource countingDataSource(DataSource delegate, AtomicInteger counter) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    java.sql.Connection connection = (java.sql.Connection) result;
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                            new Class<?>[] {java.sql.Connection.class}, (p, m, a) -> {
                                switch (m.getName()) {
                                    case "prepareStatement", "prepareCall", "createStatement" -> counter.incrementAndGet();
                                    default -> { }
                                }
                                return invoke(connection, m, a);
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

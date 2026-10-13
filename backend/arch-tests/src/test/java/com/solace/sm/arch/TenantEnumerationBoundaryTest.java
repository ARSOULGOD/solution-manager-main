package com.solace.sm.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * OR-113 (7): application code never enumerates tenants from data rows.
 *
 * <p>Two rules, because there are two ways to do it. (1) <b>Through the repository</b>: the OR-44 list
 * ({@code tenantsWithNonTerminalWork}) has exactly the startup recovery and the worker poll as callers; the OR-49 list
 * ({@code tenantsWithActiveInstances}) is held to its four callers by {@link MonitoringBoundaryTest}. (2) <b>By SQL</b>:
 * a class that carries a {@code SELECT DISTINCT tenant_id} or {@code GROUP BY tenant_id} string constant is reported,
 * unless it is {@link TenantEnumerationRepository} (where the two ruled lists are written once), the scheduled-apply row
 * ({@code JdbcApplyScheduleRepository}, plan 012 T7, {@code OR-122} (2): the one more, held to one caller by
 * {@link ApplyScheduleBoundaryTest}) or an operator command
 * (a class named {@code *Main} in a {@code persistence} package, which runs with the owner credential and is never
 * reachable from the running application).
 *
 * <p><b>Plan 015 T4.6 ({@code OR-140}): two more, named.</b> The enrolment sweep job and the agent staleness job leave their request
 * paths ({@code listTargets}, {@code getHealth}) and run on a schedule, which needs a cross-tenant read for each: the control
 * plane's {@code JdbcAgentRegistry} (organizations with an abandoned enrolment; its caller chain is the job, the port
 * {@code AgentEnrolmentSweep#organizations} and the registry read, each held to one caller) and the data plane's
 * {@code JdbcAgentReportRepository} (tenants with a silent agent; held to {@code AgentStalenessJob}). The SQL rule's pattern now
 * also reads {@code organization_id}. A THIRD caller of any of these, or a route that runs a job's work from a request or read
 * path, is a red build here: each rule has a planted violation.
 *
 * <p>The SQL rule reads the constant pool of each class file: ArchUnit has no view of string constants, and a source scan
 * would need the module sources on this module's classpath. The pool holds every literal piece of a concatenation, so a
 * query split across {@code +} is caught if the two keywords sit in one literal; one split between "tenant_id" and its
 * keyword is not, which is why the repository-call rule exists beside it.
 *
 * <p>Proved by planted fixtures ({@code EnumeratesTenantsFromDataRows}, {@code BadWorkerListCaller}) and by the real
 * operator commands, which the rule must scan and not report.
 */
class TenantEnumerationBoundaryTest {

    private static final Set<String> NON_TERMINAL_CALLERS = Set.of(
            "com.solace.sm.dp.apply.worker.ApplyStartupRecovery",
            "com.solace.sm.dp.apply.worker.ApplyWorker");
    private static final String PLANTED_SQL = "com.solace.sm.dp.apply.persistence.archfixture.EnumeratesTenantsFromDataRows";
    private static final String PLANTED_CALL = "com.solace.sm.dp.apply.persistence.archfixture.BadWorkerListCaller";
    /**
     * Plan 012 T7 ({@code OR-122} (2)): the scheduled-apply feature's one cross-organization read,
     * {@code SELECT DISTINCT tenant_id FROM apply_schedule}, written once in this class and held to one route and one caller by
     * {@link ApplyScheduleBoundaryTest}.
     */
    private static final String SCHEDULE_ENUMERATION = "com.solace.sm.dp.apply.persistence.JdbcApplyScheduleRepository";
    /**
     * Plan 015 T2.1b ({@code I5}, P15-19): the agent gateway's ONE named unbound read. A certificate thumbprint is globally
     * UNIQUE ({@code agent_identity_thumbprint_global}), so {@code resolveByThumbprint} names exactly one organization's identity;
     * it is held to the repositories that open a session or record an enrolment. (Every later call carries an agent id, which is
     * also globally unique, and is derived from by {@code AgentAuthority}, the second and last such read.)
     */
    private static final String AGENT_IDENTITY_RESOLUTION = "com.solace.sm.dp.apply.persistence.agent.JdbcAgentIdentityRepository";
    private static final Set<String> RESOLVE_BY_THUMBPRINT_CALLERS = Set.of(
            "com.solace.sm.dp.apply.persistence.agent.JdbcAgentSessionRepository",
            "com.solace.sm.dp.apply.persistence.agent.JdbcAgentEnrollmentRepository");
    private static final String PLANTED_THUMBPRINT_CALLER =
            "com.solace.sm.dp.apply.persistence.agent.archfixture.BadThumbprintResolver";
    /**
     * Plan 015 T4.6 ({@code OR-140}, which amends {@code OR-44}/{@code OR-49} to this extent and NO further): the TWO scheduled
     * agent jobs' cross-tenant reads, each named here, each held to one caller below, each proved by a planted third caller.
     * The enrolment sweep job lists organizations from the control plane's {@code agent_registration} rows; the staleness job
     * lists tenants from the data plane's agent-report watermark rows.
     */
    private static final String SWEEP_ENUMERATION = "com.solace.sm.cp.persistence.AgentSweepOrganizations";
    private static final String STALENESS_ENUMERATION = "com.solace.sm.dp.apply.persistence.agent.AgentStalenessTenants";
    private static final String STALENESS_REPOSITORY = "com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository";
    private static final String SWEEP_JOB = "com.solace.sm.gateway.http.AgentEnrolmentSweepJob";
    private static final String SWEEP_SERVICE = "com.solace.sm.cp.agent.AgentEnrolmentSweepService";
    private static final String SWEEP = "com.solace.sm.gateway.http.AgentTargetSweep";
    private static final String STALENESS_JOB = "com.solace.sm.dp.agent.AgentStalenessJob";
    private static final String SWEEP_PORT = "com.solace.sm.cp.api.agent.AgentEnrolmentSweep";
    private static final String REGISTRY = "com.solace.sm.cp.agent.AgentRegistry";
    private static final String STALENESS_PORT = "com.solace.sm.dp.health.AgentReportStaleness";
    private static final String PLANTED_SWEEP_ENUMERATOR = "com.solace.sm.gateway.archfixture.sweep.SecondSweepEnumerator";
    private static final String PLANTED_SWEEP_ON_A_GET = "com.solace.sm.gateway.archfixture.sweep.SweepsOnAGet";
    private static final String PLANTED_REGISTRY_ENUMERATOR = "com.solace.sm.cp.archfixture.ThirdRegistryEnumerator";
    private static final String PLANTED_ORGANIZATION_SQL = "com.solace.sm.cp.archfixture.EnumeratesOrganizationsFromDataRows";
    private static final String PLANTED_STALENESS_ENUMERATOR = "com.solace.sm.dp.agent.archfixture.ThirdStalenessEnumerator";
    private static final String PLANTED_REVOKER = "com.solace.sm.gateway.archfixture.sweep.RevokesAsTheSystemActor";
    private static final String PLANTED_MARK_ON_A_READ = "com.solace.sm.dp.agent.archfixture.MarksStaleOnARead";
    private static final Set<String> OPERATOR_COMMANDS = Set.of(
            "com.solace.sm.dp.apply.persistence.MetricsPartitionMain",
            "com.solace.sm.dp.apply.persistence.KeyRotationMain");

    /**
     * {@code DISTINCT tenant_id} or {@code GROUP BY tenant_id}, any case and any whitespace (also {@code t.tenant_id}), and the
     * control plane's spelling {@code organization_id} (added by {@code OR-140}), with the tenant as the only key: {@code GROUP BY t.tenant_id, t.series_id} inside a tenant-scoped rollup is not an
     * enumeration.
     */
    private static final Pattern ENUMERATING_SQL = Pattern.compile(
            "(DISTINCT|GROUP\\s+BY)\\s+([a-z_]+\\.)?(tenant_id|organization_id)(?![a-z0-9_])(?!\\s*,)",
            Pattern.CASE_INSENSITIVE);

    private static JavaClasses production() {
        return new ClassFileImporter().withImportOption(new PlaneBoundaryTest.ExcludeArchFixtures())
                .importPackages("com.solace.sm");
    }

    private static JavaClasses withPlanted() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    private static String outermost(JavaClass c) {
        JavaClass outer = c;
        while (outer.getEnclosingClass().isPresent()) {
            outer = outer.getEnclosingClass().get();
        }
        return outer.getName();
    }

    private static boolean isOperatorCommand(String outermostName) {
        String simple = outermostName.substring(outermostName.lastIndexOf('.') + 1);
        String pkg = outermostName.substring(0, outermostName.length() - simple.length());
        return simple.endsWith("Main") && pkg.contains(".persistence.");
    }

    private static final String REPOSITORY = TenantEnumerationRepository.class.getName();

    private static boolean mayCarryEnumeratingSql(String outermostName) {
        return outermostName.equals(REPOSITORY) || outermostName.equals(SCHEDULE_ENUMERATION)
                || outermostName.equals(SWEEP_ENUMERATION) || outermostName.equals(STALENESS_ENUMERATION)
                || isOperatorCommand(outermostName);
    }

    private static Set<String> sqlOffenders(JavaClasses classes, boolean onlyDisallowed) {
        Set<String> found = new TreeSet<>();
        classes.stream().forEach(c -> {
            if (c.getSource().isEmpty() || c.getPackageName().equals("com.solace.sm.arch")) {
                return;
            }
            String text;
            try (java.io.InputStream in = c.getSource().get().getUri().toURL().openStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (ENUMERATING_SQL.matcher(text).find()) {
                String outer = outermost(c);
                if (!onlyDisallowed || !mayCarryEnumeratingSql(outer)) {
                    found.add(outer);
                }
            }
        });
        return found;
    }

    private static Set<String> nonTerminalCallers(JavaClasses classes) {
        Set<String> callers = new TreeSet<>();
        classes.stream().forEach(c -> c.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().getName().equals(REPOSITORY))
                .filter(call -> call.getTarget().getName().equals("tenantsWithNonTerminalWork"))
                .forEach(call -> callers.add(outermost(c))));
        return callers;
    }

    @Test
    void noApplicationClassCarriesSqlThatEnumeratesTenantsFromDataRows() {
        assertEquals(Set.of(), sqlOffenders(production(), true),
                "only TenantEnumerationRepository (OR-44, OR-49) and the operator *Main commands (OR-113 (7)) may do this");
    }

    @Test
    void aPlantedSqlEnumerationIsReportedAndTheOperatorCommandsAreNot() {
        Set<String> reported = sqlOffenders(withPlanted(), true);
        assertTrue(reported.contains(PLANTED_SQL), "the planted class must be reported. Was: " + reported);
        for (String allowed : OPERATOR_COMMANDS) {
            assertFalse(reported.contains(allowed), allowed + " is an operator command and must not be reported");
        }
        assertFalse(reported.contains(REPOSITORY), "the repository writes the two ruled lists and must not be reported");
        assertFalse(reported.contains(SCHEDULE_ENUMERATION), "the schedule row writes the OR-122 (2) read and must not be reported");
    }

    @Test
    void theOperatorCommandsAndTheRepositoryReallyCarryTheSqlSoTheRuleScansSomething() {
        Set<String> carriers = sqlOffenders(production(), false);
        assertTrue(carriers.contains("com.solace.sm.dp.apply.persistence.MetricsPartitionMain"),
                "MetricsPartitionMain archive/drop enumerate from rows; found " + carriers);
        assertTrue(carriers.contains(REPOSITORY), "found " + carriers);
        assertTrue(carriers.contains(SCHEDULE_ENUMERATION), "plan 012 T7's read is on the allow-list because it carries the SQL; found " + carriers);
    }

    @Test
    void theTwoOr140JobReadsCarryTheSqlSoTheRuleScansSomethingAndAPlantedOrganizationEnumerationIsReported() {
        Set<String> carriers = sqlOffenders(production(), false);
        assertTrue(carriers.contains(SWEEP_ENUMERATION), "the sweep job's read is on the allow-list because it carries the SQL; found " + carriers);
        assertTrue(carriers.contains(STALENESS_ENUMERATION), "the staleness job's read likewise; found " + carriers);
        Set<String> reported = sqlOffenders(withPlanted(), true);
        assertTrue(reported.contains(PLANTED_ORGANIZATION_SQL),
                "the control plane's organization_id spelling must be caught too. Was: " + reported);
        assertFalse(reported.contains(SWEEP_ENUMERATION) || reported.contains(STALENESS_ENUMERATION),
                "the two named job reads are allowed by name and must not be reported");
    }

    /**
     * Callers (outermost class) of {@code method} on any class ASSIGNABLE to one of {@code owners}, through a call OR a method
     * reference ({@code getAccessesFromSelf}; {@code getMethodCallsFromSelf} does not see {@code port::method}, which a second
     * caller could use to walk around the rule - review MINOR 3). A concrete implementation is caught as well as its interface.
     */
    private static Set<String> callersByOwnerName(JavaClasses classes, Set<String> owners, String method) {
        Set<String> callers = new TreeSet<>();
        classes.stream().forEach(c -> c.getAccessesFromSelf().stream()
                .filter(a -> a.getTarget() instanceof com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget)
                .filter(a -> owners.stream().anyMatch(o -> a.getTargetOwner().isAssignableTo(o)))
                .filter(a -> a.getTarget().getName().equals(method))
                .forEach(a -> callers.add(outermost(c))));
        return callers;
    }

    /**
     * One of the two OR-140 jobs' reads: the call sites of {@code method} on {@code owners} in production are exactly
     * {@code expected} (and not vacuously: the real caller is seen), and the planted fixture is seen when fixtures are imported.
     */
    private static void assertHeldTo(Set<String> owners, String method, Set<String> expected, String planted, String why,
            String... alsoPlanted) {
        Set<String> real = callersByOwnerName(production(), owners, method);
        assertEquals(expected, real, why + " (" + method + ")");
        Set<String> withPlanted = callersByOwnerName(withPlanted(), owners, method);
        assertTrue(withPlanted.contains(planted), "the planted caller must be seen. Was: " + withPlanted);
        assertFalse(expected.contains(planted));
        for (String other : alsoPlanted) {
            assertTrue(withPlanted.contains(other), "the planted caller must be seen. Was: " + withPlanted);
        }
    }

    @Test
    void onlyTheSweepJobEnumeratesOrganizationsForTheEnrolmentSweep() {
        assertHeldTo(Set.of(SWEEP_PORT), "organizations", Set.of(SWEEP_JOB), PLANTED_SWEEP_ENUMERATOR,
                "OR-140: the control plane's organization list for the sweep has exactly one caller; a second needs an owner ruling",
                "com.solace.sm.gateway.archfixture.sweep.SweepsOrganizationsThroughAMethodReference");
        assertHeldTo(Set.of(REGISTRY), "organizationsWithAbandonable",
                Set.of(SWEEP_SERVICE), PLANTED_REGISTRY_ENUMERATOR,
                "OR-140: the registry's cross-organization read has exactly one caller, the service that answers the port");
    }

    @Test
    void onlyTheStalenessJobEnumeratesTenantsForTheAgentStalenessVerdict() {
        assertHeldTo(Set.of(STALENESS_REPOSITORY), "tenantsWithStaleProbedPlacements", Set.of(STALENESS_JOB),
                PLANTED_STALENESS_ENUMERATOR,
                "OR-140: the data plane's tenant list for the staleness verdict has exactly one caller; a second needs an owner ruling");
        assertEquals(Set.of(STALENESS_JOB), callersByOwnerName(production(), Set.of(STALENESS_REPOSITORY), "staleProbedInstances"),
                "the per-tenant walk is the job's too");
    }

    @Test
    void neitherJobsWorkIsReachableFromARequestOrReadPath() {
        // The sweep leaves listTargets (and any other request path): only the job calls AgentTargetSweep#sweep.
        assertHeldTo(Set.of(SWEEP), "sweep", Set.of(SWEEP_JOB), PLANTED_SWEEP_ON_A_GET,
                "OR-140 (Q-C2): a reach-level GET never triggers a control-plane revocation");
        // The staleness verdict leaves getHealth: only the job calls AgentReportStaleness#markStale (the per-instance step).
        assertHeldTo(Set.of(STALENESS_PORT, "com.solace.sm.dp.agent.AgentHealthStaleness"), "markStale", Set.of(STALENESS_JOB),
                PLANTED_MARK_ON_A_READ, "OR-140 (Q-C3): a health read never writes the staleness verdict");
        // and the two control-plane writes are made only by the per-organization sweep
        assertHeldTo(Set.of(SWEEP_PORT), "revoke", Set.of(SWEEP), PLANTED_REVOKER,
                "OR-140: the system actor's two writes are made only by the sweep");
        assertEquals(Set.of(SWEEP), callersByOwnerName(production(), Set.of(SWEEP_PORT), "retire"));
    }

    @Test
    void onlyRecoveryAndTheWorkerEnumerateNonTerminalWork() {
        assertEquals(NON_TERMINAL_CALLERS, nonTerminalCallers(production()),
                "a new caller of OR-44's list is a new tenant enumeration and needs an owner ruling");
        Set<String> withPlanted = nonTerminalCallers(withPlanted());
        assertTrue(withPlanted.contains(PLANTED_CALL), "the planted caller must be seen. Was: " + withPlanted);
    }

    private static final String IDENTITY_PORT = "com.solace.sm.dp.api.agw.AgentIdentityPort";
    /** The gateway's two existing admission lookups (design 5.6); a THIRD gateway class resolving an identity is a new reader. */
    private static final String GATEWAY_SERVICE = "com.solace.sm.agw.AgentGatewayService";
    private static final Set<String> RESOLVERS = Set.of(
            "com.solace.sm.dp.apply.persistence.agent.JdbcAgentSessionRepository",
            "com.solace.sm.dp.apply.persistence.agent.JdbcAgentEnrollmentRepository", GATEWAY_SERVICE);
    private static final String PLANTED_GATEWAY_RESOLVER = "com.solace.sm.agw.archfixture.PlantedAgwResolvesIdentity";

    /** Callers of {@code resolveByThumbprint} AND of {@code resolve} on the port or the repository: the port is the same read. */
    private static Set<String> thumbprintResolvers(JavaClasses classes) {
        Set<String> callers = new TreeSet<>();
        classes.stream().forEach(c -> c.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().getName().equals(AGENT_IDENTITY_RESOLUTION)
                        || call.getTargetOwner().getName().equals(IDENTITY_PORT))
                .filter(call -> call.getTarget().getName().equals("resolveByThumbprint")
                        || call.getTarget().getName().equals("resolve"))
                .filter(call -> !outermost(c).equals(AGENT_IDENTITY_RESOLUTION))
                .forEach(call -> callers.add(outermost(c))));
        return callers;
    }

    @Test
    void onlyTheSessionEnrollmentAndGatewayClassesResolveACertificateToAnIdentity() {
        Set<String> callers = thumbprintResolvers(production());
        assertTrue(RESOLVERS.containsAll(callers),
                "a new caller of the one named unbound read (directly or through AgentIdentityPort#resolve) needs an owner ruling"
                        + " (I5). Found " + callers);
        assertTrue(callers.contains("com.solace.sm.dp.apply.persistence.agent.JdbcAgentSessionRepository"),
                "the rule must see the real caller, or it proves nothing. Found " + callers);
        assertTrue(callers.contains(GATEWAY_SERVICE), "and the gateway's port call. Found " + callers);
        Set<String> withPlanted = thumbprintResolvers(withPlanted());
        assertTrue(withPlanted.contains(PLANTED_THUMBPRINT_CALLER), "the planted second caller must be seen. Was: " + withPlanted);
        assertTrue(withPlanted.contains(PLANTED_GATEWAY_RESOLVER),
                "the planted gateway-shaped caller through the PORT must be seen. Was: " + withPlanted);
        assertFalse(RESOLVERS.contains(PLANTED_THUMBPRINT_CALLER) || RESOLVERS.contains(PLANTED_GATEWAY_RESOLVER));
    }

    /**
     * The second sanctioned derivation of an organization (plan 015 T2.1b fix round, finding 6): {@code AgentSessionRef} carries no
     * tenant, so {@code AgentAuthority#requireEpochHolder} (and so {@code requireOwner} and {@code requireSession}) reads
     * {@code agent_session JOIN agent_identity} by the globally unique {@code agent_id}. That statement is written once, there.
     */
    private static final String AGENT_AUTHORITY = "com.solace.sm.dp.apply.persistence.agent.AgentAuthority";
    private static final String PLANTED_AGENT_ID_READ = "com.solace.sm.dp.apply.persistence.agent.archfixture.PlantedUnboundAgentIdRead";
    /** The session-to-identity join whose only predicate is the agent id (a tenant-first predicate is bound, not derived). */
    private static final Pattern AGENT_ID_DERIVATION = Pattern.compile(
            "agent_session s JOIN sm_data\\.agent_identity i.{0,200}?WHERE s\\.agent_id = :agent");

    private static Set<String> agentIdDerivations(JavaClasses classes) {
        Set<String> found = new TreeSet<>();
        classes.stream().forEach(c -> {
            if (c.getSource().isEmpty() || c.getPackageName().equals("com.solace.sm.arch")) {
                return;
            }
            try (java.io.InputStream in = c.getSource().get().getUri().toURL().openStream()) {
                if (AGENT_ID_DERIVATION.matcher(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1)).find()) {
                    found.add(outermost(c));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return found;
    }

    @Test
    void onlyAgentAuthorityDerivesATenantFromAnAgentId() {
        assertEquals(Set.of(AGENT_AUTHORITY), agentIdDerivations(production()),
                "a second statement deriving an organization from an agent id alone needs an owner ruling (I5)");
        assertTrue(agentIdDerivations(withPlanted()).contains(PLANTED_AGENT_ID_READ), "the planted second read must be seen");
    }

    @Test
    void theSqlPatternMatchesTheShapesItIsMeantFor() {
        for (String sql : new String[] {"SELECT DISTINCT tenant_id FROM x", "select distinct\ttenant_id from x",
                "SELECT DISTINCT t.tenant_id FROM x t", "... GROUP BY tenant_id ORDER BY 1", "GROUP  BY  tenant_id"}) {
            assertTrue(ENUMERATING_SQL.matcher(sql).find(), sql);
        }
        for (String sql : new String[] {"SELECT DISTINCT placement_id FROM x", "SELECT tenant_id FROM x WHERE a = :a",
                "GROUP BY tenant_id_other_thing", "GROUP BY t.tenant_id, t.series_id, t.bucket",
                "SELECT DISTINCT tenant_id, series_id FROM x"}) {
            assertFalse(ENUMERATING_SQL.matcher(sql).find(), sql);
        }
    }
}

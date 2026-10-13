package com.solace.sm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.gateway.http.ConsoleOperation;
import com.solace.sm.gateway.http.CredentialBearing;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.4 ({@code I6}; D-REG sections 6.2 and 6.1 / OQ-14 (b)): the two build-time guards on a request that carries
 * a secret.
 *
 * <ol>
 *   <li><b>A record that carries a secret does not print it.</b> A record anywhere under {@code com.solace.sm} with a field named {@code privateKey}, {@code token}, {@code enrollmentToken}, {@code csrDer}, a name ending in {@code Nonce},
 *       {@code value}, or ending in {@code Credential} must declare its own {@code toString}. The compiler-generated one
 *       prints every component, so a log line that interpolates the request would carry the key.</li>
 *   <li><b>A handler that takes a private key or an initial/reset credential is {@code @CredentialBearing}</b>, which is
 *       what makes {@code CredentialTransportInterceptor} refuse it over an insecure channel before its body is read. A
 *       handler added later (rotation, the user routes) cannot forget the mark.</li>
 * </ol>
 *
 * <p>Each rule runs against production classes ({@link PlaneBoundaryTest.ExcludeArchFixtures}); the proofs re-import the
 * planted fixtures and show each rule reports them and does not report the legal forms.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class CredentialBearingRecordsTest {

    private static final String FIXTURES = "com.solace.sm.gateway.archfixture.credentials.";

    /**
     * The fields that hold a secret: a {@code String} or {@code char[]} named for the key itself, the secret literal, or
     * ending in {@code Credential}, plus the agent gateway's {@code token}, {@code enrollmentToken}, a name ending in
     * {@code Nonce}, and the CSR ({@code csrDer}, also as {@code byte[]}). (A component that merely IS NAMED credential and holds a summary object, as
     * {@code TargetConnection.credential} does, holds no secret and is not governed.)
     */
    private static boolean holdsASecret(JavaField field) {
        String name = field.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean text = field.getRawType().isEquivalentTo(String.class) || field.getRawType().isEquivalentTo(char[].class);
        // Plan 015 Final (review MINOR-3): the agent gateway's enrolment token, single-use lease nonce and CSR bytes, anywhere under
        // com.solace.sm. The generic name `value` stays a gateway-only pattern: elsewhere it is an ordinary identifier (ErrorClass,
        // ReplicaRef, a restart-read token) and governing it would force a redacting toString onto records that hold no secret.
        boolean bytes = field.getRawType().isEquivalentTo(byte[].class);
        boolean gateway = field.getOwner().getPackageName().startsWith("com.solace.sm.gateway");
        return !NOT_A_SECRET.contains(field.getOwner().getName())
                && ((text && (name.equals("privateKey") || (gateway && name.equals("value")) || lower.endsWith("credential")
                        || name.equals("token") || name.equals("enrollmentToken") || lower.endsWith("nonce")))
                || ((text || bytes) && name.equals("csrDer")));
    }

    /**
     * Named, reviewed exemptions: records whose field merely carries a governed NAME and holds no secret. {@code Previous.token} is
     * the metrics ingest's remembered restart-detection marker of a stored sample (a counter's restart token), and {@code ErrorClass.token}
     * is the dotted lower-case classification code of an error; neither is a credential.
     */
    private static final Set<String> NOT_A_SECRET = Set.of("com.solace.sm.dp.metrics.ingest.CounterSemantics$Previous",
            "com.solace.sm.dp.apply.model.ErrorClass");

    /** The narrower set OQ-14 (b) names: a private key, an initial credential, a reset credential. */
    private static boolean carriesACredentialOfOQ14(String field) {
        return field.equals("privateKey") || field.equals("initialCredential") || field.equals("resetCredential");
    }

    private static final DescribedPredicate<JavaClass> A_RECORD_WITH_A_SECRET =
            new DescribedPredicate<>("a record with a field that holds a secret") {
                @Override
                public boolean test(JavaClass type) {
                    return type.isRecord() && type.getFields().stream()
                            .anyMatch(CredentialBearingRecordsTest::holdsASecret);
                }
            };

    private static final ArchCondition<JavaClass> DECLARE_TO_STRING =
            new ArchCondition<>("declare its own toString (redacting the secret)") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    // javac gives every record a toString, and marks the GENERATED one final (measured with javap on the
                    // fixtures: `public final String toString()` for the generated, `public String toString()` for a
                    // written one). So "declared" means a toString that is not the final, generated one.
                    boolean declared = type.getMethods().stream()
                            .anyMatch(m -> m.getName().equals("toString") && m.getRawParameterTypes().isEmpty()
                                    && !m.getModifiers().contains(JavaModifier.FINAL));
                    if (!declared) {
                        events.add(SimpleConditionEvent.violated(type, type.getName()
                                + " carries a secret and does not override toString: the generated one prints it"));
                    }
                }
            };

    @ArchTest
    static final ArchRule aGatewayRecordThatCarriesASecretOverridesToString = classes()
            .that().resideInAPackage("com.solace.sm..").and(A_RECORD_WITH_A_SECRET)
            .should(DECLARE_TO_STRING)
            .because("I6: the compiler-generated toString of a record prints its components, key included");

    private static final DescribedPredicate<JavaMethod> TAKES_A_PRIVATE_KEY_OR_CREDENTIAL =
            new DescribedPredicate<>("takes a request record with a privateKey, initialCredential or resetCredential") {
                @Override
                public boolean test(JavaMethod method) {
                    return method.getRawParameterTypes().stream().anyMatch(type -> type.isRecord()
                            && type.getFields().stream().map(JavaField::getName)
                                    .anyMatch(CredentialBearingRecordsTest::carriesACredentialOfOQ14));
                }
            };

    @ArchTest
    static final ArchRule aHandlerThatTakesAPrivateKeyIsMarkedCredentialBearing = methods()
            .that().areAnnotatedWith(ConsoleOperation.class).and(TAKES_A_PRIVATE_KEY_OR_CREDENTIAL)
            .should().beAnnotatedWith(CredentialBearing.class)
            .because("OQ-14 (b): a key must not cross an insecure channel; the mark is what the transport check keys on");

    // ---- the proofs ----------------------------------------------------------------------------------------

    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    private static Set<String> violators(ArchRule rule) {
        Set<String> out = new TreeSet<>();
        rule.evaluate(withPlantedViolations()).getFailureReport().getDetails().forEach(out::add);
        return out;
    }

    @Test
    void aRecordThatPrintsItsKeyIsReportedWhateverTheFieldIsCalled() {
        Set<String> reported = violators(aGatewayRecordThatCarriesASecretOverridesToString);

        for (String bad : new String[] {"BadCredentialRequest", "BadValueRequest", "BadNamedCredentialRequest",
                "BadTokenRequest", "BadNonceRequest", "BadEnrollmentTokenRequest", "BadCsrDerRequest", "BadConsumedNonceRequest"}) {
            assertTrue(reported.stream().anyMatch(v -> v.contains(FIXTURES + bad)),
                    bad + " must be reported. Reported: " + reported);
        }
    }

    @Test
    void aRecordThatRedactsIsNotReportedAndNeitherIsAnyRealOne() {
        Set<String> reported = violators(aGatewayRecordThatCarriesASecretOverridesToString);

        assertFalse(reported.stream().anyMatch(v -> v.contains(FIXTURES + "GoodCredentialRequest")), reported.toString());
        assertFalse(reported.stream().anyMatch(v -> v.contains(FIXTURES + "GoodTokenRequest")), reported.toString());
        assertFalse(reported.stream().anyMatch(v -> !v.contains(FIXTURES)),
                "a real record prints its secret: " + reported);
        // ... and the real ones the rule governs ARE in the analysed set, so the empty report is not an accident
        JavaClasses all = withPlantedViolations();
        for (String real : new String[] {"com.solace.sm.gateway.http.RegisterTargetRequest",
                "com.solace.sm.gateway.http.SetConfigSecretRequest", "com.solace.sm.gateway.http.AgentEnrolment",
                "com.solace.sm.dp.api.agw.EnrollmentRequest", "com.solace.sm.dp.api.agw.LeaseGrant",
                "com.solace.sm.cp.api.agent.AgentEnrollmentRequest", "com.solace.sm.cp.api.agent.AgentEnrolment$IssuedToken",
                "com.solace.sm.cp.api.agent.AgentLeaseRequest"}) {
            assertTrue(A_RECORD_WITH_A_SECRET.test(all.get(real)), real + " must be one the rule governs");
        }
    }

    @Test
    void anUnmarkedHandlerThatTakesAKeyIsReportedAndAMarkedOneIsNot() {
        Set<String> reported = violators(aHandlerThatTakesAPrivateKeyIsMarkedCredentialBearing);

        assertTrue(reported.stream().anyMatch(v -> v.contains(FIXTURES + "UnmarkedCredentialHandler")),
                "the planted unmarked handler must be reported. Reported: " + reported);
        assertFalse(reported.stream().anyMatch(v -> v.contains(FIXTURES + "MarkedCredentialHandler")), reported.toString());
        assertFalse(reported.stream().anyMatch(v -> !v.contains(FIXTURES)),
                "a real handler takes a key without the mark: " + reported);
        JavaClasses all = withPlantedViolations();
        JavaMethod real = all.get("com.solace.sm.gateway.http.TargetRegistrationController").getMethods().stream()
                .filter(m -> m.getName().equals("registerTarget")).findFirst().orElseThrow();
        assertTrue(TAKES_A_PRIVATE_KEY_OR_CREDENTIAL.test(real), "registerTarget is one the rule governs");
    }
}

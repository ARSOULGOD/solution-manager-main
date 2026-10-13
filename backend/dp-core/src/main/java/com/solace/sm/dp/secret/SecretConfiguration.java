package com.solace.sm.dp.secret;

import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.HashKeySource;
import com.solace.sm.dp.apply.runtime.HmacConfigHasher;
import java.time.Clock;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The DP secret store as a bean set (plan 010 T2.4). Not auto-configured: the launcher imports it (T2.6),
 * together with {@code SecretPersistenceConfiguration}, which supplies the two persistence ports.
 *
 * <p><b>Fail-closed ordering.</b> {@link #kekCanaryCheck} runs inside its bean method, and
 * {@link #secretStore} takes it as a parameter, so a wrong or malformed key aborts context refresh before
 * any store exists. The apply worker and the startup sweep must depend on {@code kekCanaryCheck} too; that
 * edge is the launcher's to add (T2.6), and is tested here with a stand-in.
 *
 * <p><b>The clock is the data plane's, by name.</b> The launcher holds two {@code Clock} beans (the control plane's and
 * {@code smDataClock}), so an unqualified lookup fails the boot; absent {@code smDataClock} (a context without the apply
 * engine) the system clock is used.
 *
 * <p>The key is read as a plain string from the process environment only, and never bound into a typed property
 * (D-KEY 4.1); see {@link #keyLookup}. Looking up {@code SM_DP_MASTER_KEY} does not resolve
 * {@code sm.dp.master-key}: relaxed binding maps the other way (T2.6 M16).
 */
@Configuration(proxyBeanMethods = false)
public class SecretConfiguration {

    @Bean
    MasterKeyProvider masterKeyProvider(Environment environment) {
        return new EnvironmentMasterKeyProvider(keyLookup(environment));
    }

    public static final String TEST_ACCEPT_PROPERTY_SOURCES = "sm.dp.master-key.test-accept-property-sources";

    /**
     * The lookup the provider reads the six {@code SM_DP_MASTER_KEY*} names through: the process environment
     * only (OR-85). A value found in any other property source (a command-line argument, visible in {@code ps}; a
     * {@code -D} property; {@code SPRING_APPLICATION_JSON}; a properties file) refuses the boot, naming the
     * variable and never the value. {@link #TEST_ACCEPT_PROPERTY_SOURCES} lifts that for tests, which cannot set
     * a process environment variable.
     */
    static Function<String, String> keyLookup(Environment environment) {
        if (Boolean.parseBoolean(environment.getProperty(TEST_ACCEPT_PROPERTY_SOURCES))
                || !(environment instanceof ConfigurableEnvironment configurable)) {
            return environment::getProperty;
        }
        PropertySource<?> process = configurable.getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return name -> {
            Object fromProcess = process == null ? null : process.getProperty(name);
            String anywhere = environment.getProperty(name);
            if (anywhere != null && fromProcess == null) {
                throw new IllegalStateException(name + " is set in a property source other than the process "
                        + "environment; set it as an environment variable (or use " + name + "_FILE)");
            }
            return fromProcess == null ? null : fromProcess.toString();
        };
    }

    @Bean
    MasterKeyRing masterKeyRing(MasterKeyProvider masterKeyProvider) {
        return masterKeyProvider.load();
    }

    @Bean
    Envelope secretEnvelope() {
        return Envelope.standard();
    }

    @Bean
    KekCanaryCheck kekCanaryCheck(MasterKeyRing masterKeyRing, CanaryRows canaryRows, Envelope secretEnvelope,
            @Qualifier("smDataClock") ObjectProvider<Clock> clock) {
        KekCanaryCheck check = new KekCanaryCheck(masterKeyRing, canaryRows, secretEnvelope,
                clock.getIfAvailable(Clock::systemUTC));
        check.run();
        return check;
    }

    @Bean
    EnvelopeSecretStore secretStore(SecretRows secretRows, MasterKeyRing masterKeyRing, Envelope secretEnvelope,
            KekCanaryCheck kekCanaryCheck, @Qualifier("smDataClock") ObjectProvider<Clock> clock) {
        return new EnvelopeSecretStore(secretRows, masterKeyRing, secretEnvelope,
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * OR-32's per-tenant hash key, minted lazily in {@code sm_secret}. Depends on the canary check for the same
     * reason the store does: a wrong key must abort the context before any key is opened.
     */
    @Bean
    HashKeySource hashKeySource(SecretRows secretRows, MasterKeyRing masterKeyRing, Envelope secretEnvelope,
            KekCanaryCheck kekCanaryCheck, @Qualifier("smDataClock") ObjectProvider<Clock> clock) {
        return new EnvelopeHashKeySource(secretRows, masterKeyRing, secretEnvelope,
                clock.getIfAvailable(Clock::systemUTC));
    }

    /** The one production {@link ConfigHasher}: keyed, per tenant. Consumed at apply wiring (plan 010 T6.2). */
    @Bean
    ConfigHasher configHasher(HashKeySource hashKeySource) {
        return new HmacConfigHasher(hashKeySource);
    }
}

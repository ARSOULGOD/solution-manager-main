package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Plan 013 T1.2: the policy is a bean, the strict one is what production gets, and {@code sm.dp.targets.allowed-networks}
 * binds to it. A test that needs loopback imports {@link LoopbackTargetAddressConfiguration}, whose {@code @Primary} bean
 * wins; nothing else can change which policy is wired.
 */
class TargetAddressConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TargetAddressConfiguration.class);

    @Test
    void productionWiresTheStrictPolicyAndTheScanServiceOverIt() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
            TargetAddressPolicy policy = context.getBean(TargetAddressPolicy.class);

            assertInstanceOf(StrictTargetAddressPolicy.class, policy);
            assertThrows(TargetAddressRefusedException.class, () -> policy.check("127.0.0.1"));
            assertNotNull(context.getBean(HostKeyScanService.class));
            assertThrows(TargetAddressRefusedException.class,
                    () -> context.getBean(HostKeyScanService.class).scan("169.254.169.254", 22),
                    "the scan service is built over the strict policy");
        });
    }

    @Test
    void theAllowListBindsFromTheProperty() {
        runner.withPropertyValues("sm.dp.targets.allowed-networks[0]=10.0.0.0/8",
                "sm.dp.targets.allowed-networks[1]=fd00::/8").run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    assertEquals(java.util.List.of("10.0.0.0/8", "fd00::/8"),
                            context.getBean(TargetAddressProperties.class).allowedNetworks());
                    TargetAddressPolicy policy = context.getBean(TargetAddressPolicy.class);

                    assertEquals(1, policy.check("10.1.2.3").size());
                    assertThrows(TargetAddressRefusedException.class, () -> policy.check("192.168.1.1"));
                });
    }

    @Test
    void aCommaSeparatedValueBindsToo() {
        runner.withPropertyValues("sm.dp.targets.allowed-networks=10.0.0.0/8,192.168.0.0/16").run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
            assertEquals(2, context.getBean(TargetAddressProperties.class).allowedNetworks().size());
        });
    }

    @Test
    void aBadEntryFailsTheBootNamingTheProperty() {
        runner.withPropertyValues("sm.dp.targets.allowed-networks[0]=not-a-network").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "a malformed allow-list must not boot");
            Throwable cause = failure;
            boolean named = false;
            while (cause != null) {
                named |= String.valueOf(cause.getMessage()).contains("sm.dp.targets.allowed-networks");
                cause = cause.getCause();
            }
            assertTrue(named, "the failure names the property: " + failure);
        });
    }

    @Test
    void noPropertyIsNoAllowListAndNothingElseRelaxesTheRule() {
        runner.withPropertyValues("sm.dp.targets.strict=false", "sm.dp.targets.permissive=true",
                "sm.dp.targets.enabled=false").run(context -> {
                    assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
                    assertEquals(java.util.List.of(), context.getBean(TargetAddressProperties.class).allowedNetworks());
                    assertThrows(TargetAddressRefusedException.class,
                            () -> context.getBean(TargetAddressPolicy.class).check("127.0.0.1"),
                            "no property reaches the built-in refusals");
                });
    }

    @Test
    void aTestsPrimaryBeanWinsOverTheStrictOne() {
        runner.withUserConfiguration(LoopbackTargetAddressConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));

            assertInstanceOf(PermissiveTargetAddressPolicy.class, context.getBean(TargetAddressPolicy.class));
            assertEquals(2, context.getBeansOfType(TargetAddressPolicy.class).size());
        });
    }
}

package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Plan 010 T2.6: importing the secret store makes the master key a precondition of the PROCESS. The production
 * composition, given every other property it needs, refuses to start without it and names the variable — rather than
 * booting and failing on the first SSH connection.
 */
class MasterKeyRequiredAtBootIT {

    @Test
    void aBootWithoutAMasterKeyRefusesToStartNamingTheVariable() {
        Map<String, Object> properties = new LinkedHashMap<>(TwoSchemaTestDatabase.provisionStandalone().properties());
        assertTrue(properties.remove(TwoSchemaTestDatabase.MASTER_KEY) != null,
                "the harness registers a test key; this test deliberately withholds it");

        Exception refused = null;
        try {
            new SpringApplicationBuilder(SolutionManagerApplication.class).web(WebApplicationType.NONE)
                    .properties(properties).run().close();
            fail("the production composition must not boot without SM_DP_MASTER_KEY");
        } catch (Exception e) {
            refused = e;
        }
        StringBuilder chain = new StringBuilder();
        for (Throwable t = refused; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        assertTrue(chain.toString().contains("SM_DP_MASTER_KEY"), chain.toString());
        assertFalse(chain.toString().contains(properties.getOrDefault("sm.data.runtime.password", "?").toString()),
                "the refusal names the variable, never a credential");
    }
}

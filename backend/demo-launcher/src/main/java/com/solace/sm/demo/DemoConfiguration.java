package com.solace.sm.demo;

import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerConfigEditor;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerDiagnostics;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerMetrics;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerRuntimeAdapter;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DemoConfiguration {
    static final PlacementRef DEMO_PLACEMENT = new PlacementRef(
            new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000101")),
            new InstanceId(UUID.fromString("00000000-0000-0000-0000-000000000102")),
            new PlacementId(UUID.fromString("00000000-0000-0000-0000-000000000103")));

    @Bean
    DemoPlacementCatalog placementCatalog() {
        return new DemoPlacementCatalog();
    }

    @Bean
    SshDockerRuntimeAdapter runtimeAdapter(DemoPlacementCatalog catalog) {
        return new SshDockerRuntimeAdapter(catalog);
    }

    @Bean
    SshDockerConfigEditor configEditor(DemoPlacementCatalog catalog) {
        return new SshDockerConfigEditor(catalog);
    }

    @Bean
    SshDockerDiagnostics diagnostics(DemoPlacementCatalog catalog) {
        return new SshDockerDiagnostics(catalog);
    }

    @Bean
    SshDockerMetrics metrics(DemoPlacementCatalog catalog) {
        return new SshDockerMetrics(catalog);
    }
}

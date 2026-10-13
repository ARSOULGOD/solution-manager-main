package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import java.nio.file.Path;
import java.util.UUID;

/** The kit, run against the in-memory adapter. Without this class the kit runs zero tests. */
class InMemoryRuntimeAdapterContractTest extends AbstractRuntimeAdapterContractTest {

    @Override
    protected Fixture newFixture() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        InMemoryRuntimeAdapter adapter =
                new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        return new Fixture(adapter, placement, adapter.replicas(),
                new ConfigLocation("/app/external/spring/config", new WriterAccess.LocalFilesystem(Path.of("/dev/null")),
                        "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION),
                true, TestHashers.SHA256);
    }
}

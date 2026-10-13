package com.solace.sm.dp.apply.runtime.docker;

import com.solace.sm.dp.apply.runtime.AbstractRuntimeAdapterContractTest;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same obligations as {@code InMemoryRuntimeAdapterContractTest}, against a real daemon.
 *
 * <p>Where the in-memory subclass skips {@code startOrUpdateNeverChangesTheArtifact} by assumption,
 * this one does not: Docker publishes an image identity, so the {@code I1} obligation is live here.
 */
@Testcontainers(disabledWithoutDocker = true)
class DockerAdapterContractIT extends AbstractRuntimeAdapterContractTest {

    private final List<DockerFixtures> fixtures = new ArrayList<>();
    private final List<DockerEngineClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        clients.forEach(DockerEngineClient::close);
        clients.clear();
        fixtures.forEach(DockerFixtures::close);
        fixtures.clear();
    }

    @Override
    protected Fixture newFixture() {
        DockerFixtures f = new DockerFixtures();
        fixtures.add(f);
        DockerEngineClient engine = DockerFixtures.client();
        clients.add(engine);

        Path dir = DockerFixtures.newConfigDir();
        String prefix = "sm-plan005-kit-" + UUID.randomUUID().toString().substring(0, 8);
        f.start(prefix + "-1", dir, DockerFixtures.Shape.DECLARED_AND_MOUNTED, true);

        PlacementRef ref = DockerFixtures.newPlacement();
        DockerPlacement placement = DockerFixtures.placement(prefix, dir);
        // The kit runs over the KEYED hasher: the adapter's store hashes read-back bytes per tenant (OR-32).
        ConfigHasher hasher = TestHashers.keyed();
        DockerAdapter adapter = new DockerAdapter(engine,
                DockerFixtures.catalogFor(ref, placement), DockerFixtures.store(hasher),
                DockerFixtures.props());

        PreflightResult result = adapter.prepare(ref, 1);
        if (result instanceof PreflightResult.Refused refused) {
            throw new AssertionError("pre-flight refused: " + refused.reason().token());
        }
        PreflightResult.Ready ready = (PreflightResult.Ready) result;
        return new Fixture(adapter, ref, ready.replicas(), placement.location(),
                ready.healthSignalDeclared(), hasher);
    }
}

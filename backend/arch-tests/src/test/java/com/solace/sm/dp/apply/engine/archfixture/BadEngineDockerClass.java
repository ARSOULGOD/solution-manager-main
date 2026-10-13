package com.solace.sm.dp.apply.engine.archfixture;

import com.github.dockerjava.transport.DockerHttpClient;

/**
 * A planted violation: the apply ENGINE holding a Docker client type.
 *
 * <p>{@code BE-D12} §2.3's named obligation is that the core apply packages must not depend on
 * Docker — or on any adapter's client library — with a planted-violation proof, exactly as
 * {@code onlyMeshbridgeTouchesJcsmp} confines JCSMP today. An engine that could reach a daemon
 * directly would be an engine that could bypass the seam it exists to speak through.
 *
 * <p>Excluded from the real {@code @ArchTest} run by {@code ExcludeArchFixtures}; the proof test
 * re-imports it without that filter.
 */
public final class BadEngineDockerClass {

    private DockerHttpClient client;

    public DockerHttpClient client() {
        return client;
    }
}

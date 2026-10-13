package com.solace.sm.launcher.archfixture;

import com.github.dockerjava.transport.DockerHttpClient;

/** Planted violation: the launcher holding a Docker client (rule {@code noHttpLayerHoldsATransport}). */
public final class BadLauncherDockerClientClass {

    private DockerHttpClient client;

    public DockerHttpClient client() {
        return client;
    }
}

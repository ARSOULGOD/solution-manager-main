package com.solace.sm.dp.apply.runtime.docker.archfixture;

import com.github.dockerjava.transport.DockerHttpClient;

/**
 * The <b>legal</b> control for {@code onlyTheDockerAdapterTouchesDockerJava}: a Docker client type
 * held inside the adapter's own package, which is exactly where confining it is the point.
 *
 * <p>A confinement rule that also rejected the code it governs would be broken rather than strict,
 * and the proof asserts this class's PRESENCE in the analysed set before asserting its ABSENCE from
 * the failure message — otherwise that absence passes for free the day the class is renamed away.
 */
public final class LegalDockerClientClass {

    private DockerHttpClient client;

    public DockerHttpClient client() {
        return client;
    }
}

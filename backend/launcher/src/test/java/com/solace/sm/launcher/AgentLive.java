package com.solace.sm.launcher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The prerequisites of the real-agent suites (plan 015 T4.4) and the rule that they FAIL, never skip: a missing {@code go}, Docker daemon,
 * {@value #ALPINE} image or {@value #CACHE_IMAGE} image is an error with a message that names the repair.
 *
 * <p>The ordinary build does not run these classes at all (the {@code agent-live-excluded} profile of {@code launcher/pom.xml}, announced
 * by {@code AgentLiveExclusionNoticeTest}); {@code -Pagent-live} runs them with {@code sm.agent.live=true}. Because {@code -Dit.test}
 * overrides failsafe's excludes, every live class calls {@link #require()} from its own set-up as well: a class that is run by name with
 * a tool missing fails here rather than skipping or half-running.
 */
final class AgentLive {

    /** The managed integration of every drill but the Cache one: small enough that a restart is quick, and present locally. */
    static final String ALPINE = "alpine:3.17";
    /** The real Cache, built by plan 015 T0.4 step 3 ({@code docker build -t solace-cache-engine:local}). */
    static final String CACHE_IMAGE = "solace-cache-engine:local";

    private static volatile String checked;

    private AgentLive() {
    }

    /** True under {@code -Pagent-live}. */
    static boolean profileActive() {
        return Boolean.getBoolean("sm.agent.live");
    }

    /** The missing prerequisites, empty when everything is there. */
    static List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (!AgentTestSupport.run(List.of("go", "version"), Duration.ofSeconds(20)).ok()) {
            missing.add("go is not on the PATH (install it: brew install go)");
        }
        if (!AgentTestSupport.run(List.of("docker", "info"), Duration.ofSeconds(30)).ok()) {
            missing.add("the Docker daemon is not reachable (start Docker Desktop)");
        }
        for (String image : List.of(ALPINE, CACHE_IMAGE)) {
            if (!AgentTestSupport.run(List.of("docker", "image", "inspect", image), Duration.ofSeconds(30)).ok()) {
                missing.add("the image " + image + " is not present locally"
                        + (image.equals(CACHE_IMAGE) ? " (plan 015 T0.4 step 3 builds it from solace-cache-engine)" : " (docker pull " + image + ")"));
            }
        }
        return missing;
    }

    /** Fails the calling class when anything is missing; remembers a success for the rest of the JVM. */
    static synchronized void require() {
        if (checked != null) {
            return;
        }
        List<String> missing = missing();
        if (!missing.isEmpty()) {
            throw new AssertionError("the real-agent suites cannot run" + (profileActive() ? " (-Pagent-live)" : "")
                    + ": " + String.join("; ", missing));
        }
        checked = "ok";
    }
}

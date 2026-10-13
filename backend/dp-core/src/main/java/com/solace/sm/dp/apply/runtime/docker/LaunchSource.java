package com.solace.sm.dp.apply.runtime.docker;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * What the container's launch spec says about where it reads configuration — {@code SM-D21}'s
 * "enrollment pre-flight inspects the actual launch command".
 *
 * <p>Reads {@code Config.Entrypoint}, {@code Config.Cmd} and {@code Config.Env}, in that order, and
 * <b>nothing else</b>. Env is scanned for exactly two names; every other variable is left untouched
 * and unreported, because a container's environment is the most secret-dense thing the daemon will
 * hand over ({@code I6}).
 *
 * <p>The split between declared and competing is {@code architecture.md} §6.1's precedence trap: a
 * command-line property outranks an environment variable, so an env-set location beside a
 * command-line one is a source SM does not own — not a second declaration of the one it does.
 */
public record LaunchSource(List<String> declaredLocations, List<String> competingLocations) {

    private static final String CLI_ADDITIONAL = "--spring.config.additional-location=";
    private static final String CLI_LOCATION = "--spring.config.location=";
    private static final String ENV_ADDITIONAL = "SPRING_CONFIG_ADDITIONAL_LOCATION=";
    private static final String ENV_LOCATION = "SPRING_CONFIG_LOCATION=";

    public LaunchSource {
        declaredLocations = List.copyOf(declaredLocations);
        competingLocations = List.copyOf(competingLocations);
    }

    public static LaunchSource of(JsonNode inspect) {
        List<String> declared = new ArrayList<>();
        List<String> competing = new ArrayList<>();
        JsonNode config = inspect.path("Config");

        for (String arg : strings(config.path("Entrypoint"))) {
            collectCommandLine(arg, declared, competing);
        }
        for (String arg : strings(config.path("Cmd"))) {
            collectCommandLine(arg, declared, competing);
        }
        for (String env : strings(config.path("Env"))) {
            if (env.startsWith(ENV_ADDITIONAL)) {
                competing.add(env.substring(ENV_ADDITIONAL.length()));
            } else if (env.startsWith(ENV_LOCATION)) {
                competing.add(env.substring(ENV_LOCATION.length()));
            }
        }
        return new LaunchSource(declared, competing);
    }

    private static void collectCommandLine(String arg, List<String> declared,
            List<String> competing) {
        if (arg.startsWith(CLI_ADDITIONAL)) {
            declared.add(arg.substring(CLI_ADDITIONAL.length()));
        } else if (arg.startsWith(CLI_LOCATION)) {
            competing.add(arg.substring(CLI_LOCATION.length()));
        }
    }

    private static List<String> strings(JsonNode array) {
        // MEASURED: Config.Entrypoint is JSON null on a container started with a plain command, and
        // path() on a missing field gives a MissingNode. Neither is an array; both fall through to
        // the empty list rather than throwing. A parser that read only Entrypoint would see nothing
        // at all on such a container.
        if (!array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}

package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T6.3: {@code console.yaml}'s {@code ApplyObservation.state} is {@code RequestState} verbatim (ruling
 * {@code OR-36}) - the gateway cannot import {@code dp-core}'s enum, so this is where the two are held together. The
 * contract also names {@code terminal} as the complement of the three open states, which is {@code isTerminal()}.
 */
class RequestStateContractMirrorTest {

    @Test
    void theContractsApplyObservationStateEnumIsExactlyRequestState() throws IOException {
        String contract = Files.readString(contractPath());
        int schema = contract.indexOf("\n    ApplyObservation:");
        assertTrue(schema > 0, "the ApplyObservation schema must exist");
        Matcher matcher = Pattern.compile("enum: \\[(REQUESTED[^\\]]*)\\]", Pattern.DOTALL)
                .matcher(contract.substring(schema));
        assertTrue(matcher.find(), "the state enum must be spelt out");
        Set<String> declared = new TreeSet<>();
        Arrays.stream(matcher.group(1).split(",")).map(String::trim).forEach(declared::add);

        Set<String> engine = new TreeSet<>();
        Arrays.stream(RequestState.values()).map(Enum::name).forEach(engine::add);

        assertEquals(engine, declared);
    }

    @Test
    void theThreeOpenStatesAreTheOnesTheContractNames() {
        Set<RequestState> open = new java.util.HashSet<>();
        for (RequestState state : RequestState.values()) {
            if (!state.isTerminal()) {
                open.add(state);
            }
        }
        assertEquals(Set.of(RequestState.REQUESTED, RequestState.RUNNING, RequestState.APPLY_INDETERMINATE), open);
    }

    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("contracts/openapi/console.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("contracts/openapi/console.yaml not found from " + Path.of("").toAbsolutePath());
    }
}

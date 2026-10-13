package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A body built for a code with no title fails its own non-null check and the ORIGINAL refusal is lost behind a 500
 * (plan 011 P11-4.1 flagged it; P11-7.2 adds the six monitoring titles). Every {@link ErrorCode} has one.
 */
class ProblemBodyTitlesTest {

    @Test
    void everyErrorCodeHasAFixedTitleAndItsOwnStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            ProblemBody body = ProblemBody.of(code, UUID.randomUUID());
            assertFalse(body.title().isBlank(), code.name());
            assertEquals(code.status(), body.status(), code.name());
            assertEquals(code, body.code());
        }
    }
}

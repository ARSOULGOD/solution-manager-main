package com.solace.sm.dp.api.agw.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import org.junit.jupiter.api.function.Executable;

/** The two assertions every kit repeats. */
final class KitAsserts {

    private KitAsserts() {
    }

    /** The call is refused with exactly this gateway code (a coded refusal, never an unclassified failure). */
    static GatewayPortException refused(GatewayCode code, Executable call) {
        GatewayPortException e = assertThrows(GatewayPortException.class, call);
        assertEquals(code, e.code(), "refusal code");
        return e;
    }
}

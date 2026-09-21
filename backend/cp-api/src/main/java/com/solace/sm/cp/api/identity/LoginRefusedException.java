package com.solace.sm.cp.api.identity;

/** Generic refusal for unknown, invalid, inactive, or temporarily locked local login attempts. */
public final class LoginRefusedException extends RuntimeException {

    public LoginRefusedException() {
        super("authentication failed");
    }
}

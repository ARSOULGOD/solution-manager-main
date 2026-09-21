package com.solace.sm.cp.identity;

/** Non-catalog operations permitted while a local credential must be rotated. */
public enum LocalOperation {
    ROTATE_OWN_CREDENTIAL,
    LOGOUT,
    READ_OWN_IDENTITY
}

package com.solace.sm.dp.secret;

import java.util.Base64;

/** Test-only key material. The bytes are a fill pattern, never a real key. */
final class Keys {

    private Keys() {
    }

    static byte[] bytes(int fill) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    static MasterKey key(String id, int fill) {
        return MasterKey.of(new KeyId(id), bytes(fill));
    }

    static String base64(int fill) {
        return Base64.getEncoder().encodeToString(bytes(fill));
    }
}

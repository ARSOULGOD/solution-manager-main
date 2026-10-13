package com.solace.sm.dp.secret;

import java.util.Objects;

/**
 * What the database stores for one secret (D-KEY 3.2): the id of the key that wraps the data key, the
 * wrapped data key and its IV, the value's IV, and the value ciphertext. Everything here is ciphertext or
 * a label, and {@link #toString} prints only the label.
 */
public record SealedSecret(KeyId keyId, byte[] wrappedDek, byte[] dekIv, byte[] valueIv, byte[] ciphertext,
        int aadVersion) {

    public SealedSecret {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(wrappedDek, "wrappedDek");
        Objects.requireNonNull(dekIv, "dekIv");
        Objects.requireNonNull(valueIv, "valueIv");
        Objects.requireNonNull(ciphertext, "ciphertext");
    }

    @Override
    public String toString() {
        return "SealedSecret[keyId=" + keyId + ", aadVersion=" + aadVersion + "]";
    }
}

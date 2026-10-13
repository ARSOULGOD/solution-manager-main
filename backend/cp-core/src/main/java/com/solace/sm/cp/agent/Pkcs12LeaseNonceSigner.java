package com.solace.sm.cp.agent;

import com.solace.sm.cp.config.AgentGatewayCaProperties;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 lease nonces under a secret-key entry in the operator's CA keystore (the keystore the CA key already comes
 * from, opened with the same password). The key is never derived from a public input, never logged and never printed: this
 * class's {@code toString} and every exception message name neither the key nor its bytes (I6).
 *
 * <p><b>Input encoding.</b> {@code "sm-agent-lease-nonce-v1"} as a domain tag, then each of (request id, agent id, thumbprint,
 * consumed nonce) as a 4-byte big-endian length followed by its UTF-8 bytes; an absent consumed nonce is length {@code -1},
 * which an empty string (length 0) cannot collide with. So no field boundary can move.
 *
 * <p>The key is read on first use and kept for the life of the process; replacing it needs a restart. A key shorter than 32
 * bytes is refused. Without the entry nothing is issued.
 */
public final class Pkcs12LeaseNonceSigner implements LeaseNonceSigner {

    static final String DOMAIN = "sm-agent-lease-nonce-v1";
    static final int MIN_KEY_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final AgentGatewayCaProperties properties;
    private volatile byte[] key;

    public Pkcs12LeaseNonceSigner(AgentGatewayCaProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void verifyOperable() {
        key();
    }

    @Override
    public String nonce(UUID agentId, String requestId, String certificateThumbprint, String consumedNonce) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(certificateThumbprint, "certificateThumbprint");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key(), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(encode(requestId, agentId.toString(), certificateThumbprint, consumedNonce)));
        } catch (java.security.GeneralSecurityException e) {
            // Not chained: a provider's message is not ours to forward.
            throw new IllegalStateException("lease nonce HMAC is unusable");
        }
    }

    static byte[] encode(String requestId, String agentId, String thumbprint, String consumedNonce) {
        byte[][] fields = new byte[][] {requestId.getBytes(StandardCharsets.UTF_8), agentId.getBytes(StandardCharsets.UTF_8),
                thumbprint.getBytes(StandardCharsets.UTF_8),
                consumedNonce == null ? null : consumedNonce.getBytes(StandardCharsets.UTF_8)};
        byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
        int size = 4 + domain.length;
        for (byte[] f : fields) size += 4 + (f == null ? 0 : f.length);
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putInt(domain.length).put(domain);
        for (byte[] f : fields) {
            if (f == null) {
                out.putInt(-1);
            } else {
                out.putInt(f.length).put(f);
            }
        }
        return out.array();
    }

    private byte[] key() {
        byte[] loaded = key;
        if (loaded != null) return loaded;
        synchronized (this) {
            if (key != null) return key;
            if (properties.getKeyStoreFile().isBlank() || properties.getKeyStorePassword().isBlank()
                    || properties.getLeaseNonceKeyAlias().isBlank()) {
                throw new IllegalStateException("lease nonce key is not configured");
            }
            try {
                KeyStore store = KeyStore.getInstance(properties.getKeyStoreType());
                char[] password = properties.getKeyStorePassword().toCharArray();
                try (InputStream input = Files.newInputStream(Path.of(properties.getKeyStoreFile()))) {
                    store.load(input, password);
                }
                Key entry = store.getKey(properties.getLeaseNonceKeyAlias(), password);
                byte[] material = entry == null || !"RAW".equalsIgnoreCase(entry.getFormat()) && entry.getEncoded() == null
                        ? null : entry.getEncoded();
                if (material == null || material.length < MIN_KEY_BYTES) {
                    if (material != null) Arrays.fill(material, (byte) 0);
                    throw new IllegalStateException("lease nonce key entry is missing or shorter than " + MIN_KEY_BYTES + " bytes");
                }
                key = material;
                return material;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("lease nonce key cannot be loaded");
            }
        }
    }

    /** Names no key and no byte of one. */
    @Override
    public String toString() {
        return "Pkcs12LeaseNonceSigner";
    }
}

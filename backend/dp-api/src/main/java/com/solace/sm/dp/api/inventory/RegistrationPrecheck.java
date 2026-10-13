package com.solace.sm.dp.api.inventory;

import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import java.util.List;
import java.util.Objects;

/**
 * What {@link TargetConnectionAdministration#precheckRegistration} learned without writing anything (plan 013 T1.4,
 * D-REG section 8 step 2): every host key the host offered (the registration records them all, {@code OR-89}) and the
 * fingerprint of the private key's PUBLIC half (what a replay compares). Neither is a secret.
 */
public record RegistrationPrecheck(List<OfferedHostKey> offeredHostKeys, String publicKeyFingerprint) {

    public RegistrationPrecheck {
        offeredHostKeys = List.copyOf(Objects.requireNonNull(offeredHostKeys, "offeredHostKeys"));
        Objects.requireNonNull(publicKeyFingerprint, "publicKeyFingerprint");
    }

    /**
     * The type of the offered key with this fingerprint, if the host offered it and the data plane pins by its type
     * (OR-125 (2)): what the registration stores beside the pin. Empty for a key not offered or of a type it does not pin by.
     */
    public java.util.Optional<HostKeyType> typeOf(String fingerprint) {
        return offeredHostKeys.stream().filter(key -> key.fingerprint().equals(fingerprint)).findFirst()
                .flatMap(key -> HostKeyType.ofWire(key.algorithm()));
    }

    /** The offered fingerprints, in the order the host offered them. */
    public List<String> offeredFingerprints() {
        return offeredHostKeys.stream().map(OfferedHostKey::fingerprint).toList();
    }
}

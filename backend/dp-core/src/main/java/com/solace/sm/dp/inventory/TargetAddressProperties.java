package com.solace.sm.dp.inventory;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code sm.dp.targets.*} (plan 013 T1.2, D-REG section 7.3).
 *
 * @param allowedNetworks the networks a registered host may resolve into, as CIDR blocks ({@code 10.0.0.0/8},
 *                        {@code fd00::/8}). <b>Empty means no restriction beyond the built-in refusals</b> (loopback,
 *                        link-local, unspecified, multicast), which no setting can lift. When set, every address a
 *                        host resolves to must lie in one of them. An entry that is not a CIDR block fails the boot,
 *                        naming the property.
 */
@ConfigurationProperties(prefix = "sm.dp.targets")
public record TargetAddressProperties(List<String> allowedNetworks) {

    public TargetAddressProperties {
        allowedNetworks = allowedNetworks == null ? List.of() : List.copyOf(allowedNetworks);
        for (String entry : allowedNetworks) {
            StrictTargetAddressPolicy.parseNetwork(entry);
        }
    }

    /** No allow-list. */
    public static TargetAddressProperties unrestricted() {
        return new TargetAddressProperties(List.of());
    }
}

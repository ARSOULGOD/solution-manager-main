package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one {@link TargetAddressPolicy} production wires (plan 013 T1.2, D-REG section 7.3, {@code OR-121} OQ-3 (d)).
 *
 * <p>It resolves the host and refuses if <b>any</b> resolved address is loopback, link-local (the cloud metadata
 * address {@code 169.254.169.254} among them), unspecified or multicast, IPv4 or IPv6, or, when
 * {@code sm.dp.targets.allowed-networks} is set, lies outside every listed network. An IPv4-compatible IPv6 address
 * ({@code ::127.0.0.1}) is judged as the IPv4 address it embeds, an IPv4-mapped one ({@code ::ffff:a.b.c.d}, which a
 * resolver can return as an {@code Inet6Address}) is normalised to the IPv4 address the connect would reach, and the IPv4
 * inside NAT64 and 6to4 is judged too; 0.0.0.0/8, 255.255.255.255 and fec0::/10 are refused. The checks run on the resolved {@link InetAddress}, so every spelling the JDK accepts for an address
 * ({@code 127.1}, a decimal integer) is judged by what it resolves to, which is what the connectors will connect to.
 * Nothing relaxes the four built-in refusals: not the allow-list, not a property.
 *
 * <p>Private ranges ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}, {@code fc00::/7}) are NOT refused: they are
 * where a customer's hosts live. Narrowing them is the allow-list's job.
 */
public final class StrictTargetAddressPolicy implements TargetAddressPolicy {

    /** Name resolution, replaceable so a test can make a name resolve to loopback without a DNS server. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final Pattern IPV4_LITERAL = Pattern.compile("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$");
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final List<Network> allowed;
    private final Resolver resolver;

    public StrictTargetAddressPolicy(TargetAddressProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    public StrictTargetAddressPolicy(TargetAddressProperties properties, Resolver resolver) {
        Objects.requireNonNull(properties, "properties");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        List<Network> networks = new ArrayList<>();
        for (String entry : properties.allowedNetworks()) {
            networks.add(parseNetwork(entry));
        }
        this.allowed = List.copyOf(networks);
    }

    @Override
    public List<InetAddress> check(String host) throws UnknownHostException {
        InetAddress[] resolved = resolver.resolve(host);
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException();
        }
        List<InetAddress> judged = new ArrayList<>();
        for (InetAddress address : resolved) {
            judged.add(judge(address));
        }
        return List.copyOf(judged);
    }

    /** The first judged address, as the very object judged: what is connected to is exactly what was checked. */
    @Override
    public InetAddress connectable(String host) throws UnknownHostException {
        InetAddress checked = check(host).get(0);
        return InetAddress.getByAddress(host, checked.getAddress());
    }

    /**
     * Judges one resolved address and returns the one to connect to. The JDK can hand back an {@code Inet6Address}
     * holding an IPv4-mapped address ({@code ::ffff:a.b.c.d}, as a resolver may for an AAAA record), and
     * {@code InetAddress.getByAddress(name, bytes)} turns those 16 bytes into an {@code Inet4Address}, so the address
     * is normalised FIRST, and the original, the normalised and every embedded IPv4 are all judged.
     */
    private InetAddress judge(InetAddress original) throws UnknownHostException {
        InetAddress normalised = InetAddress.getByAddress(original.getAddress());
        InetAddress unwrapped = embeddedIpv4(normalised);
        if (neverConnectable(original) || neverConnectable(normalised) || neverConnectable(unwrapped)
                || reservedIpv6(normalised)) {
            throw new TargetAddressRefusedException();
        }
        InetAddress tunnelled = tunnelledIpv4(normalised);
        if (tunnelled != null && neverConnectable(tunnelled)) {
            throw new TargetAddressRefusedException();
        }
        if (!allowed.isEmpty() && allowed.stream().noneMatch(network -> network.contains(unwrapped))) {
            throw new TargetAddressRefusedException();
        }
        return normalised;
    }

    private static boolean neverConnectable(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        // 0.0.0.0/8 ("this network") and the limited broadcast address
        return b.length == 4 && (b[0] == 0 || (b[0] & b[1] & b[2] & b[3]) == (byte) 0xFF);
    }

    /** fec0::/10, the deprecated site-local range. Unique-local fc00::/7 is deliberately NOT refused (customer hosts). */
    private static boolean reservedIpv6(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 16 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0xC0;
    }

    /** The IPv4 inside a NAT64 ({@code 64:ff9b::/96}) or 6to4 ({@code 2002::/16}) address, else null. */
    private static InetAddress tunnelledIpv4(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 16) {
            return null;
        }
        try {
            boolean nat64 = b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                    && Arrays.equals(Arrays.copyOfRange(b, 4, 12), new byte[8]);
            if (nat64) {
                return InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16));
            }
            if (b[0] == 0x20 && b[1] == 0x02) {
                return InetAddress.getByAddress(Arrays.copyOfRange(b, 2, 6));
            }
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    /** {@code ::a.b.c.d} (IPv4-compatible, deprecated but still parsed) is judged as {@code a.b.c.d}. */
    private static InetAddress embeddedIpv4(InetAddress address) {
        if (address instanceof Inet6Address v6 && v6.isIPv4CompatibleAddress()) {
            try {
                return InetAddress.getByAddress(Arrays.copyOfRange(v6.getAddress(), 12, 16));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
        return address;
    }

    /**
     * Parses one {@code sm.dp.targets.allowed-networks} entry. Only an IP LITERAL and a prefix length are accepted, and
     * the literal is shape-checked before it reaches the JDK, so a hostname can never reach {@code getByName} and cause
     * a DNS lookup at configuration time.
     *
     * @throws IllegalArgumentException the entry is not a CIDR block
     */
    static Network parseNetwork(String entry) {
        String property = "sm.dp.targets.allowed-networks entry";
        if (entry == null) {
            throw new IllegalArgumentException(property + " must be a CIDR block such as 10.0.0.0/8");
        }
        String[] parts = entry.trim().split("/", -1);
        if (parts.length != 2 || parts[1].isEmpty() || !parts[1].matches("[0-9]{1,3}")) {
            throw new IllegalArgumentException(property + " must be a CIDR block such as 10.0.0.0/8 or fd00::/8, was ["
                    + entry + "]");
        }
        boolean v4 = IPV4_LITERAL.matcher(parts[0]).matches();
        if (v4) {
            for (String octet : parts[0].split("\\.")) {
                // 300.0.0.0 would reach the JDK as a hostname; 010.0.0.0 is octal to some parsers. Both are refused.
                if (Integer.parseInt(octet) > 255 || (octet.length() > 1 && octet.startsWith("0"))) {
                    throw new IllegalArgumentException(property + " has an IPv4 octet above 255 or with a leading zero, was ["
                            + entry + "]");
                }
            }
        }
        boolean v6 = !v4 && parts[0].contains(":") && IPV6_LITERAL.matcher(parts[0]).matches();
        if (!v4 && !v6) {
            throw new IllegalArgumentException(property + " must start with an IPv4 or IPv6 literal, was [" + entry + "]");
        }
        InetAddress base;
        try {
            base = InetAddress.getByName(parts[0]);      // a literal: no lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(property + " is not a valid address literal, was [" + entry + "]");
        }
        int prefix = Integer.parseInt(parts[1]);
        int bits = base.getAddress().length * 8;
        if (v4 != (base instanceof Inet4Address) || prefix > bits) {
            throw new IllegalArgumentException(property + " has a prefix length outside 0.." + bits + ", was [" + entry + "]");
        }
        return new Network(base.getAddress(), prefix);
    }

    /** One CIDR block: an address family, a base address and a prefix length. */
    record Network(byte[] base, int prefix) {

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != base.length) {
                return false;                             // a different family never matches
            }
            int whole = prefix / 8;
            for (int i = 0; i < whole; i++) {
                if (candidate[i] != base[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (candidate[whole] & mask) == (base[whole] & mask);
        }
    }
}

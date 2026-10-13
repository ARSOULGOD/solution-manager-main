package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Plan 013 T1.2 (D-REG section 7.3): the strict policy, as a table, with a stub resolver for the names. */
class StrictTargetAddressPolicyTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);              // a literal: never a DNS lookup
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    private static StrictTargetAddressPolicy policy(List<String> allowed, Map<String, List<InetAddress>> names) {
        Map<String, List<InetAddress>> table = new HashMap<>(names);
        return new StrictTargetAddressPolicy(new TargetAddressProperties(allowed), host -> {
            List<InetAddress> found = table.get(host);
            if (found == null) {
                return InetAddress.getAllByName(host);          // literals resolve to themselves
            }
            return found.toArray(InetAddress[]::new);
        });
    }

    private static StrictTargetAddressPolicy strict() {
        return policy(List.of(), Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.9.10", "::1", "169.254.169.254", "169.254.0.1", "fe80::1",
            "0.0.0.0", "::", "224.0.0.1", "239.255.255.255", "ff02::1", "::ffff:127.0.0.1", "::127.0.0.1"})
    void everyLoopbackLinkLocalUnspecifiedAndMulticastLiteralIsRefused(String literal) {
        assertThrows(TargetAddressRefusedException.class, () -> strict().check(literal), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.3", "172.16.0.9", "192.168.1.20", "203.0.113.9", "8.8.8.8", "2001:db8::1",
            "fd00::5"})
    void anOrdinaryRoutableAddressIsAccepted(String literal) throws Exception {
        List<InetAddress> checked = strict().check(literal);

        assertEquals(List.of(ip(literal)), checked);
    }

    @Test
    void aNameThatResolvesToLoopbackIsRefused() {
        StrictTargetAddressPolicy policy = policy(List.of(), Map.of("looks-fine.example", List.of(ip("127.0.0.1"))));

        assertThrows(TargetAddressRefusedException.class, () -> policy.check("looks-fine.example"));
    }

    @Test
    void aNameWithOneBadAddressAmongGoodOnesIsRefused() {
        StrictTargetAddressPolicy policy = policy(List.of(),
                Map.of("two-faced.example", List.of(ip("10.0.0.5"), ip("169.254.169.254"))));

        assertThrows(TargetAddressRefusedException.class, () -> policy.check("two-faced.example"));
    }

    @Test
    void aNameThatDoesNotResolveIsAnUnknownHostNotAPolicyRefusal() {
        StrictTargetAddressPolicy policy = new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted(),
                host -> {
                    throw new UnknownHostException(host);
                });

        assertThrows(UnknownHostException.class, () -> policy.check("nowhere.example"));
    }

    @Test
    void anEmptyResolutionIsAnUnknownHost() {
        StrictTargetAddressPolicy policy = new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted(),
                host -> new InetAddress[0]);

        assertThrows(UnknownHostException.class, () -> policy.check("empty.example"));
    }

    @Test
    void theRefusalNamesNeitherTheHostNorTheAddress() {
        TargetAddressRefusedException refused = assertThrows(TargetAddressRefusedException.class,
                () -> strict().check("169.254.169.254"));

        assertFalse(refused.getMessage().contains("169.254"), refused.getMessage());
        assertEquals(new TargetAddressRefusedException().getMessage(), refused.getMessage());
    }

    @Test
    void anAddressOutsideTheAllowListIsRefusedAndOneInsideIsAccepted() throws Exception {
        StrictTargetAddressPolicy policy = policy(List.of("10.0.0.0/8", "fd00::/8"), Map.of());

        assertEquals(List.of(ip("10.9.8.7")), policy.check("10.9.8.7"));
        assertEquals(List.of(ip("fd00::5")), policy.check("fd00::5"));
        assertThrows(TargetAddressRefusedException.class, () -> policy.check("192.168.1.20"));
        assertThrows(TargetAddressRefusedException.class, () -> policy.check("11.0.0.1"));
        assertThrows(TargetAddressRefusedException.class, () -> policy.check("fe00::1"));
    }

    @Test
    void theAllowListNeverLiftsABuiltInRefusal() {
        StrictTargetAddressPolicy policy = policy(List.of("0.0.0.0/0", "::/0"), Map.of());

        assertThrows(TargetAddressRefusedException.class, () -> policy.check("127.0.0.1"));
        assertThrows(TargetAddressRefusedException.class, () -> policy.check("169.254.169.254"));
        assertThrows(TargetAddressRefusedException.class, () -> policy.check("::1"));
    }

    @Test
    void everyAddressOfANameMustBeInsideTheAllowList() {
        StrictTargetAddressPolicy policy = policy(List.of("10.0.0.0/8"),
                Map.of("split.example", List.of(ip("10.0.0.5"), ip("192.168.0.5"))));

        assertThrows(TargetAddressRefusedException.class, () -> policy.check("split.example"));
    }

    @Test
    void aPrefixThatIsNotOnAByteBoundaryIsHonoured() throws Exception {
        StrictTargetAddressPolicy policy = policy(List.of("10.20.0.0/14"), Map.of());

        assertTrue(policy.check("10.23.255.254").size() == 1);
        assertThrows(TargetAddressRefusedException.class, () -> policy.check("10.24.0.1"));
        assertThrows(TargetAddressRefusedException.class, () -> policy.check("10.19.255.255"));
    }

    @Test
    void aSlashThirtyTwoAllowsExactlyOneHost() throws Exception {
        StrictTargetAddressPolicy policy = policy(List.of("203.0.113.9/32"), Map.of());

        assertEquals(1, policy.check("203.0.113.9").size());
        assertThrows(TargetAddressRefusedException.class, () -> policy.check("203.0.113.10"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "10.0.0.0", "10.0.0.0/", "10.0.0.0/33", "10.0.0.0/-1", "::/129", "example.com/8",
            "10.0.0/8", "10.0.0.0/eight", "10.0.0.0/8/8", "not-a-cidr", "[::1]/8"})
    void anAllowListEntryThatIsNotACidrBlockFailsConstruction(String entry) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new TargetAddressProperties(List.of(entry)), "[" + entry + "]");

        assertTrue(refused.getMessage().contains("sm.dp.targets.allowed-networks"), refused.getMessage());
    }

    @Test
    void connectableCarriesTheRegisteredNameAndTheCheckedAddress() throws Exception {
        StrictTargetAddressPolicy policy = policy(List.of(), Map.of("stored.example", List.of(ip("10.0.0.5"))));

        InetAddress connectable = policy.connectable("stored.example");

        assertEquals("stored.example", connectable.getHostName());
        assertEquals("10.0.0.5", connectable.getHostAddress());
    }

    @Test
    void anAbsentAllowListMeansNoRestrictionBeyondTheBuiltInOnes() {
        assertEquals(List.of(), new TargetAddressProperties(null).allowedNetworks());
    }

    /** An {@code Inet6Address} that really holds an IPv4-mapped address, as a resolver may return for an AAAA record. */
    private static InetAddress mapped(String v4) throws Exception {
        byte[] v4Bytes = InetAddress.getByName(v4).getAddress();
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        System.arraycopy(v4Bytes, 0, bytes, 12, 4);
        InetAddress built = java.net.Inet6Address.getByAddress("mapped.example", bytes, -1);
        assertTrue(built instanceof java.net.Inet6Address, "the fixture must be an Inet6Address");
        return built;
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "169.254.169.254", "0.0.0.0", "224.0.0.1"})
    void aMappedInet6AddressHoldingAnUnconnectableIpv4IsRefusedByCheckAndConnectable(String v4) throws Exception {
        StrictTargetAddressPolicy policy = policy(List.of(), Map.of("mapped.example", List.of(mapped(v4))));

        assertThrows(TargetAddressRefusedException.class, () -> policy.check("mapped.example"), v4);
        assertThrows(TargetAddressRefusedException.class, () -> policy.connectable("mapped.example"), v4);
    }

    @Test
    void aMappedInet6AddressHoldingAnOrdinaryIpv4IsConnectedAsExactlyTheAddressJudged() throws Exception {
        StrictTargetAddressPolicy policy = policy(List.of("10.0.0.0/8"), Map.of("mapped.example", List.of(mapped("10.1.2.3"))));

        InetAddress connectable = policy.connectable("mapped.example");

        assertEquals(policy.check("mapped.example").get(0), connectable);
        assertEquals("10.1.2.3", connectable.getHostAddress());
        assertEquals("mapped.example", connectable.getHostName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "2002:7f00:1::1", "2002:a9fe:a9fe::1", "0.1.2.3",
            "255.255.255.255", "fec0::1"})
    void tunnelledReservedAndSiteLocalAddressesAreRefused(String literal) {
        assertThrows(TargetAddressRefusedException.class, () -> strict().check(literal), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"64:ff9b::808:808", "2002:808:808::1", "fd00::5", "fc00::1"})
    void tunnelledOrdinaryAddressesAndUniqueLocalOnesStayAccepted(String literal) throws Exception {
        assertEquals(1, strict().check(literal).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"300.0.0.0/8", "10.256.0.0/8", "010.0.0.0/8", "10.0.0.00/8"})
    void anAllowListEntryWithAnImpossibleOrLeadingZeroOctetIsRefusedBeforeTheJdkSeesIt(String entry) {
        assertThrows(IllegalArgumentException.class,
                () -> new StrictTargetAddressPolicy(new TargetAddressProperties(List.of(entry))), entry);
    }
}

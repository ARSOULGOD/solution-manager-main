package com.solace.sm.cp.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientAddressesTest {

    @Test
    void everySpellingOfOneIpv6AddressCanonicalisesToTheSameString() {
        // The container reports 0:0:0:0:0:0:0:1; operators and proxies write ::1. One address, one key.
        String expected = ClientAddresses.canonicalize("0:0:0:0:0:0:0:1").orElseThrow();
        assertThat(ClientAddresses.canonicalize("::1")).contains(expected);
        assertThat(ClientAddresses.canonicalize("[::1]")).contains(expected);
        assertThat(ClientAddresses.canonicalize("::1%lo0")).contains(expected);
        assertThat(ClientAddresses.canonicalize("2001:DB8::1"))
                .isEqualTo(ClientAddresses.canonicalize("2001:db8:0:0:0:0:0:1"));
    }

    @Test
    void anIpv4MappedIpv6AddressCanonicalisesToItsIpv4Form() {
        assertThat(ClientAddresses.canonicalize("::ffff:203.0.113.9")).contains("203.0.113.9");
    }

    @Test
    void ipv4IsAcceptedAsIsAndLeadingZerosAreRefused() {
        assertThat(ClientAddresses.canonicalize("203.0.113.9")).contains("203.0.113.9");
        assertThat(ClientAddresses.canonicalize(" 203.0.113.9 ")).contains("203.0.113.9");
        assertThat(ClientAddresses.canonicalize("010.0.0.1")).isEmpty();
        assertThat(ClientAddresses.canonicalize("256.0.0.1")).isEmpty();
    }

    @Test
    void anythingThatIsNotALiteralIsRefusedAndNeverResolved() {
        for (String notALiteral : new String[] {"localhost", "example.com", "", "not-an-address",
                "203.0.113.9; DROP", "x".repeat(200), "1.2.3", null,
                // A leading "." passes the character class but the JDK does NOT parse it as a
                // literal (it only does so when the first char is a hex digit or ":"), so it
                // falls through to the system resolver — measured. It must be refused first.
                ".:1", ".::1", "..:"}) {
            assertThat(ClientAddresses.canonicalize(notALiteral)).as(String.valueOf(notALiteral)).isEmpty();
        }
    }

    @Test
    void theGateBeforeGetByNameRejectsEveryShapeTheJdkWouldHandToTheResolver() {
        // canonicalize() returning empty cannot prove no lookup happened — getByName(".:1") does the
        // lookup AND then throws, so an "is it empty?" assertion passes with or without the gate. This
        // tests the gate itself: anything it admits is parsed as a literal by the JDK, never resolved.
        for (String reachesTheResolver : new String[] {".:1", ".::1", "..:", "g::1", "-::1", ""}) {
            assertThat(ClientAddresses.isParsedAsIpv6Literal(reachesTheResolver)).as(reachesTheResolver).isFalse();
        }
        for (String literal : new String[] {"::1", "0:0:0:0:0:0:0:1", "2001:db8::1", "::ffff:203.0.113.9", "fe80::1"}) {
            assertThat(ClientAddresses.isParsedAsIpv6Literal(literal)).as(literal).isTrue();
        }
    }

    @Test
    void requestOriginCarriesTheCanonicalSpellingAndRefusesNonLiterals() {
        assertThat(new RequestOrigin("::1", UUID.randomUUID()).clientAddress())
                .isEqualTo(ClientAddresses.canonicalize("0:0:0:0:0:0:0:1").orElseThrow());
        assertThatThrownBy(() -> new RequestOrigin("localhost", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throttleKeyIsTheFullIpv4AddressAndTheIpv6Slash64() {
        assertThat(ClientAddresses.throttleKey("203.0.113.9")).isEqualTo("203.0.113.9");
        String a = ClientAddresses.canonicalize("2001:db8:1:2::1").orElseThrow();
        String b = ClientAddresses.canonicalize("2001:db8:1:2:ffff:ffff:ffff:ffff").orElseThrow();
        String other = ClientAddresses.canonicalize("2001:db8:1:3::1").orElseThrow();
        assertThat(ClientAddresses.throttleKey(a)).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientAddresses.throttleKey(b)).isEqualTo(ClientAddresses.throttleKey(a));
        assertThat(ClientAddresses.throttleKey(other)).isNotEqualTo(ClientAddresses.throttleKey(a));
        // An IPv4-mapped address is the IPv4 client, not an IPv6 /64.
        assertThat(ClientAddresses.throttleKey(ClientAddresses.canonicalize("::ffff:203.0.113.9").orElseThrow()))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void throttleKeyRefusesANonCanonicalSpelling() {
        assertThatThrownBy(() -> ClientAddresses.throttleKey("::1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientAddresses.throttleKey("example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientAddresses.throttleKey(null)).isInstanceOf(IllegalArgumentException.class);
    }
}

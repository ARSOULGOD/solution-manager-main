package com.solace.sm.dp.metrics.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.metrics.push.BrokerSampleDecoder.Body;
import com.solace.sm.dp.metrics.push.BrokerSampleDecoder.NumberRead;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-5.1, the I6 filter before write (M-Q12): the decoder parses a broker event, applies the declared filter, reads
 * ONLY the declared pointers and discards the document. What comes out is a key string, a yes or no, and a finite number;
 * nothing else of the payload leaves it, in a result, a {@code toString} or a failure.
 */
class BrokerSampleDecoderTest {

    private static final String SECRET = "SENTINEL-broker-password-7731";
    private final BrokerSampleDecoder decoder = new BrokerSampleDecoder(64 * 1024);

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private Body parse(String json) {
        return decoder.parse(bytes(json)).orElseThrow();
    }

    @Test
    void theKeyIsTheTextAtTheDeclaredPointerAndOnlyThat() {
        Body body = decoder.parse(PushFixtures.heartbeat("SOURCE_DEFAULT_Alice_FF_NWFILE_V1")).orElseThrow();

        assertEquals(Optional.of("SOURCE_DEFAULT_Alice_FF_NWFILE_V1"), body.text("/header/adapterID"));
        assertEquals(Optional.empty(), body.text("/header/nothing"));
        assertEquals(Optional.empty(), body.text("/header"), "an object is not a key");
        assertEquals(Optional.empty(), body.text("/header/eventType"), "a number is not read as a key: ids are text here");
    }

    @Test
    void onlyTheDeclaredPointerIsRead() {
        // a payload carrying a password next to what is declared: nothing of it can come out of the decoder's API
        Body body = parse("{\"header\":{\"adapterID\":\"A\"},\"password\":\"" + SECRET + "\",\"fileStateEvent\":{\"runTimeMs\":5}}");

        assertEquals(Optional.of("A"), body.text("/header/adapterID"));
        assertEquals(new NumberRead.Finite(5.0), body.number("/fileStateEvent/runTimeMs"));
        assertEquals(Optional.empty(), body.text("/fileStateEvent"), "a subtree is never handed back: only a scalar at a declared pointer");
        assertFalse(body.toString().contains(SECRET), body.toString());
        assertFalse(body.toString().contains("password"), body.toString());
    }

    @Test
    void aPayloadSecretNeverLeavesTheDecoderThroughAResultAFailureOrAToString() {
        String start = "{\"header\":{\"adapterID\":\"A\"},\"solace\":{\"password\":\"" + SECRET + "\"}}";

        Body body = parse(start);
        NumberRead notANumber = body.number("/solace/password");
        Optional<Body> unparsable = decoder.parse(bytes(start.substring(0, start.length() - 4)));

        assertEquals(NumberRead.NOT_A_FINITE_NUMBER, notANumber);
        assertFalse(notANumber.toString().contains(SECRET), notANumber.toString());
        assertTrue(unparsable.isEmpty(), "a truncated document is refused");
        assertFalse(String.valueOf(unparsable).contains(SECRET));
        assertFalse(body.toString().contains(SECRET));
    }

    @Test
    void aNonNumberAtThePointerIsNotAFiniteNumberAndCarriesNoValue() {
        for (String value : new String[] {"\"12\"", "true", "null", "{\"a\":1}", "[1]", "\"" + SECRET + "\""}) {
            NumberRead read = parse("{\"v\":" + value + "}").number("/v");
            assertEquals(NumberRead.NOT_A_FINITE_NUMBER, read, value);
            assertFalse(read.toString().contains(SECRET));
        }
        assertEquals(NumberRead.NOT_A_FINITE_NUMBER, parse("{\"v\":1}").number("/missing"));
    }

    @Test
    void aNonFiniteNumberIsNotAFiniteNumberNeverPassedOnToBeMisreadAsARunwayFailure() {
        // 1e400 is a valid JSON number that Jackson reads as Infinity as a double
        assertEquals(NumberRead.NOT_A_FINITE_NUMBER, parse("{\"v\":1e400}").number("/v"));
        assertEquals(NumberRead.NOT_A_FINITE_NUMBER, parse("{\"v\":-1e400}").number("/v"));
        assertEquals(new NumberRead.Finite(0.0), parse("{\"v\":0}").number("/v"));
        assertEquals(new NumberRead.Finite(-2.5), parse("{\"v\":-2.5}").number("/v"));
        // NaN and Infinity as bare tokens are not JSON: the document does not parse at all
        assertTrue(decoder.parse(bytes("{\"v\":NaN}")).isEmpty());
        assertTrue(decoder.parse(bytes("{\"v\":Infinity}")).isEmpty());
    }

    @Test
    void theFilterComparesTheScalarAtItsPointerAsTextAndNothingElse() {
        Body body = parse("{\"header\":{\"eventType\":6,\"adapterType\":\"SOURCE\"},\"o\":{\"x\":1}}");

        assertTrue(body.matches(new PushMetric.EventFilter("/header/eventType", "6")), "a number compares as written");
        assertTrue(body.matches(new PushMetric.EventFilter("/header/adapterType", "SOURCE")));
        assertFalse(body.matches(new PushMetric.EventFilter("/header/adapterType", "source")), "exact, case-sensitive");
        assertFalse(body.matches(new PushMetric.EventFilter("/header/missing", "x")));
        assertFalse(body.matches(new PushMetric.EventFilter("/o", "x")), "an object never equals a string");
    }

    @Test
    void aBodyThatIsNotAJsonDocumentOrIsTooLargeIsRefusedWithoutBeingRead() {
        assertTrue(decoder.parse(bytes("not json")).isEmpty());
        assertTrue(decoder.parse(new byte[0]).isEmpty());
        assertTrue(decoder.parse(bytes("{\"a\":1} trailing")).isEmpty(), "trailing content is not a document");
        assertTrue(new BrokerSampleDecoder(10).parse(bytes("{\"a\":\"12345678\"}")).isEmpty(), "over the size bound");
        assertTrue(decoder.parse(bytes("[1,2]")).isEmpty() || decoder.parse(bytes("[1,2]")).get().text("/header/adapterID").isEmpty());
    }

    @Test
    void aPointerThatLeavesTheGrammarReadsNothing() {
        Body body = parse("{\"a\":{\"b\":\"x\"}}");

        assertEquals(Optional.empty(), body.text("/a/../a/b"));
        assertEquals(Optional.empty(), body.text("a/b"), "a pointer starts with a slash");
        assertEquals(Optional.empty(), body.text(""), "the whole document is never a key");
        assertEquals(Optional.empty(), body.text("/a/b~0"), "no ~ escape");
        assertEquals(NumberRead.NOT_A_FINITE_NUMBER, body.number(""));
    }
}

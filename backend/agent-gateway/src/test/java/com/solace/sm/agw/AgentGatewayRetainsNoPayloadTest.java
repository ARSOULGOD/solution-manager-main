package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.solace.sm.dp.api.agw.CommandOffer;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Structural, not GC (plan 015 P15-17, AGW-D26): the gateway holds a command's rendered bytes for one in-memory hop and nothing keeps
 * them. This walks the declared instance fields of {@link AgentGatewayService} and every type reachable from them inside
 * {@code com.solace.sm.agw} and fails on a {@code byte[]}, a {@link ByteString}, a {@link CommandOffer}, a generated {@code *Offer}
 * message, or any collection, map, array or wildcard parameterized by one. A planted fixture proves the walk reports.
 */
class AgentGatewayRetainsNoPayloadTest {

    @Test
    void theGatewayServiceAndEverythingItReachesRetainsNoCommandPayload() {
        assertEquals(List.of(), offenders(AgentGatewayService.class));
    }

    @Test
    void theWalkReportsAPlantedByteStringField() {
        List<String> found = offenders(PlantedHoldsAByteString.class);
        assertTrue(found.stream().anyMatch(x -> x.contains("held") && x.contains("ByteString")), found.toString());
    }

    @Test
    void theWalkReportsPlantedCollectionsOfOffersAndRawBytesAndFindsThemThroughAReachableClass() {
        List<String> found = offenders(PlantedReachesAHolder.class);
        assertTrue(found.stream().anyMatch(x -> x.contains("offers") && x.contains("CommandOffer")), found.toString());
        assertTrue(found.stream().anyMatch(x -> x.contains("generated") && x.contains("CommandOffer")), found.toString());
        assertTrue(found.stream().anyMatch(x -> x.contains("raw") && x.contains("byte[]")), found.toString());
        assertFalse(found.stream().anyMatch(x -> x.contains("harmless")), found.toString());
    }

    // ---- planted fixtures (test sources only) ----
    static final class PlantedHoldsAByteString {
        private ByteString held;
    }

    static final class PlantedHolder {
        private Map<String, List<CommandOffer>> offers;
        private List<? extends solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.CommandOffer> generated;
        private byte[] raw;
        private String harmless;
    }

    static final class PlantedHoldsEnvelopesAndBuilders {
        private solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.GatewayToAgent envelope;
        private solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.CommandOffer.Builder builder;
        private solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.CommandOfferOrBuilder view;
        private Object anything;
        private com.google.protobuf.Message message;
        private com.google.protobuf.MessageOrBuilder viewOfMessage;
    }

    @Test
    void theWalkReportsPlantedEnvelopeBuilderAndGenericHolders() {
        List<String> found = offenders(PlantedHoldsEnvelopesAndBuilders.class);
        for (String field : List.of("envelope", "builder", "view", "anything", "message", "viewOfMessage")) {
            assertTrue(found.stream().anyMatch(x -> x.contains("." + field + " ")), field + " in " + found);
        }
    }

    static final class PlantedReachesAHolder {
        private Map<String, PlantedHolder> viaMap;
    }

    // ---- the walk ----
    static List<String> offenders(Class<?> root) {
        List<String> out = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        walkClass(root, seen, out);
        return out;
    }

    private static void walkClass(Class<?> type, Set<Class<?>> seen, List<String> out) {
        if (!seen.add(type)) return;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() && field.getName().startsWith("$")) continue;
            walkType(field.getGenericType(), type.getSimpleName() + "." + field.getName(), seen, out);
        }
    }

    private static void walkType(Type type, String where, Set<Class<?>> seen, List<String> out) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                if (c == byte[].class) out.add(where + " : byte[]");
                walkType(c.getComponentType(), where, seen, out);
            } else if (forbidden(c)) {
                out.add(where + " : " + c.getSimpleName());
            } else if (c.getName().startsWith("com.solace.sm.agw.")) {
                walkClass(c, seen, out);
            }
        } else if (type instanceof ParameterizedType p) {
            walkType(p.getRawType(), where, seen, out);
            // A StreamObserver<GatewayToAgent> is a SINK for outbound messages, not a holder of them.
            if (p.getRawType() == io.grpc.stub.StreamObserver.class) return;
            for (Type argument : p.getActualTypeArguments()) walkType(argument, where, seen, out);
        } else if (type instanceof GenericArrayType g) {
            walkType(g.getGenericComponentType(), where, seen, out);
        } else if (type instanceof WildcardType w) {
            // "?" / "? extends Object" is not a field of type Object; only a real bound is followed.
            for (Type bound : w.getUpperBounds()) if (bound != Object.class) walkType(bound, where, seen, out);
            for (Type bound : w.getLowerBounds()) walkType(bound, where, seen, out);
        } else if (type instanceof TypeVariable<?> v) {
            for (Type bound : v.getBounds()) walkType(bound, where, seen, out);
        }
    }

    /** Anything that can hold an offer: the offer types and their builders, the gateway-to-agent envelope, and the generic holders. */
    private static boolean forbidden(Class<?> c) {
        if (c == ByteString.class || c == CommandOffer.class || c == Object.class || c == com.google.protobuf.Message.class
                || c == com.google.protobuf.MessageOrBuilder.class || c == com.google.protobuf.MessageLite.class
                || c == com.google.protobuf.MessageLiteOrBuilder.class) {
            return true;
        }
        String name = c.getName();
        return (name.startsWith("solace.sm.agentgateway.") && (name.contains("Offer") || name.contains("GatewayToAgent")))
                || (name.startsWith("com.solace.sm.dp.api.agw.") && name.contains("Offer"));
    }
}

package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import solace.sm.agentgateway.v1draft.AgentGatewayGrpc;

/** Plan 015 T3.2 / T2.2 review note (b): the interceptor hands the service the bare IP literal of the peer, never a toString form. */
class AgentGatewayInterceptorTest {

    @Test
    void anIpv4PeerIsItsBareLiteralWithoutThePort() throws Exception {
        assertEquals("203.0.113.9", AgentGatewayInterceptor.peerAddress(
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 9}), 55000)));
    }

    @Test
    void anIpv6PeerIsItsLiteralWithoutThePortOrTheZone() throws Exception {
        InetAddress v6 = java.net.Inet6Address.getByAddress(null, new byte[] {(byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}, 3);
        String address = AgentGatewayInterceptor.peerAddress(new InetSocketAddress(v6, 55000));
        assertEquals("fe80:0:0:0:0:0:0:1", address);
    }

    @Test
    void aPeerThatIsNotAnInetSocketAddressOrIsUnresolvedIsNull() {
        assertNull(AgentGatewayInterceptor.peerAddress(null));
        assertNull(AgentGatewayInterceptor.peerAddress(InetSocketAddress.createUnresolved("agent.example.test", 1)));
        assertNull(AgentGatewayInterceptor.peerAddress(new SocketAddress() { @Override public String toString() { return "/1.2.3.4:5"; } }));
    }

    @Test
    void theContextCarriesThePeerLiteralNotSocketAddressToString() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        ServerCallHandler<Object, Object> next = (call, headers) -> {
            seen.set(AgentGatewayService.MetadataContext.CLIENT_ADDRESS.get());
            return new ServerCall.Listener<>() { };
        };
        Attributes attributes = Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR,
                new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 1, 2, 3}), 4242)).build();
        new AgentGatewayInterceptor().interceptCall(new StubCall(attributes), new Metadata(), next);
        assertEquals("10.1.2.3", seen.get());
    }

    private static final class StubCall extends ServerCall<Object, Object> {
        private final Attributes attributes;
        StubCall(Attributes attributes) { this.attributes = attributes; }
        @Override public void request(int numMessages) { }
        @Override public void sendHeaders(Metadata headers) { }
        @Override public void sendMessage(Object message) { }
        @Override public void close(Status status, Metadata trailers) { }
        @Override public boolean isCancelled() { return false; }
        @Override public Attributes getAttributes() { return attributes; }
        @SuppressWarnings("unchecked")
        @Override public MethodDescriptor<Object, Object> getMethodDescriptor() {
            return (MethodDescriptor<Object, Object>) (MethodDescriptor<?, ?>) AgentGatewayGrpc.getConnectMethod();
        }
    }
}

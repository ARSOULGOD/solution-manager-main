package com.solace.sm.agw;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Grpc;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import javax.net.ssl.SSLSession;

/** Carries only the one enrollment metadata value into the service call context. */
final class AgentGatewayInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> TOKEN = Metadata.Key.of("x-sm-enrollment-token", Metadata.ASCII_STRING_MARSHALLER);
    @Override
    public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String token = headers.get(TOKEN);
        String thumbprint = thumbprint(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION));
        String address = peerAddress(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
        Context context = Context.current().withValue(AgentGatewayService.MetadataContext.TOKEN, token)
                .withValue(AgentGatewayService.MetadataContext.CLIENT_ADDRESS, address)
                .withValue(AgentGatewayService.MetadataContext.THUMBPRINT, thumbprint);
        return Contexts.interceptCall(context, call, headers, next);
    }

    /**
     * The bare IP literal of the transport peer ({@code Grpc.TRANSPORT_ATTR_REMOTE_ADDR}, an {@code InetSocketAddress} on the Netty
     * transport; the attribute exists at grpc-java 1.68.1, I2): the port is never part of it, and neither is an IPv6 zone. Never
     * {@code SocketAddress.toString()}, whose form ({@code /1.2.3.4:5678}, or a hostname with it) is not an address. {@code null}
     * when the transport offers none.
     */
    static String peerAddress(java.net.SocketAddress remote) {
        if (!(remote instanceof java.net.InetSocketAddress inet) || inet.getAddress() == null) return null;
        String literal = inet.getAddress().getHostAddress();
        int zone = literal.indexOf('%');
        return zone < 0 ? literal : literal.substring(0, zone);
    }

    private static String thumbprint(SSLSession session) {
        if (session == null) return null;
        try {
            Certificate[] peer = session.getPeerCertificates();
            if (peer.length == 0 || !(peer[0] instanceof X509Certificate x509)) return null;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x509.getEncoded()));
        } catch (Exception e) { return null; }
    }
}

package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@code getConnectionTypes} (plan 013 T4, {@code UI-D27}): the connection type catalog, served verbatim from
 * {@value #BROKER_RESOURCE} and {@value #REMOTE_SERVER_RESOURCE} — byte-identical copies of
 * {@code contracts/connection-types/*.json} ({@code ConnectionTypeDescriptorMirrorTest} pins them to the canonical
 * contract, so a drifted copy fails the build rather than quietly serving yesterday's descriptor). SM-owned, never
 * package-supplied: there is no load path and no per-organization variant.
 *
 * <p>Also the one place {@link ConnectionAttributeCodec} (and the test-probe binding builders) look up a
 * {@code connectionType}'s descriptor, so the catalog central to "what attributes exist and which are secret" has
 * one home.
 */
@Component
public final class ConnectionTypeCatalog {

    static final String BROKER_RESOURCE = "/contracts/connection-types/solace-broker.json";
    static final String REMOTE_SERVER_RESOURCE = "/contracts/connection-types/remote-server.json";

    private final List<ConnectionType> descriptors;
    private final Map<String, ConnectionType> byType;

    public ConnectionTypeCatalog() {
        this(load(BROKER_RESOURCE), load(REMOTE_SERVER_RESOURCE));
    }

    ConnectionTypeCatalog(ConnectionType... descriptors) {
        this.descriptors = List.of(descriptors);
        this.byType = this.descriptors.stream()
                .collect(Collectors.toUnmodifiableMap(ConnectionType::connectionType, d -> d));
    }

    /** Every descriptor, in a fixed order (broker, then remote server) — what {@code getConnectionTypes} answers. */
    public List<ConnectionType> all() {
        return descriptors;
    }

    public Optional<ConnectionType> byConnectionType(String connectionType) {
        return Optional.ofNullable(byType.get(connectionType));
    }

    private static ConnectionType load(String resource) {
        try (InputStream in = ConnectionTypeCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return new ObjectMapper().readValue(in, ConnectionType.class);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to read " + resource, e);
        }
    }
}

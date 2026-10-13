package com.solace.sm.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Plan 013 T4 fix round 1, item 2 ({@code UI-D27}): the mirror-and-lock {@link ConnectionTypeCatalog}'s own
 * javadoc already claimed but did not have — the shape {@code PackageSchemaMirrorLockTest} uses for
 * {@code contracts/solution-package.schema.json}. Equality, not a presence check: a served copy that is merely
 * close serves a connection type catalog the contract no longer holds, and {@code getConnectionTypes} would lie
 * about it being "mirrored verbatim".
 */
class ConnectionTypeDescriptorMirrorTest {

    @Test
    void theClasspathSolaceBrokerDescriptorIsByteIdenticalToTheContract() throws IOException {
        assertByteIdentical("solace-broker.json", ConnectionTypeCatalog.BROKER_RESOURCE);
    }

    @Test
    void theClasspathRemoteServerDescriptorIsByteIdenticalToTheContract() throws IOException {
        assertByteIdentical("remote-server.json", ConnectionTypeCatalog.REMOTE_SERVER_RESOURCE);
    }

    @Test
    void theCatalogServesBothDescriptorsAndNothingElse() {
        ConnectionTypeCatalog catalog = new ConnectionTypeCatalog();

        assertThat(catalog.all()).extracting(ConnectionType::connectionType)
                .containsExactly("solace-broker", "remote-server");
    }

    private static void assertByteIdentical(String contractFileName, String classpathResource) throws IOException {
        byte[] contract = Files.readAllBytes(contracts().resolve("connection-types/" + contractFileName));
        byte[] mirrored;
        try (InputStream in = ConnectionTypeCatalog.class.getResourceAsStream(classpathResource)) {
            assertThat(in).as(classpathResource + " is on the production classpath").isNotNull();
            mirrored = in.readAllBytes();
        }

        assertThat(mirrored)
                .as("update the mirror with: cp contracts/connection-types/" + contractFileName
                        + " backend/console-gateway/src/main/resources/contracts/connection-types/")
                .isEqualTo(contract);
    }

    /** Walks up from the module root to find the monorepo's {@code contracts/} directory, read-only. */
    private static Path contracts() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("contracts/connection-types");
            if (Files.isDirectory(candidate)) {
                return dir.resolve("contracts");
            }
            dir = dir.getParent();
        }
        throw new UncheckedIOException(
                new IOException("could not locate contracts/connection-types from " + Path.of("").toAbsolutePath()));
    }
}

package com.solace.sm.cp.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * {@code BE-D10}'s mirror-and-lock for the one contract file production code reads at runtime: the copy on the
 * classpath is byte-identical to {@code contracts/solution-package.schema.json}. Equality, not a presence check -
 * a copy that is merely close validates packages against a vocabulary the contract no longer holds.
 */
class PackageSchemaMirrorLockTest {

    @Test
    void theClasspathSchemaIsByteIdenticalToTheContract() throws IOException {
        byte[] contract = Files.readAllBytes(CatalogFixtures.contracts().resolve("solution-package.schema.json"));
        byte[] mirrored;
        try (InputStream in = CatalogConfiguration.class.getResourceAsStream(CatalogConfiguration.SCHEMA_RESOURCE)) {
            assertThat(in).as("the schema is on the production classpath").isNotNull();
            mirrored = in.readAllBytes();
        }

        assertThat(mirrored).as("update the mirror with: cp contracts/solution-package.schema.json "
                + "backend/cp-core/src/main/resources/contracts/").isEqualTo(contract);
    }

    @Test
    void theProductionValidatorBuiltFromItRefusesAnEmptyDocument() {
        assertThat(new CatalogConfiguration().packageValidator().validateJson("{}", false).ok()).isFalse();
    }
}

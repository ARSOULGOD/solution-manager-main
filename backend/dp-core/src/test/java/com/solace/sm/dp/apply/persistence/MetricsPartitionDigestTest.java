package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan 011 P11-2.1: {@code drop} hashes an archive as a stream, so its size is not bounded by the heap. */
class MetricsPartitionDigestTest {

    @TempDir
    Path dir;

    @Test
    void aFarLargerThanOneBufferFileHashesToTheSameDigestAsTheWholeFile() throws Exception {
        byte[] bytes = new byte[3 * 65536 + 17];          // several reads, and a short last one
        new Random(42).nextBytes(bytes);
        Path file = dir.resolve("big.csv.gz");
        Files.write(file, bytes);

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(expected, MetricsPartitionMain.sha256Of(file));
    }

    @Test
    void anEmptyFileHashesToTheEmptyDigest() throws IOException, java.security.NoSuchAlgorithmException {
        Path file = Files.createFile(dir.resolve("empty"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0])),
                MetricsPartitionMain.sha256Of(file));
    }
}

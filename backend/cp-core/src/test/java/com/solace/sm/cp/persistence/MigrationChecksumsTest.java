package com.solace.sm.cp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code OR-22}: {@code sm_control} migrations are frozen. Fails the build if any pinned migration
 * file's bytes change, any pinned file disappears, or a migration file exists on disk that {@code
 * CHECKSUMS.sha256} does not pin. See {@code db/migration/sm_control/README.md} for the ruling.
 */
class MigrationChecksumsTest {

    private static final String RESOURCE_DIR = "db/migration/sm_control";
    private static final String CHECKSUMS_FILE = "CHECKSUMS.sha256";
    private static final Pattern CHECKSUM_LINE = Pattern.compile("([0-9a-f]{64}) {2}(\\S+)");
    private static final Pattern MIGRATION_FILE = Pattern.compile("V\\d+__.*\\.sql");

    @Test
    void everyPinnedMigrationFileMatchesItsRecordedChecksum() throws IOException, NoSuchAlgorithmException,
            URISyntaxException {
        File directory = migrationDirectory();
        Map<String, String> pinned = readPinned(directory);
        assertFalse(pinned.isEmpty(), CHECKSUMS_FILE + " pinned nothing — is it on the classpath?");

        for (Map.Entry<String, String> entry : pinned.entrySet()) {
            File file = new File(directory, entry.getKey());
            assertTrue(file.isFile(), "a pinned migration file is missing: " + entry.getKey());
            assertEquals(entry.getValue(), sha256(file),
                    entry.getKey() + " has changed since it was pinned — OR-22 forbids editing an "
                            + "applied migration; add a new migration file instead");
        }
    }

    @Test
    void everyMigrationFileOnDiskIsPinned() throws IOException, URISyntaxException {
        File directory = migrationDirectory();
        Map<String, String> pinned = readPinned(directory);
        File[] files = directory.listFiles((dir, name) -> MIGRATION_FILE.matcher(name).matches());
        assertNotNull(files, "could not list " + directory);

        List<String> unpinned = new ArrayList<>();
        for (File file : files) {
            if (!pinned.containsKey(file.getName())) {
                unpinned.add(file.getName());
            }
        }
        assertTrue(unpinned.isEmpty(), "migration file(s) exist without a pinned checksum: " + unpinned);
    }

    private static Map<String, String> readPinned(File directory) throws IOException {
        File checksums = new File(directory, CHECKSUMS_FILE);
        assertTrue(checksums.isFile(), CHECKSUMS_FILE + " is missing from " + directory);
        Map<String, String> pinned = new LinkedHashMap<>();
        for (String line : Files.readAllLines(checksums.toPath())) {
            if (line.isBlank()) {
                continue;
            }
            Matcher matcher = CHECKSUM_LINE.matcher(line);
            assertTrue(matcher.matches(), "unparseable " + CHECKSUMS_FILE + " line: " + line);
            pinned.put(matcher.group(2), matcher.group(1));
        }
        return pinned;
    }

    private static File migrationDirectory() throws URISyntaxException {
        URL resource = MigrationChecksumsTest.class.getClassLoader()
                .getResource(RESOURCE_DIR + "/" + CHECKSUMS_FILE);
        assertNotNull(resource, RESOURCE_DIR + "/" + CHECKSUMS_FILE + " is not on the test classpath");
        return new File(resource.toURI()).getParentFile();
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

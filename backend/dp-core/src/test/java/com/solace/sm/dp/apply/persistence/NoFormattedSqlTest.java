package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * W3-13 (OR-99): the I5 identifier carve-out has exactly two sanctioned cases and no third. A statement is never built
 * with {@code String.format} or {@code formatted}, even from a code constant, because that is the pattern a later change
 * copies with a value that is not. A choice between two tables is two constant statements.
 */
class NoFormattedSqlTest {

    @Test
    void noPersistenceRepositoryBuildsASqlStatementWithFormat() throws IOException {
        Path root = Path.of("src/main/java/com/solace/sm/dp/apply/persistence");
        assertTrue(Files.isDirectory(root), "run from the dp-core module: " + root.toAbsolutePath());
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().startsWith("Jdbc"))::iterator) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains("String.format(") || line.contains(".formatted(")) {
                        offenders.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "SQL is bound or chosen between constants, never formatted");
    }
}

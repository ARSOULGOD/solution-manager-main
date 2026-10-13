package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The executable half of design §3.1: the SQL that ships in {@code db/bootstrap} and
 * {@code db/migration/sm_data} carries exactly the statements the design sketch carries — no more,
 * no fewer, none altered.
 *
 * <p><b>Why a mirror and not a copy.</b> §3.1 is the reviewed artefact; the migration files are what
 * actually runs. Splitting one into six files at statement boundaries is a mechanical operation, and
 * a mechanical operation deserves a mechanical check — otherwise the next edit to the design lands in
 * a document nothing executes, or an edit to a migration diverges from the document everyone reads.
 * The evidence report {@code evidence/ddl-execution-pg14.md} measured 7 Phase-0 and 25 Phase-1
 * statements; this test recomputes both from source rather than trusting the numbers.
 *
 * <p>The comparison is a <b>multiset</b>, deliberately: the split redistributes the per-table GRANTs
 * to the migrations that create their tables, so file order is not design order. Statement <i>text</i>
 * is what must not move.
 *
 * <p>This is a surefire unit test — it needs no Docker, and it must fail on a laptop with no Docker
 * daemon, because it guards a text relationship, not a database.
 */
class SketchMirrorTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration/sm_data");
    private static final Path PHASE0 = Path.of("src/main/resources/" + SmDataMigrator.PHASE0_RESOURCE);

    /**
     * Plan 010 T2.4's two Phase-0 statements. They are not in the design sketch (eda-walking-skeleton §3.1 is a
     * reviewed artefact of the first six migrations and does not grow with the schema), so the mirror compares
     * Phase 0 WITHOUT them and a separate test pins that they are exactly these two and nothing else.
     */
    private static final List<String> PHASE0_AFTER_THE_SKETCH = List.of(
            "CREATE SCHEMA sm_secret AUTHORIZATION sm_dp_owner",
            "GRANT USAGE ON SCHEMA sm_secret TO sm_dp_runtime",
            // plan 011 P11-2.1 (M-Q19, OR-76): the DP-owned metrics schema, with its own Flyway history
            "CREATE SCHEMA sm_metrics AUTHORIZATION sm_dp_owner",
            "GRANT USAGE ON SCHEMA sm_metrics TO sm_dp_runtime");

    @Test
    void phase0CarriesExactlyTheSketchPlusTheSecretAndMetricsSchemasFourStatements() {
        List<String> all = SqlStatements.of(phase0Sql());
        assertTrue(all.containsAll(PHASE0_AFTER_THE_SKETCH), "T2.4's two and P11-2.1's two statements are in Phase 0: " + all);
        assertEquals(11, all.size(), "seven in the sketch, four after it");
        assertEquals(PHASE0_AFTER_THE_SKETCH, all.subList(7, 11), "…and they come last");
    }

    @Test
    void theShippedSqlFilesCarryExactlyTheStatementsOfTheDesignSketch() {
        List<String> fromDesign = SqlStatements.of(DesignSketch.sqlBlock());
        List<String> fromFiles = shippedStatements();

        assertEquals(sorted(fromDesign), sorted(fromFiles),
                "the migration files and eda-walking-skeleton.md §3.1 have diverged. Change ONE of "
                        + "them deliberately and re-run — never let them drift.");
    }

    @Test
    void theStatementCountsMatchTheMeasuredPg14EvidenceRun() {
        assertEquals(7, sketchPhase0Statements().size(),
                "Phase 0 is the 7 statements evidence/ddl-execution-pg14.md §2.2 executed (T2.4's two follow them)");
        assertEquals(25, phase1Statements().size(),
                "Phase 1 is the 25 statements evidence/ddl-execution-pg14.md §2.6 executed, plus "
                        + "nothing: SET ROLE is Flyway initSql and is counted in the 25");
    }

    @Test
    void everyMigrationFileCarriesAtLeastOneStatement() {
        List<Path> files = migrationFiles();
        assertEquals(6, files.size(), "the split is six migrations: " + files);
        for (Path f : files) {
            assertFalse(SqlStatements.of(DesignSketch.read(f)).isEmpty(),
                    f.getFileName() + " contributes no statement — a comment-only migration is a "
                            + "version number Flyway records for nothing");
        }
    }

    /**
     * The multiset comparison above cannot see a statement MOVING between files. Flyway's version
     * order would refuse most harmful moves (a GRANT before its table), but not all, and "would fail
     * at migrate time" is not a lock. Pinning each file's statement count is: a move changes two
     * counts.
     */
    @Test
    void eachMigrationCarriesExactlyTheStatementsTheSplitAssignedIt() {
        List<Path> files = migrationFiles();
        int[] expected = {1, 6, 9, 2, 4, 2};   // V001 .. V006 — measured at the split; V005 gained
                                              // one statement (attempt_open_fence, BE-D20)
        assertEquals(expected.length, files.size());
        for (int i = 0; i < files.size(); i++) {
            assertEquals(expected[i], SqlStatements.of(DesignSketch.read(files.get(i))).size(),
                    files.get(i).getFileName() + " carries a different number of statements than "
                            + "the split assigned — a statement moved between files");
        }
    }

    @Test
    void theSetRoleConstantIsTheOnlyStatementNoFileCarries() {
        List<String> inFiles = new ArrayList<>();
        for (Path f : migrationFiles()) {
            inFiles.addAll(SqlStatements.of(DesignSketch.read(f)));
        }
        assertFalse(inFiles.contains(SmDataMigrator.INIT_SQL),
                "SET ROLE sm_dp_owner belongs in Flyway initSql, not in a migration file — Flyway "
                        + "applies initSql to every connection it opens, a file applies it to one");
        assertTrue(SqlStatements.of(DesignSketch.sqlBlock()).contains(SmDataMigrator.INIT_SQL),
                "…and the design sketch does carry it, so the mirror's SET ROLE contribution is real");
    }

    @Test
    void theSplitterPreconditionsHoldForBothPhasesOfTheSketch() {
        assertNull(SqlStatements.assertNoDialectSurprises(DesignSketch.sqlBlock()),
                "the sketch uses SQL the simple splitter cannot see through");
    }

    /**
     * Prove-it-fires. A rule that cannot fail is decoration, so one token is mutated in an in-memory
     * copy of a migration and the same comparison must reject it, naming the statement.
     */
    @Test
    void mutatingOneTokenOfOneMigrationBreaksTheMirrorAndNamesTheStatement() {
        List<String> fromDesign = sorted(SqlStatements.of(DesignSketch.sqlBlock()));

        // `>= 0` becomes `>= -1`: still valid SQL, still parses, and would silently admit a negative
        // instance watermark — exactly the class of edit a byte-diff of the whole file might be
        // argued away as reformatting.
        List<String> mutated = new ArrayList<>();
        boolean touched = false;
        for (String s : shippedStatements()) {
            if (s.contains("instance_watermark_nonneg CHECK (instance_watermark >= 0)")) {
                mutated.add(s.replace("instance_watermark >= 0", "instance_watermark >= -1"));
                touched = true;
            } else {
                mutated.add(s);
            }
        }
        assertTrue(touched, "the mutation target must exist, or this proof proves nothing");

        assertNotEquals(fromDesign, sorted(mutated),
                "the mirror must reject a one-token change");

        List<String> onlyInMutated = new ArrayList<>(sorted(mutated));
        onlyInMutated.removeAll(fromDesign);
        assertEquals(1, onlyInMutated.size(), "exactly one statement should differ: " + onlyInMutated);
        assertTrue(onlyInMutated.get(0).contains("instance_watermark >= -1"),
                "the difference the comparison surfaces must be the mutated statement itself, so a "
                        + "failure message points a reader at the offending SQL. Was: " + onlyInMutated);
    }

    // -------------------------------------------------------------------------------------------

    /** Phase 0 + Flyway's initSql + every migration, in version order — the executed whole. */
    private static List<String> shippedStatements() {
        List<String> all = new ArrayList<>(sketchPhase0Statements());
        all.addAll(phase1Statements());
        return all;
    }

    /** Phase 0 minus the statements added after the sketch (see {@link #PHASE0_AFTER_THE_SKETCH}). */
    private static List<String> sketchPhase0Statements() {
        List<String> all = new ArrayList<>(SqlStatements.of(phase0Sql()));
        all.removeAll(PHASE0_AFTER_THE_SKETCH);
        return all;
    }

    /** What Flyway actually executes: its initSql, then the six migrations in version order. */
    private static List<String> phase1Statements() {
        List<String> all = new ArrayList<>();
        all.add(SmDataMigrator.INIT_SQL);
        for (Path f : migrationFiles()) {
            all.addAll(SqlStatements.of(DesignSketch.read(f)));
        }
        return all;
    }

    private static String phase0Sql() {
        return DesignSketch.read(moduleDir().resolve(PHASE0));
    }

    /**
     * The six migrations design §3.1's sketch was split into. Later migrations (plan 010's V007 onward) have
     * their own design homes and their own ITs; the sketch is a reviewed artefact of the FIRST six and does
     * not grow with the schema, so the mirror is bounded to them rather than to "every file in the folder".
     */
    private static List<Path> migrationFiles() {
        try (Stream<Path> s = Files.list(moduleDir().resolve(MIGRATIONS))) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(p -> p.getFileName().toString().compareTo("V007") < 0)
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Surefire runs with the module as cwd; the reactor root does not, so resolve explicitly. */
    private static Path moduleDir() {
        Path cwd = Path.of("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve(MIGRATIONS)) ? cwd : DesignSketch.repoRoot().resolve("backend/dp-core");
    }

    private static List<String> sorted(List<String> in) {
        return in.stream().sorted().toList();
    }
}

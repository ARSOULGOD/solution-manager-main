package com.solace.sm.dp.apply.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.sql.DataSource;

/**
 * The operator's partition command for {@code sm_metrics.metrics_raw} (plan 011 P11-2.1; M-Q13 c', {@code OR-77}):
 * {@code status}, {@code freeze}, {@code rollup}, {@code detach}, {@code archive} and {@code drop}. Everything destructive that the
 * runtime role cannot and must not do lives here, run like the migration step.
 *
 * <pre>
 *   status
 *   freeze  yyyy_mm            VACUUM (FREEZE) a CLOSED month, then record it in partition_freeze_log   (OQ-10 a)
 *   detach  yyyy_mm            ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY, a CLOSED month        (MF4)
 *                              refused until the rollups have caught up with it (the watermark guard, below)
 *   rollup  yyyy_mm            recompute the hourly and daily rollups of a CLOSED month for the tenants the detach
 *                              guard says are behind, so a month the background loop stopped short of can be detached
 *   archive yyyy_mm dir        per tenant: gzip CSV + a sha256/row-count manifest, unencrypted          (OQ-11 a, OQ-27 b)
 *   drop    yyyy_mm dir        DROP the detached table, only once every tenant's manifest verifies
 * </pre>
 *
 * <p><b>It may enumerate tenants from the rows it operates on</b> ({@code OR-113} (7)): {@code archive} and {@code drop}
 * read {@code DISTINCT}/{@code GROUP BY tenant_id} from the detached month, because the application's two ruled tenant
 * lists ({@code OR-44}, {@code OR-49}) omit a tenant that has data but no active instance, and an archive must not lose
 * it. This is allowed only because it is an operator command run with the owner credential and never reachable from the
 * running application; {@code TenantEnumerationBoundaryTest} fails if any other class does the same.
 *
 * <p><b>The credential.</b> A standalone {@code main} (never a Spring context) with the migrator credential read from
 * the same three environment variables as {@link SmDataMigrationMain}, and {@code SET ROLE sm_dp_owner}. It prints
 * nothing it read: no URL, user or password reaches either stream or an exception message ({@code I6}); a failure is
 * the exception's simple class name and SQLSTATE, as in {@link SmDataMigrationMain}.
 *
 * <p><b>The second sanctioned {@code I5} case.</b> {@code DETACH}, {@code DROP}, {@code VACUUM} and the archive's
 * {@code SELECT} name a partition, and DDL cannot bind an identifier. So: the month is checked against
 * {@link MetricsPartitionArgument} before any connection exists; the partition it names must then be FOUND by a bound
 * catalog lookup; and every statement is built from the catalog's {@code relname}, quoted, never from the argument
 * text. No tenant id is ever spliced into SQL: the archive reads each tenant through a bound parameter
 * (OQ-27 (b); there is no {@code COPY}).
 *
 * <p><b>Archive before drop</b> ({@code architecture.md} §8.5). {@code drop} re-reads every tenant's manifest and
 * archive file and compares the file's hash and row count with the manifest's and the partition's own; one mismatch,
 * or one tenant without a manifest, refuses the drop and removes nothing.
 */
public final class MetricsPartitionMain {

    /** A database error. */
    static final int EXIT_FAILED = 1;
    /** The command line or the environment is wrong, or the month is not a month. Decided before any connection. */
    static final int EXIT_USAGE = 2;
    /** A well-formed request the current state refuses: no such partition, the open month, no verified manifest. */
    static final int EXIT_REFUSED = 3;

    /** Optional: the operator's disk budget in bytes, printed beside the measured sizes by {@code status} (OQ-16). */
    static final String BUDGET_VARIABLE = "SM_METRICS_DISK_BUDGET_BYTES";

    /**
     * Optional, ISO-8601 (default {@code PT2H}): the rollup late window the {@code detach} guard waits out. It must be at
     * least the data plane's {@code sm.dp.metrics.rollup.late-window}.
     */
    static final String LATE_WINDOW_VARIABLE = "SM_METRICS_ROLLUP_LATE_WINDOW";

    /** Optional, ISO-8601 (default {@code P7D}): the raw tier, which raw queries still read from (D-6). */
    static final String RAW_TIER_VARIABLE = "SM_METRICS_RAW_TIER";

    static final Duration DEFAULT_LATE_WINDOW = Duration.ofHours(2);
    static final Duration DEFAULT_RAW_TIER = Duration.ofDays(7);

    /** Opens the owner-credential data source; a seam so a test can prove no connection is asked for. */
    interface DataSourceFactory {
        DataSource open(String url, String username, String password);
    }

    static final DataSourceFactory DEFAULT_FACTORY = SmDataMigrationMain::dataSource;

    /** How long {@code detach} waits for a lock; a test shortens it to interrupt a detach on purpose. */
    static final String DEFAULT_LOCK_TIMEOUT = "10s";

    private static final String PARENT = "sm_metrics.metrics_raw";
    private static final String SCHEMA = "sm_metrics";
    private static final String CLOSED_SQL =
            "SELECT to_date(?, 'YYYY_MM') < date_trunc('month', now() AT TIME ZONE 'UTC')::date";
    private static final int FETCH_SIZE = 5000;
    private static final String HEADER = "tenant_id,series_id,ts,value,increment,received_at";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MetricsPartitionMain() {
    }

    /** @param args the subcommand and its arguments; configuration arrives through the environment, never the command line */
    public static void main(String[] args) {
        System.exit(exitCode(args, System.getenv(), System.out, System.err, DEFAULT_FACTORY));
    }

    /** {@link #main} minus {@link System#exit}, with both streams and the data-source factory injected. */
    static int exitCode(String[] args, Map<String, String> env, PrintStream out, PrintStream err,
            DataSourceFactory factory) {
        return exitCode(args, env, out, err, factory, DEFAULT_LOCK_TIMEOUT);
    }

    static int exitCode(String[] args, Map<String, String> env, PrintStream out, PrintStream err,
            DataSourceFactory factory, String lockTimeout) {
        Command command;
        try {
            command = Command.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        for (String name : new String[] {SmDataMigrationMain.URL_VARIABLE, SmDataMigrationMain.USERNAME_VARIABLE,
                SmDataMigrationMain.PASSWORD_VARIABLE}) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                err.println(name + " is not set: the partition command reads its credential from the environment, "
                        + "and will not guess one");
                return EXIT_USAGE;
            }
        }
        Long budget = null;
        String budgetText = env.get(BUDGET_VARIABLE);
        if (budgetText != null && !budgetText.isBlank()) {
            try {
                budget = Long.parseLong(budgetText.strip());
                if (budget < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                err.println(BUDGET_VARIABLE + " is not a number of bytes");
                return EXIT_USAGE;
            }
        }
        Duration lateWindow;
        Duration rawTier;
        try {
            lateWindow = duration(env.get(LATE_WINDOW_VARIABLE), DEFAULT_LATE_WINDOW);
            rawTier = duration(env.get(RAW_TIER_VARIABLE), DEFAULT_RAW_TIER);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            err.println(LATE_WINDOW_VARIABLE + " and " + RAW_TIER_VARIABLE + " are ISO-8601 durations (for example PT2H, P7D)");
            return EXIT_USAGE;
        }
        try {
            JdbcUrls.requireLocation(env.get(SmDataMigrationMain.URL_VARIABLE));
        } catch (IllegalArgumentException e) {
            err.println(SmDataMigrationMain.URL_VARIABLE + " is not a bare location: " + e.getMessage()
                    + ". The credential belongs in " + SmDataMigrationMain.USERNAME_VARIABLE + " / "
                    + SmDataMigrationMain.PASSWORD_VARIABLE);
            return EXIT_USAGE;
        }
        try {
            DataSource dataSource = factory.open(env.get(SmDataMigrationMain.URL_VARIABLE),
                    env.get(SmDataMigrationMain.USERNAME_VARIABLE), env.get(SmDataMigrationMain.PASSWORD_VARIABLE));
            try (Connection c = dataSource.getConnection()) {
                try (Statement s = c.createStatement()) {
                    s.execute("SET ROLE sm_dp_owner");
                }
                switch (command.name()) {
                    case "status" -> status(c, budget, out);
                    case "freeze" -> freeze(c, command.month(), out);
                    case "rollup" -> rollup(c, command.month(), out);
                    case "detach" -> detach(c, command.month(), lockTimeout, lateWindow, rawTier, out);
                    case "archive" -> archive(c, command.month(), command.directory(), out);
                    case "drop" -> drop(c, command.month(), command.directory(), out);
                    default -> throw new IllegalStateException("unreachable");
                }
            }
            return 0;
        } catch (Refused e) {
            err.println(e.getMessage());
            return EXIT_REFUSED;
        } catch (DetachPending e) {
            err.println(e.getMessage());
            return EXIT_FAILED;
        } catch (SmDataMigrationMain.NoDriverRegistered e) {
            err.println("the partition command failed: " + e.getMessage());
            return EXIT_FAILED;
        } catch (SQLException | RuntimeException | IOException e) {
            // classifiers only: a driver message may carry the URL or the role
            err.println("the partition command failed: " + SmDataMigrationMain.classify(e));
            return EXIT_FAILED;
        }
    }

    // ---- the subcommands -----------------------------------------------------------------------------------------

    private static void status(Connection c, Long budget, PrintStream out) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT (max(to_date(right(c.relname, 7), 'YYYY_MM')) + interval '1 month')::date"
                        + " FROM pg_catalog.pg_inherits h JOIN pg_catalog.pg_class c ON c.oid = h.inhrelid"
                        + " WHERE h.inhparent = ?::regclass")) {
            ps.setString(1, PARENT);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                out.println("runway end: " + (rs.getObject(1) == null ? "none (no partition is attached)" : rs.getString(1)));
            }
        }
        int unfrozenClosed = 0;
        int pendingDetach = 0;
        List<String> lines = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT c.relname, to_date(right(c.relname, 7), 'YYYY_MM') < date_trunc('month', now() AT TIME ZONE 'UTC')::date,"
                        + " EXISTS (SELECT 1 FROM sm_metrics.partition_freeze_log f WHERE f.partition_name = c.relname),"
                        + " h.inhdetachpending"
                        + " FROM pg_catalog.pg_inherits h JOIN pg_catalog.pg_class c ON c.oid = h.inhrelid"
                        + " WHERE h.inhparent = ?::regclass ORDER BY c.relname")) {
            ps.setString(1, PARENT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean closed = rs.getBoolean(2);
                    boolean frozen = rs.getBoolean(3);
                    boolean pending = rs.getBoolean(4);
                    if (closed && !frozen) {
                        unfrozenClosed++;
                    }
                    if (pending) {
                        pendingDetach++;
                    }
                    lines.add("  " + rs.getString(1) + (pending ? " pending detach" : "") + " " + (closed ? "closed" : "open")
                            + (closed && !frozen ? " unfrozen" : ""));
                }
            }
        }
        out.println("attached partitions: " + lines.size());
        lines.forEach(out::println);
        out.println("unfrozen closed partitions: " + unfrozenClosed);
        out.println("pending detach: " + pendingDetach + (pendingDetach == 0 ? ""
                : " (an interrupted detach: its rows are hidden from queries through the parent. Run detach again "
                        + "for each to finish it)"));
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT coalesce(sum(pg_total_relation_size(c.oid)), 0)::bigint FROM pg_catalog.pg_class c"
                        + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relkind = 'r'")) {
            ps.setString(1, SCHEMA);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                out.println("sm_metrics bytes: " + rs.getLong(1));
            }
        }
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT pg_database_size(current_database())")) {
            rs.next();
            out.println("database bytes: " + rs.getLong(1));
        }
        out.println("budget bytes: " + (budget == null ? "unconfigured" : budget.toString()));
    }

    private static void freeze(Connection c, MetricsPartitionArgument month, PrintStream out) throws SQLException {
        String relname = requireAttachedClosed(c, month).relname();
        try (Statement s = c.createStatement()) {
            s.execute("VACUUM (FREEZE) " + qualified(relname));          // outside a transaction block (MF3)
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sm_metrics.partition_freeze_log (partition_name, frozen_at) VALUES (?, now())"
                        + " ON CONFLICT (partition_name) DO UPDATE SET frozen_at = EXCLUDED.frozen_at")) {
            ps.setString(1, relname);
            ps.executeUpdate();
        }
        out.println("froze " + relname);
    }

    private static Duration duration(String text, Duration fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        Duration parsed = Duration.parse(text.strip());
        if (parsed.isNegative()) {
            throw new IllegalArgumentException("negative");
        }
        return parsed;
    }

    private static void detach(Connection c, MetricsPartitionArgument month, String lockTimeout, Duration lateWindow,
            Duration rawTier, PrintStream out) throws SQLException {
        Attached attached = requireAttachedClosed(c, month);
        String relname = attached.relname();
        if (!attached.pendingDetach()) {                // an interrupted detach already passed this guard
            requireRolledUp(c, relname, lateWindow, rawTier);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('lock_timeout', ?, false)")) {
            ps.setString(1, lockTimeout);
            ps.execute();
        }
        // CONCURRENTLY cannot run inside a transaction block (MF4): this connection is in auto-commit. An earlier
        // CONCURRENTLY interrupted after its first phase leaves the partition pending; PostgreSQL then refuses another
        // CONCURRENTLY and finishes it only with FINALIZE.
        String mode = attached.pendingDetach() ? "FINALIZE" : "CONCURRENTLY";
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE " + PARENT + " DETACH PARTITION " + qualified(relname) + " " + mode);
        } catch (SQLException e) {
            // the second phase waits for older transactions; if lock_timeout cancelled that wait the partition is left
            // half-detached, and says so in the catalog
            Attached after = findAttached(c, month);
            if (after != null && after.pendingDetach()) {
                throw new DetachPending(relname);
            }
            throw e;
        }
        out.println("detached " + relname + " (the table and its rows are kept; archive it, then drop it)");
    }

    /**
     * The rollup watermark guard (the obligation from the P11-2.1 wave review, owned by plan 011 P11-3.2). A closed month
     * is not detached until (1) the clock has passed its end by the raw tier AND the rollup late window, so the days it
     * holds are no longer read from raw and no late sample can still arrive for them, and (2) every series that has a raw
     * row in it has an hourly rollup for the hour of its LAST raw row that was computed <b>after the newest row of that
     * hour was received</b> (W2-04). Without it, the previous month's last days, still inside the 7-day raw tier, could be
     * detached before their hours were ever rolled up, and the gap would be permanent.
     *
     * <p><b>Why received-at, not the late window.</b> The earlier second condition asked for a rollup computed after the
     * hour's late window closed. The rollup loop only recomputes a bucket while it is inside that window, so an hour that
     * no run happened to cover after the window (SM down across it, or a tenant whose last instance retired and whose
     * rollups therefore stopped) could never satisfy it: the detach was refused forever and nothing could recompute the
     * hour. A rollup computed after the last row arrived has, by construction, seen every row that ever arrived for the
     * hour, and condition (1) already rules out a later arrival. {@link #rollup} recomputes what this lists.
     *
     * <p><b>What it does not prove:</b> it checks each series' last hour, not every hour in between; an outage of the
     * rollup loop in the middle of a month is not detected here. The runbook rule (let the rollups run, check
     * {@code status}) stays. It reads {@code series} and probes the partition once per series through its primary key; it
     * never scans the partition. The partition name comes from the catalog, quoted, as everywhere in this command.
     */
    private static void requireRolledUp(Connection c, String relname, Duration lateWindow, Duration rawTier)
            throws SQLException {
        long waitSeconds = lateWindow.plus(rawTier).toSeconds();
        try (PreparedStatement ps = c.prepareStatement("SELECT ((to_date(?, 'YYYY_MM') + interval '1 month')::timestamp"
                + " AT TIME ZONE 'UTC') + make_interval(secs => ?) <= now()")) {
            ps.setString(1, relname.substring(MetricsPartitionArgument.PARTITION_PREFIX.length()));
            ps.setDouble(2, waitSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    throw new Refused("refusing to detach " + relname + ": its raw rows may still be read (the raw tier is "
                            + rawTier + ") or may still be corrected by a late sample (the rollup late window is "
                            + lateWindow + "). Detach once both have passed since the month ended");
                }
            }
        }
        long unrolled = unrolledSeries(c, relname).size();
        if (unrolled > 0) {
            throw new Refused("refusing to detach " + relname + ": " + unrolled + " series have raw rows in it whose last "
                    + "hour has no rollup computed after its newest row was received. Let the rollups run (the metrics "
                    + "background loop recomputes the late window every few minutes), or recompute the month with "
                    + "'rollup " + relname.substring(MetricsPartitionArgument.PARTITION_PREFIX.length())
                    + "', then detach again");
        }
    }

    /** One series the guard lists; the tenant is only ever used as a bound value (never printed, never spliced). */
    private record Unrolled(UUID tenant, long series) {
    }

    private static final String ORIGIN = "TIMESTAMPTZ '2001-01-01 00:00:00+00'";

    /** The series whose last raw hour has no rollup computed after that hour's newest row was received. */
    private static List<Unrolled> unrolledSeries(Connection c, String relname) throws SQLException {
        String partition = qualified(relname);
        String lastHour = "date_bin('1 hour', x.last_ts, " + ORIGIN + ")";
        List<Unrolled> behind = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT x.tenant_id, x.series_id FROM ("
                + "SELECT s.tenant_id, s.series_id, l.last_ts FROM sm_metrics.series s CROSS JOIN LATERAL (SELECT max(r.ts) AS last_ts FROM "
                + partition + " r WHERE r.tenant_id = s.tenant_id AND r.series_id = s.series_id) l WHERE l.last_ts IS NOT NULL) x"
                + " WHERE NOT EXISTS (SELECT 1 FROM sm_metrics.metrics_rollup_1h h WHERE h.tenant_id = x.tenant_id"
                + " AND h.series_id = x.series_id AND h.bucket = " + lastHour
                + " AND h.computed_at >= (SELECT max(n.received_at) FROM " + partition + " n WHERE n.tenant_id = x.tenant_id"
                + " AND n.series_id = x.series_id AND n.ts >= " + lastHour + " AND n.ts < " + lastHour
                + " + interval '1 hour'))"
                + " ORDER BY x.tenant_id, x.series_id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                behind.add(new Unrolled(rs.getObject(1, UUID.class), rs.getLong(2)));
            }
        }
        return behind;
    }

    /**
     * {@code rollup yyyy_mm} (W2-04): recomputes every hourly bucket and every day of a CLOSED month, from raw, for each
     * tenant that has a series the detach guard lists, with the same statements the background loop runs
     * ({@link JdbcRollupRepository}). The loop only recomputes buckets inside the late window, so a month it stopped
     * short of (the service was down across the hour's window; the tenant's last instance retired) had no other way
     * back. Idempotent: running it again rewrites the same buckets with what is now true. It does not detach.
     *
     * <p>It runs on the owner connection, after {@code SET ROLE sm_dp_owner}, through the same repository the data plane
     * uses; the tenant ids are bound values and are never printed.
     */
    private static void rollup(Connection c, MetricsPartitionArgument month, PrintStream out) throws SQLException {
        String relname = requireAttachedClosed(c, month).relname();
        java.util.TreeSet<UUID> tenants = new java.util.TreeSet<>();
        int series = 0;
        for (Unrolled behind : unrolledSeries(c, relname)) {
            tenants.add(behind.tenant());
            series++;
        }
        if (tenants.isEmpty()) {
            out.println("rollup " + relname + ": every series' last hour is already rolled up; nothing to do");
            return;
        }
        java.time.YearMonth ym = java.time.YearMonth.of(
                Integer.parseInt(month.yyyyMm().substring(0, 4)), Integer.parseInt(month.yyyyMm().substring(5, 7)));
        Instant from = ym.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        JdbcRollupRepository rollups = new JdbcRollupRepository(new org.springframework.jdbc.core.namedparam
                .NamedParameterJdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c, true)));
        int hours = 0;
        int days = 0;
        Instant computedAt = Instant.now();
        for (UUID tenant : tenants) {
            hours += rollups.rollUpHours(tenant, from, to, computedAt);
            days += rollups.rollUpDays(tenant, from, to, computedAt);
        }
        out.println("rolled up " + relname + " for " + tenants.size() + " tenant(s) with " + series + " series behind: "
                + hours + " hourly and " + days + " daily rows written");
    }

    private static void archive(Connection c, MetricsPartitionArgument month, Path directory, PrintStream out)
            throws SQLException, IOException {
        String relname = requireDetached(c, month);
        Files.createDirectories(directory);
        List<UUID> tenants = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT DISTINCT tenant_id FROM " + qualified(relname) + " ORDER BY tenant_id")) {
            while (rs.next()) {
                tenants.add(rs.getObject(1, UUID.class));
            }
        }
        if (tenants.isEmpty()) {
            out.println("archived " + relname + ": it holds no rows, so there is nothing to write");
            return;
        }
        for (UUID tenant : tenants) {
            archiveTenant(c, relname, tenant, directory, out);
        }
    }

    private static void archiveTenant(Connection c, String relname, UUID tenant, Path directory, PrintStream out)
            throws SQLException, IOException {
        String fileName = relname + "." + tenant + ".csv.gz";
        Path target = directory.resolve(fileName);
        Path temp = directory.resolve(fileName + ".tmp");
        MessageDigest digest = sha256();
        long rows = 0;
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);                       // a cursor needs a transaction (M-Q18's export mechanism)
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT tenant_id, series_id, ts, value, increment, received_at FROM " + qualified(relname)
                        + " WHERE tenant_id = ? ORDER BY series_id, ts", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(FETCH_SIZE);
            ps.setObject(1, tenant);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(new DigestOutputStream(Files.newOutputStream(temp), digest)), StandardCharsets.UTF_8));
                    ResultSet rs = ps.executeQuery()) {
                writer.write(HEADER);
                writer.write('\n');
                while (rs.next()) {
                    double increment = rs.getDouble(5);
                    boolean noIncrement = rs.wasNull();
                    writer.write(rs.getObject(1, UUID.class) + "," + rs.getLong(2) + ","
                            + instant(rs.getObject(3, OffsetDateTime.class)) + "," + rs.getDouble(4) + ","
                            + (noIncrement ? "" : Double.toString(increment)) + ","
                            + instant(rs.getObject(6, OffsetDateTime.class)) + "\n");
                    rows++;
                }
            }
            c.commit();
        } finally {
            c.setAutoCommit(autoCommit);
        }
        String sha = HexFormat.of().formatHex(digest.digest());
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "csv+gzip, unencrypted (OQ-11): the destination must be encrypted storage");
        manifest.put("partition", relname);
        manifest.put("tenantId", tenant.toString());
        manifest.put("file", fileName);
        manifest.put("columns", List.of(HEADER.split(",")));
        manifest.put("rows", rows);
        manifest.put("sha256", sha);
        manifest.put("archivedAt", Instant.now().toString());
        Path manifestPath = directory.resolve(relname + "." + tenant + ".manifest.json");
        Files.writeString(manifestPath, JSON.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
        out.println("archived " + relname + " for tenant " + tenant + ": " + rows + " rows, sha256 " + sha);
    }

    private static void drop(Connection c, MetricsPartitionArgument month, Path directory, PrintStream out)
            throws SQLException, IOException {
        String relname = requireDetached(c, month);
        Map<UUID, Long> counts = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT tenant_id, count(*) FROM " + qualified(relname)
                        + " GROUP BY tenant_id ORDER BY tenant_id")) {
            while (rs.next()) {
                counts.put(rs.getObject(1, UUID.class), rs.getLong(2));
            }
        }
        for (Map.Entry<UUID, Long> tenant : counts.entrySet()) {
            verifyManifest(directory, relname, tenant.getKey(), tenant.getValue());
        }
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE " + qualified(relname));
        }
        out.println("dropped " + relname + " after verifying " + counts.size() + " tenant archive(s)");
    }

    /** The archive for one tenant must exist, hash to its manifest, and hold exactly the partition's rows. */
    private static void verifyManifest(Path directory, String relname, UUID tenant, long expectedRows) throws IOException {
        Path manifestPath = directory.resolve(relname + "." + tenant + ".manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new Refused("refusing to drop " + relname + ": tenant " + tenant + " has no manifest in the archive "
                    + "directory. Run archive first");
        }
        Map<?, ?> manifest = JSON.readValue(manifestPath.toFile(), Map.class);
        String fileName = relname + "." + tenant + ".csv.gz";
        Path file = directory.resolve(fileName);
        if (!relname.equals(manifest.get("partition")) || !tenant.toString().equals(manifest.get("tenantId"))
                || !fileName.equals(manifest.get("file"))) {
            throw new Refused("refusing to drop " + relname + ": the manifest for tenant " + tenant
                    + " does not describe this partition, tenant and file");
        }
        if (!Files.isRegularFile(file)) {
            throw new Refused("refusing to drop " + relname + ": the archive file for tenant " + tenant + " is missing");
        }
        String actualSha = sha256Of(file);
        if (!actualSha.equals(manifest.get("sha256"))) {
            throw new Refused("refusing to drop " + relname + ": the archive file for tenant " + tenant
                    + " does not match its manifest's sha256");
        }
        long fileRows = gzipDataRows(file);
        Object manifestRows = manifest.get("rows");
        if (!(manifestRows instanceof Number n) || n.longValue() != expectedRows || fileRows != expectedRows) {
            throw new Refused("refusing to drop " + relname + ": the archive for tenant " + tenant + " does not hold exactly "
                    + "the partition's " + expectedRows + " rows");
        }
    }

    // ---- catalog lookups: the identifier comes from the catalog, never from the argument -------------------------

    /** An attached partition, as the catalog names it, and whether an interrupted detach has left it pending. */
    private record Attached(String relname, boolean pendingDetach) {
    }

    private static Attached findAttached(Connection c, MetricsPartitionArgument month) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT c.relname, h.inhdetachpending FROM pg_catalog.pg_inherits h"
                + " JOIN pg_catalog.pg_class c ON c.oid = h.inhrelid WHERE h.inhparent = ?::regclass AND c.relname = ?")) {
            ps.setString(1, PARENT);
            ps.setString(2, month.partitionName());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Attached(rs.getString(1), rs.getBoolean(2)) : null;
            }
        }
    }

    private static Attached requireAttachedClosed(Connection c, MetricsPartitionArgument month) throws SQLException {
        Attached attached = findAttached(c, month);
        if (attached == null) {
            throw new Refused("no attached partition " + month.partitionName() + " under " + PARENT
                    + " (already detached, or never created)");
        }
        String relname = attached.relname();
        try (PreparedStatement ps = c.prepareStatement(CLOSED_SQL)) {
            ps.setString(1, relname.substring(MetricsPartitionArgument.PARTITION_PREFIX.length()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) {
                    throw new Refused(relname + " is not a closed month (UTC): ingest still writes to it, so it is "
                            + "neither frozen nor detached");
                }
            }
        }
        return attached;
    }

    private static String requireDetached(Connection c, MetricsPartitionArgument month) throws SQLException {
        String relname = lookup(c, "SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n"
                + " ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ? AND c.relkind = 'r'"
                + " AND NOT c.relispartition", SCHEMA, month.partitionName());
        if (relname == null) {
            throw new Refused("no detached table " + month.partitionName() + " in " + SCHEMA
                    + " (still attached, already dropped, or never created): detach it first");
        }
        return relname;
    }

    private static String lookup(Connection c, String sql, String first, String second) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, first);
            ps.setString(2, second);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** {@code sm_metrics."relname"}: the catalog's name, quoted as an identifier. */
    private static String qualified(String catalogRelname) {
        return SCHEMA + ".\"" + catalogRelname.replace("\"", "\"\"") + "\"";
    }

    // ---- small helpers -------------------------------------------------------------------------------------------

    private static String instant(OffsetDateTime value) {
        return DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The file's sha256, streamed: an archive of a tenant-month can be larger than the heap, or than 2 GiB. */
    static String sha256Of(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0;) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long gzipDataRows(Path file) throws IOException {
        long lines = 0;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0;) {
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        lines++;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Math.max(0, lines - 1);                 // minus the header
    }

    /** A detach that did not finish and left the partition pending: a fixed message, no driver text (I6). */
    private static final class DetachPending extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DetachPending(String relname) {
            super("the detach of " + relname + " did not finish: it is PENDING (an older transaction held it past the "
                    + "lock timeout), and until it finishes its rows are hidden from queries through " + PARENT
                    + ". Run the same detach command again to finish it");
        }
    }

    /** A well-formed request the current state refuses; the message is this class's own words. */
    private static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** The parsed command line. */
    private record Command(String name, MetricsPartitionArgument month, Path directory) {

        static Command parse(String[] args) {
            String usage = "usage: status | freeze yyyy_mm | rollup yyyy_mm | detach yyyy_mm | archive yyyy_mm <dir> | "
                    + "drop yyyy_mm <dir>";
            if (args == null || args.length == 0) {
                throw new IllegalArgumentException(usage);
            }
            int expected = switch (args[0]) {
                case "status" -> 1;
                case "freeze", "rollup", "detach" -> 2;
                case "archive", "drop" -> 3;
                default -> throw new IllegalArgumentException(usage);
            };
            if (args.length != expected) {
                throw new IllegalArgumentException(usage);
            }
            MetricsPartitionArgument month = expected == 1 ? null : MetricsPartitionArgument.parse(args[1]);
            Path directory = expected == 3 ? Path.of(args[2]) : null;
            return new Command(args[0], month, directory);
        }
    }
}

package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.apply.runtime.ssh.HostKeyScanner;
import com.solace.sm.dp.apply.runtime.ssh.SshHostKeyScanner;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.TargetConnectionValidator;
import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.EnvironmentMasterKeyProvider;
import com.solace.sm.dp.secret.KekCanaryCheck;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The data-plane half of target registration (plan 010 T2.7; {@code OR-28}): takes the {@code targetId} the control
 * plane's {@code TargetRegistrationMain} printed, the connection fields, <b>the envelope</b> (B2) and a private-key
 * <b>file path</b>, stores the key through the secret store (purpose {@code SSH_PRIVATE_KEY}, by reference) and
 * writes {@code sm_data.target_connection}.
 *
 * <p><b>A standalone {@code main}</b>, shaped like {@link KeyRotationMain}: the DP runtime credential and the master
 * key come from the environment, collaborators are constructed by hand, and <b>no Spring context is ever started</b>,
 * so it can never start a second {@code ApplyWorker} or recovery sweep against the live database
 * ({@code WorkerSingletonGuard}). It lives here because JDBC is confined to this package.
 *
 * <p><b>Its authority is the DP runtime credential on the host.</b> It cannot call control-plane authorization
 * ({@code I7}) and needs none: a connection row is inert until a CP target with that id exists and a principal passes
 * {@code target.use} at its ownership scope (T4.4). A connection row with no CP target is never reachable.
 *
 * <p><b>The key.</b> Read once from the named file into a {@code char[]}, handed to the store, and zeroised in a
 * {@code finally}; the file's bytes are zeroised as soon as they are decoded. The key, the path, the database
 * password and the master key are never printed. The master key's canary must verify first, so a mistyped master key
 * cannot seal a credential nobody can open again.
 *
 * <p><b>The envelope is validated here first</b> (a root of {@code /} or an empty prefix is no envelope) with the same
 * grammar V007's CHECKs enforce, so the refusal is a clear message rather than a constraint name; the CHECKs and the
 * placement trigger remain the second and third enforcers. The envelope is immutable once written: changing it means
 * registering a new target.
 *
 * <p><b>Every key type the host offers is recorded</b> (OR-89's residual, plan 010 T7.1). A host that offers ed25519,
 * ecdsa and rsa has three fingerprints, and pinning a different one on a second registration would otherwise slip past
 * the per-organization uniqueness of the pinned key. So the command asks the host for its keys
 * ({@link SshHostKeyScanner}, no credential involved) or takes the operator's explicit {@link #FINGERPRINTS_VARIABLE};
 * the pinned fingerprint must be one of them; and V012's {@code target_host_key_once} refuses any key another live
 * target of the organization already holds. A host that cannot be asked, with no list given, is refused.
 *
 * <p><b>Idempotent on {@code targetId}</b>: an identical re-run (the same record AND the same key) writes nothing
 * (and stores no second key) but still records a key type it has newly learned; a differing record is refused, and so
 * is the same record with a different key (plan 013 T1.1: compared by the fingerprint of the key's PUBLIC half, so a
 * re-export of one key is still a replay; the stored key is never replaced here, rotate it instead).
 *
 * <p><b>The key must open</b> as an UNENCRYPTED private key, or the registration is refused before anything is
 * stored (exit 2, "not accepted"): the transport could never use any other. <b>The field grammars</b> are not here:
 * {@link TargetConnectionValidator} holds them, and the service applies the same ones.
 */
public final class TargetConnectionMain {

    /** {@code jdbc:postgresql://…} — the SM database. Credentials do NOT belong in it. */
    public static final String URL_VARIABLE = "SM_DATA_RUNTIME_URL";
    /** The DP runtime role, normally {@code sm_dp_runtime}. */
    public static final String USERNAME_VARIABLE = "SM_DATA_RUNTIME_USERNAME";
    /** That role's secret. Read, used, and never echoed. */
    public static final String PASSWORD_VARIABLE = "SM_DATA_RUNTIME_PASSWORD";

    /** The organization (tenant) the target belongs to — the install operator's own knowledge, not a request field. */
    public static final String TENANT_VARIABLE = "SM_TARGET_TENANT_ID";
    /** The id {@code TargetRegistrationMain} printed. */
    public static final String TARGET_ID_VARIABLE = "SM_TARGET_ID";
    public static final String HOST_VARIABLE = "SM_TARGET_HOST";
    /** Optional; defaults to 22. */
    public static final String PORT_VARIABLE = "SM_TARGET_PORT";
    public static final String SSH_USER_VARIABLE = "SM_TARGET_SSH_USER";
    /** The pinned host key, as {@code SHA256:<base64>}. */
    public static final String FINGERPRINT_VARIABLE = "SM_TARGET_HOST_KEY_FINGERPRINT";
    /**
     * Optional (OR-89's residual): EVERY host-key fingerprint of the host, comma separated, one per key type (for
     * example {@code ssh-keyscan -t ed25519,ecdsa,rsa host | ssh-keygen -lf - -E sha256}). It must contain the pinned
     * one. When it is set the host is not contacted; when it is not, the command asks the host itself.
     */
    public static final String FINGERPRINTS_VARIABLE = "SM_TARGET_HOST_KEY_FINGERPRINTS";
    /** B2: the directory a declare body's {@code configDirectory} must lie inside. */
    public static final String CONFIG_ROOT_VARIABLE = "SM_TARGET_ALLOWED_CONFIG_ROOT";
    /** B2: the prefix a declare body's {@code containerName} must carry. */
    public static final String CONTAINER_PREFIX_VARIABLE = "SM_TARGET_ALLOWED_CONTAINER_PREFIX";
    /** The path of the SSH private key file. The path is read; the file's content never leaves this process. */
    public static final String KEY_FILE_VARIABLE = "SM_TARGET_PRIVATE_KEY_FILE";
    /** Optional: the registering principal's id, for {@code created_by}. Defaults to {@link #OPERATOR_ACTOR}. */
    public static final String ACTOR_VARIABLE = "SM_TARGET_ACTOR_ID";

    /**
     * {@code created_by} when no principal is named: this command acts on nobody's behalf (it holds the runtime
     * credential, not a login), exactly as {@code SmControlLockoutRecoveryMain} records no actor.
     */
    public static final UUID OPERATOR_ACTOR = new UUID(0L, 1L);

    /** The registration failed (a database error, a different record already registered, or the keys do not match). */
    static final int EXIT_FAILED = 1;
    /** A variable is missing or malformed, or the envelope or a field is not acceptable. */
    static final int EXIT_MISSING_VARIABLE = 2;

    private TargetConnectionMain() {
    }

    /** @param args ignored — configuration arrives through the environment, never the command line */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), TargetConnectionMain::readKeyFile, new SshHostKeyScanner(), System.out,
                System.err));
    }

    /** {@link #main} minus {@link System#exit}, with the streams, the key reader and the host-key scanner injected. */
    static int exitCode(Map<String, String> env, Function<String, char[]> keyReader, HostKeyScanner scanner,
            PrintStream out, PrintStream err) {
        for (String name : new String[] {URL_VARIABLE, USERNAME_VARIABLE, PASSWORD_VARIABLE, TENANT_VARIABLE,
                TARGET_ID_VARIABLE, HOST_VARIABLE, SSH_USER_VARIABLE, FINGERPRINT_VARIABLE, CONFIG_ROOT_VARIABLE,
                CONTAINER_PREFIX_VARIABLE, KEY_FILE_VARIABLE}) {
            if (isBlank(env.get(name))) {
                err.println(name + " is not set: the target connection registration reads its configuration from "
                        + "the environment, and will not guess it");
                return EXIT_MISSING_VARIABLE;
            }
        }
        try {
            JdbcUrls.requireLocation(env.get(URL_VARIABLE));
        } catch (IllegalArgumentException e) {
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage() + ". The credential belongs in "
                    + USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }
        UUID tenant;
        UUID targetId;
        UUID actor;
        TargetConnectionSpec spec;
        try {
            tenant = uuid(env, TENANT_VARIABLE);
            targetId = uuid(env, TARGET_ID_VARIABLE);
            actor = isBlank(env.get(ACTOR_VARIABLE)) ? OPERATOR_ACTOR : uuid(env, ACTOR_VARIABLE);
            spec = spec(env);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return EXIT_MISSING_VARIABLE;
        }
        MasterKeyRing ring;
        try {
            ring = new EnvironmentMasterKeyProvider(env::get).load();
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return EXIT_MISSING_VARIABLE;
        }

        char[] key = keyReader.apply(env.get(KEY_FILE_VARIABLE));
        if (key == null) {
            err.println(KEY_FILE_VARIABLE + " does not name a readable key file (UTF-8 text, 1 to "
                    + EnvelopeSecretStore.MAX_VALUE_BYTES + " bytes)");
            return EXIT_MISSING_VARIABLE;
        }
        try {
            spec = withHostKeys(spec, env, scanner);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            Arrays.fill(key, '\0');
            return EXIT_MISSING_VARIABLE;
        }
        try {
            boolean recorded = register(env.get(URL_VARIABLE), env.get(USERNAME_VARIABLE),
                    env.get(PASSWORD_VARIABLE), ring, tenant, targetId, spec, key, actor);
            out.println(recorded ? "target connection recorded for target " + targetId
                    : "target " + targetId + " is already registered with this record; credential unchanged "
                            + "(this command never replaces a stored key: use credential rotation)");
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("the target connection was not accepted: " + e.getMessage());
            return EXIT_MISSING_VARIABLE;
        } catch (IllegalStateException e) {
            err.println("the target connection was not recorded: " + e.getMessage());
            return EXIT_FAILED;
        } catch (PgConstraintViolation e) {
            // a constraint NAME is an identifier (e.g. target_connection_host_once), never a row's text
            err.println("the target connection was not recorded: the database refused it (constraint "
                    + e.constraint() + ")");
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            // classifiers only: a driver message may carry the URL or the role
            err.println("the target connection registration failed: " + e.getClass().getSimpleName());
            return EXIT_FAILED;
        } finally {
            Arrays.fill(key, '\0');
        }
    }

    /**
     * Writes the connection record and its sealed key in one transaction. The caller zeroises {@code privateKey}.
     *
     * @return true when this call recorded the connection; false when an identical record with the same key (by
     *         public fingerprint) was already registered and nothing was written
     * @throws IllegalStateException the master key does not verify against the install's canary, a different
     *         record is already registered for {@code targetId}, or the same record is registered with a different
     *         key ({@code TargetCredentialConflictException})
     * @throws IllegalArgumentException a field is outside the grammar, or the key does not open as an unencrypted
     *         private key ({@code CredentialInvalidException})
     */
    public static boolean register(String url, String username, String password, MasterKeyRing ring, UUID tenantId,
            UUID targetId, TargetConnectionSpec spec, char[] privateKey, UUID actor) {
        DataSource dataSource = SmDataMigrationMain.dataSource(url, username, password);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Envelope envelope = Envelope.standard();

        // Read-only: a mistyped master key would seal a credential that nobody could ever open again.
        MasterKey current = ring.current();
        if (!KekCanaryCheck.canaryVerifies(envelope, current, new JdbcKekCanaryRepository(jdbc).find(current.id()))) {
            throw new IllegalStateException("the master key does not match this install's canary: start the "
                    + "application once with the correct key first, and check "
                    + EnvironmentMasterKeyProvider.KEY + " and " + EnvironmentMasterKeyProvider.KEY_ID);
        }
        EnvelopeSecretStore secrets = new EnvelopeSecretStore(new JdbcSecretRepository(jdbc), ring, envelope,
                Clock.systemUTC());
        InventoryService inventory = new InventoryService(transactions, new JdbcInstanceStateRepository(jdbc),
                new JdbcPlacementRepository(jdbc), new JdbcTargetConnectionRepository(jdbc), secrets, secrets,
                Clock.systemUTC(), new JdbcMetricDefinitionRepository(jdbc),
                // a target registration touches no placement, so there is no ladder to settle and nothing to announce
                com.solace.sm.dp.config.LadderRecorder.none(), com.solace.sm.dp.apply.events.ApplyEventEmitter.silent(),
                // the command never runs the HTTP pre-check (it asks the host itself, above), so it holds no scan service
                null);
        boolean existed = inventory.connectionOf(tenantId, targetId).isPresent();
        inventory.register(tenantId, targetId, spec, privateKey, actor);
        return !existed;
    }

    /**
     * OR-89's residual: records every key type the host offers, so the same machine cannot be registered a second time
     * in one organization by pinning a different one. The operator's explicit list wins (and the host is not
     * contacted); otherwise the host is asked. Either way the pinned fingerprint must be one of them, or the pin is
     * not a key this host presents. A host that cannot be asked, with no list given, is refused: registering without
     * knowing the host's other keys would silently reopen the gap this closes.
     */
    private static TargetConnectionSpec withHostKeys(TargetConnectionSpec spec, Map<String, String> env,
            HostKeyScanner scanner) {
        Set<String> offered = new LinkedHashSet<>();
        if (!isBlank(env.get(FINGERPRINTS_VARIABLE))) {
            for (String part : env.get(FINGERPRINTS_VARIABLE).split(",")) {
                String fingerprint = part.strip();
                require(TargetConnectionValidator.isFingerprint(fingerprint), FINGERPRINTS_VARIABLE,
                        "a comma-separated list of SHA256:<base64>");
                offered.add(fingerprint);
            }
        } else {
            try {
                offered.addAll(scanner.scan(spec.host(), spec.port()));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("the host could not be asked for its SSH host keys, so the other "
                        + "key types it offers are unknown: reach the host from here, or set " + FINGERPRINTS_VARIABLE
                        + " to every fingerprint it offers (one per key type)");
            }
        }
        if (!offered.contains(spec.hostKeyFingerprint())) {
            throw new IllegalArgumentException(FINGERPRINT_VARIABLE + " is not one of the host keys the host "
                    + "presents: pin the fingerprint of a key it actually offers");
        }
        return new TargetConnectionSpec(spec.host(), spec.port(), spec.sshUser(), spec.hostKeyFingerprint(),
                spec.allowedConfigRoot(), spec.allowedContainerPrefix(), offered);
    }

    private static TargetConnectionSpec spec(Map<String, String> env) {
        String host = env.get(HOST_VARIABLE).strip();
        require(TargetConnectionValidator.isHost(host), HOST_VARIABLE, "a host name or address");
        String sshUser = env.get(SSH_USER_VARIABLE).strip();
        require(TargetConnectionValidator.isSshUser(sshUser), SSH_USER_VARIABLE, "a login name");
        String fingerprint = env.get(FINGERPRINT_VARIABLE).strip();
        require(TargetConnectionValidator.isFingerprint(fingerprint), FINGERPRINT_VARIABLE, "SHA256:<base64>");
        int port = 22;
        if (!isBlank(env.get(PORT_VARIABLE))) {
            try {
                port = Integer.parseInt(env.get(PORT_VARIABLE).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(PORT_VARIABLE + " is not a port number");
            }
            if (!TargetConnectionValidator.isPort(port)) {
                throw new IllegalArgumentException(PORT_VARIABLE + " is not a port number");
            }
        }
        String root = env.get(CONFIG_ROOT_VARIABLE).strip();
        // B2: "/" is no envelope, and neither is a path that climbs out of one.
        if (!TargetConnectionValidator.isConfigRoot(root)) {
            throw new IllegalArgumentException(CONFIG_ROOT_VARIABLE + " must be an absolute directory below the "
                    + "filesystem root (not '/'), with no '..' segment: it is the envelope every declare for this "
                    + "target is confined to");
        }
        String prefix = env.get(CONTAINER_PREFIX_VARIABLE).strip();
        require(TargetConnectionValidator.isContainerPrefix(prefix), CONTAINER_PREFIX_VARIABLE,
                "a non-empty container name prefix (letters, digits, '_', '.', '-')");
        return new TargetConnectionSpec(host, port, sshUser, fingerprint, root, prefix);
    }

    private static void require(boolean valid, String variable, String expected) {
        if (!valid) {
            throw new IllegalArgumentException(variable + " is not " + expected);
        }
    }

    private static UUID uuid(Map<String, String> env, String name) {
        try {
            return UUID.fromString(env.get(name).strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not a valid UUID");
        }
    }

    /**
     * Reads the key file into a {@code char[]} and zeroises the intermediate bytes. {@code null} for anything that is
     * not a readable, non-empty, in-bounds, valid-UTF-8 file: the caller says so without echoing the path or why.
     */
    static char[] readKeyFile(String path) {
        byte[] bytes = null;
        ByteBuffer wrapped = null;
        CharBuffer decoded = null;
        try {
            bytes = Files.readAllBytes(Path.of(path));
            if (bytes.length == 0 || bytes.length > EnvelopeSecretStore.MAX_VALUE_BYTES) {
                return null;
            }
            wrapped = ByteBuffer.wrap(bytes);
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(wrapped);
            char[] key = new char[decoded.remaining()];
            decoded.get(key);
            return key;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
            if (decoded != null && decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

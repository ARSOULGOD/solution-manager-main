package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.push.BrokerConnectionRecord;
import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.EnvironmentMasterKeyProvider;
import com.solace.sm.dp.secret.KekCanaryCheck;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import com.solace.sm.dp.secret.SecretPurpose;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The data-plane half of broker-connection registration (plan 011 P11-5.1; {@code OR-107} OQ-3 (a)): takes the
 * organization and environment, the broker coordinates and a credential <b>file path</b>, stores the credential through the
 * secret store (purpose {@code BROKER_CREDENTIAL}, by reference; {@code BE-D43}) and writes
 * {@code sm_data.broker_connection}. One live connection per (organization, environment): every instance of the environment
 * that declares a push source uses it, with no declare-body field and no new verb.
 *
 * <p><b>The {@link TargetConnectionMain} shape, copied rather than shared</b> (the two commands will diverge: a target has a
 * host key and an envelope, a broker connection has a VPN): a standalone {@code main}, the DP runtime credential and the
 * master key from the environment, collaborators constructed by hand and <b>no Spring context ever started</b>, so it can never
 * start a second {@code ApplyWorker} or recovery sweep against the live database. It lives here because JDBC is confined to
 * this package.
 *
 * <p><b>Its authority is the DP runtime credential on the host.</b> It cannot call control-plane authorization ({@code I7}) and
 * needs none: a connection row is inert until an instance of that environment declares a {@code solace_event} metric.
 *
 * <p><b>The credential</b> is read once from the named file into a {@code char[]} (the file's trailing line break is not part
 * of it), handed to the store and zeroised in a {@code finally}; the file's bytes are zeroised as soon as they are decoded.
 * <b>It is never echoed</b>, nor the file's path, the database password or the master key: not on stdout or stderr, and not in
 * a message or a cause that escapes. A failure prints a classifier (a variable name, a constraint name, a class name), never a
 * driver message, which may carry the URL or the role. The master key's canary must verify first, so a mistyped master key
 * cannot seal a credential nobody can open again.
 *
 * <p><b>Idempotent on the connection id</b>, which defaults to one derived from (organization, environment) so a re-run
 * names the same connection: an identical re-run writes nothing and stores no second credential; a differing one is refused;
 * a second LIVE connection for the same environment is refused by the {@code broker_connection_live_once} index, in the same
 * transaction as the credential, so a refused row strands no secret.
 *
 * <p><b>One live connection per broker and message VPN across the whole install</b> (OR-112 (2); {@code BE-D«next-or112b»}):
 * a second live connection to the same (host, port, VPN), from any environment or organization, is refused in this command's
 * own sentence before anything is sealed. The read behind it ({@link JdbcBrokerConnectionRepository#liveAtBroker}) is
 * <b>this install-operator command's own</b>, deliberately install-wide and package-private; it is not application runtime and
 * has no route from a request or a tick. The {@code broker_connection_live_broker_once} index (V019) is the database-level
 * guarantee for a race or a row written any other way.
 */
public final class BrokerConnectionMain {

    /** {@code jdbc:postgresql://…} — the SM database. Credentials do NOT belong in it. */
    public static final String URL_VARIABLE = "SM_DATA_RUNTIME_URL";
    /** The DP runtime role, normally {@code sm_dp_runtime}. */
    public static final String RUNTIME_USERNAME_VARIABLE = "SM_DATA_RUNTIME_USERNAME";
    /** That role's secret. Read, used, and never echoed. */
    public static final String PASSWORD_VARIABLE = "SM_DATA_RUNTIME_PASSWORD";

    /** The organization (tenant) the connection belongs to: the install operator's own knowledge, not a request field. */
    public static final String TENANT_VARIABLE = "SM_BROKER_TENANT_ID";
    /** The environment whose instances use this broker. */
    public static final String ENVIRONMENT_VARIABLE = "SM_BROKER_ENVIRONMENT_ID";
    /** Optional: the connection id. Defaults to {@link #defaultConnectionId}. */
    public static final String CONNECTION_ID_VARIABLE = "SM_BROKER_CONNECTION_ID";
    /** One of {@code tcp}, {@code tcps}, {@code ws}, {@code wss}. */
    public static final String SCHEME_VARIABLE = "SM_BROKER_SCHEME";
    public static final String HOST_VARIABLE = "SM_BROKER_HOST";
    /** The broker's SMF port; required (no default is asserted from memory, I2). */
    public static final String PORT_VARIABLE = "SM_BROKER_PORT";
    public static final String VPN_VARIABLE = "SM_BROKER_VPN";
    /** The broker client username the Data Plane subscribes as. */
    public static final String USERNAME_VARIABLE = "SM_BROKER_USERNAME";
    /** The path of the file holding the broker credential. The path is read; the file's content never leaves this process. */
    public static final String CREDENTIAL_FILE_VARIABLE = "SM_BROKER_CREDENTIAL_FILE";
    /** Optional: the registering principal's id, for {@code created_by}. Defaults to {@link #OPERATOR_ACTOR}. */
    public static final String ACTOR_VARIABLE = "SM_BROKER_ACTOR_ID";

    /** {@code created_by} when no principal is named: this command acts on nobody's behalf (it holds the runtime credential, not a login). */
    public static final UUID OPERATOR_ACTOR = new UUID(0L, 2L);

    /** The registration failed (a database error, a different record already registered, or the master key does not match). */
    static final int EXIT_FAILED = 1;
    /** A variable is missing or malformed. */
    static final int EXIT_MISSING_VARIABLE = 2;

    // The field grammars copy V017's CHECKs: two enforcers, the standing pattern.
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$");
    private static final Pattern VPN = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final Pattern BROKER_USER = Pattern.compile("^[A-Za-z0-9_.@:-]{1,255}$");
    private static final Pattern SCHEME = Pattern.compile("^(tcp|tcps|ws|wss)$");

    /** A refusal whose message is this class's own sentence, which names no value: the only message {@link #exitCode} prints. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    private BrokerConnectionMain() {
    }

    /** @param args ignored: configuration arrives through the environment, never the command line */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), BrokerConnectionMain::readCredentialFile, System.out, System.err));
    }

    /** The id used when none is named: one connection per (organization, environment), so a re-run is the same connection. */
    public static UUID defaultConnectionId(UUID tenantId, UUID environmentId) {
        return UUID.nameUUIDFromBytes(("sm-broker-connection:" + tenantId + ":" + environmentId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** {@link #main} minus {@link System#exit}, with the streams and the credential reader injected. */
    static int exitCode(Map<String, String> env, Function<String, char[]> credentialReader, PrintStream out,
            PrintStream err) {
        for (String name : new String[] {URL_VARIABLE, RUNTIME_USERNAME_VARIABLE, PASSWORD_VARIABLE, TENANT_VARIABLE,
                ENVIRONMENT_VARIABLE, SCHEME_VARIABLE, HOST_VARIABLE, PORT_VARIABLE, VPN_VARIABLE, USERNAME_VARIABLE,
                CREDENTIAL_FILE_VARIABLE}) {
            if (isBlank(env.get(name))) {
                err.println(name + " is not set: the broker connection registration reads its configuration from the "
                        + "environment, and will not guess it");
                return EXIT_MISSING_VARIABLE;
            }
        }
        try {
            JdbcUrls.requireLocation(env.get(URL_VARIABLE));
        } catch (IllegalArgumentException e) {
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage() + ". The credential belongs in "
                    + RUNTIME_USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }
        BrokerConnectionRecord spec;
        UUID actor;
        try {
            UUID tenant = uuid(env, TENANT_VARIABLE);
            UUID environment = uuid(env, ENVIRONMENT_VARIABLE);
            UUID id = isBlank(env.get(CONNECTION_ID_VARIABLE)) ? defaultConnectionId(tenant, environment)
                    : uuid(env, CONNECTION_ID_VARIABLE);
            actor = isBlank(env.get(ACTOR_VARIABLE)) ? OPERATOR_ACTOR : uuid(env, ACTOR_VARIABLE);
            spec = spec(env, tenant, environment, id, actor);
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

        char[] credential = credentialReader.apply(env.get(CREDENTIAL_FILE_VARIABLE));
        if (credential == null) {
            err.println(CREDENTIAL_FILE_VARIABLE + " does not name a readable credential file (UTF-8 text, 1 to "
                    + EnvelopeSecretStore.MAX_VALUE_BYTES + " bytes)");
            return EXIT_MISSING_VARIABLE;
        }
        try {
            boolean recorded = register(env.get(URL_VARIABLE), env.get(RUNTIME_USERNAME_VARIABLE), env.get(PASSWORD_VARIABLE),
                    ring, spec, credential);
            out.println(recorded ? "broker connection recorded: " + spec.brokerConnectionId()
                    : "broker connection " + spec.brokerConnectionId() + " is already registered with this record; "
                            + "credential unchanged (this command never replaces a stored credential)");
            return 0;
        } catch (Refused e) {
            // register's own sentences only, each naming no value
            err.println("the broker connection was not recorded: " + e.getMessage());
            return EXIT_FAILED;
        } catch (PgConstraintViolation e) {
            // a constraint NAME is an identifier (e.g. broker_connection_live_once), never a row's text
            err.println("the broker connection was not recorded: the database refused it (constraint "
                    + e.constraint() + ")");
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            // classifiers only: a driver message may carry the URL or the role
            err.println("the broker connection registration failed: " + e.getClass().getSimpleName());
            return EXIT_FAILED;
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    /**
     * Writes the connection record and its sealed credential in one transaction. The caller zeroises {@code credential}.
     *
     * @return true when this call recorded the connection; false when an identical record was already registered (nothing
     *         was written, and the stored credential was NOT compared with {@code credential})
     * @throws Refused the master key does not verify against the install's canary, or a different record (or a retired one)
     *         is already registered under this id; the message is one of this method's own sentences
     */
    public static boolean register(String url, String username, String password, MasterKeyRing ring,
            BrokerConnectionRecord spec, char[] credential) {
        DataSource dataSource = SmDataMigrationMain.dataSource(url, username, password);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Envelope envelope = Envelope.standard();

        // Read-only: a mistyped master key would seal a credential that nobody could ever open again.
        MasterKey current = ring.current();
        if (!KekCanaryCheck.canaryVerifies(envelope, current, new JdbcKekCanaryRepository(jdbc).find(current.id()))) {
            throw new Refused("the master key does not match this install's canary: start the "
                    + "application once with the correct key first, and check "
                    + EnvironmentMasterKeyProvider.KEY + " and " + EnvironmentMasterKeyProvider.KEY_ID);
        }
        EnvelopeSecretStore secrets = new EnvelopeSecretStore(new JdbcSecretRepository(jdbc), ring, envelope,
                Clock.systemUTC());
        JdbcBrokerConnectionRepository connections = new JdbcBrokerConnectionRepository(jdbc);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Optional<BrokerConnectionRecord> existing = connections.find(spec.tenantId(), spec.brokerConnectionId());
            if (existing.isPresent()) {
                if (existing.get().retiredAt() != null) {
                    throw new Refused("this connection is retired and cannot be registered again");
                }
                if (!existing.get().sameConnectionAs(spec)) {
                    throw new Refused("a different connection record is already registered under this id");
                }
                return false;
            }
            // OR-112 (2): one live connection per broker and message VPN across the whole install. After the id check, so an
            // identical re-run is still idempotent (its own live row would otherwise match); before the credential is sealed,
            // so a refusal strands nothing. The index broker_connection_live_broker_once (V019) is the guarantee under a race.
            if (connections.liveAtBroker(spec.host(), spec.port(), spec.vpn())) {
                throw new Refused("another live connection already uses this broker and message VPN (one live connection "
                        + "per broker and message VPN across the install; adapter ids are unique only within one VPN): "
                        + "retire that connection first, or point this one at a different broker or VPN");
            }
            UUID reference = secrets.put(spec.tenantId(), SecretPurpose.BROKER_CREDENTIAL, credential, spec.createdBy());
            connections.insert(new BrokerConnectionRecord(spec.tenantId(), spec.environmentId(),
                    spec.brokerConnectionId(), spec.scheme(), spec.host(), spec.port(), spec.vpn(),
                    spec.clientUsername(), reference, spec.createdBy(), Clock.systemUTC().instant(), null));
            return true;
        }));
    }

    private static BrokerConnectionRecord spec(Map<String, String> env, UUID tenant, UUID environment, UUID id,
            UUID actor) {
        String scheme = env.get(SCHEME_VARIABLE).strip();
        require(SCHEME, scheme, SCHEME_VARIABLE, "one of tcp, tcps, ws, wss");
        String host = env.get(HOST_VARIABLE).strip();
        require(HOST, host, HOST_VARIABLE, "a host name or address (no scheme, no port)");
        int port;
        try {
            port = Integer.parseInt(env.get(PORT_VARIABLE).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PORT_VARIABLE + " is not a port number");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(PORT_VARIABLE + " is not a port number");
        }
        String vpn = env.get(VPN_VARIABLE).strip();
        require(VPN, vpn, VPN_VARIABLE, "a message VPN name (letters, digits, '_', '.', '-')");
        String user = env.get(USERNAME_VARIABLE).strip();
        require(BROKER_USER, user, USERNAME_VARIABLE, "a client username (no spaces)");
        // the credential reference is a placeholder here: register() replaces it with the stored secret's id
        return new BrokerConnectionRecord(tenant, environment, id, scheme, host, port, vpn, user, new UUID(0L, 0L),
                actor, null, null);
    }

    private static void require(Pattern grammar, String value, String variable, String expected) {
        if (!grammar.matcher(value).matches()) {
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
     * Reads the credential file into a {@code char[]} and zeroises the intermediate bytes. A trailing line break (what an
     * editor or {@code echo} adds) is not part of the credential. {@code null} for anything that is not a readable, non-empty,
     * in-bounds, valid-UTF-8 file: the caller says so without echoing the path or why.
     */
    static char[] readCredentialFile(String path) {
        byte[] bytes = null;
        ByteBuffer wrapped = null;
        CharBuffer decoded = null;
        try {
            bytes = Files.readAllBytes(Path.of(path));
            int length = bytes.length;
            while (length > 0 && (bytes[length - 1] == '\n' || bytes[length - 1] == '\r')) {
                length--;
            }
            if (length == 0 || length > EnvelopeSecretStore.MAX_VALUE_BYTES) {
                return null;
            }
            wrapped = ByteBuffer.wrap(bytes, 0, length);
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(wrapped);
            char[] credential = new char[decoded.remaining()];
            decoded.get(credential);
            return credential;
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

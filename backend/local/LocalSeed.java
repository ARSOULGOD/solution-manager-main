import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.identity.CredentialHasher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper for backend/local/sm-local.sh (OR-118, plan 013 T4.1). Run as a single-file program against the launcher's classpath:
 * {@code java -cp <launcher classpath> LocalSeed.java <command>}.
 *
 * <p>Since plan 013 the local stack needs <b>no SQL</b> beyond the database provisioning the script itself does (Phase 0 and the two
 * migration steps). Everything that used to be seeded as the database owner now goes through the public API, as the
 * bootstrap administrator, exactly as an operator would from Postman (the collection's {@code Setup} folder is the same walk):
 * <ol>
 *   <li>sign in and rotate the single-use bootstrap credential ({@code rotateOwnCredential});</li>
 *   <li>name the install's organization ({@code renameOrganization}, install scope), create the sub-organizations and
 *       environments ({@code createSubOrganization}, {@code createEnvironment});</li>
 *   <li>entitle the organization to the Cache ({@code setEntitlement});</li>
 *   <li>create the environment-bound Operator ({@code createUser}, {@code grantAssignment}) and rotate its provisional credential
 *       to the one it keeps;</li>
 *   <li>(command {@code register-target}) scan the target host, register it with its SSH key and test it
 *       ({@code scanHostKeys}, {@code registerTarget}, {@code testTargetConnection}).</li>
 * </ol>
 * Every call is replay-safe (a replay answers {@code 200}, or the object is looked up first), so a failed run is simply run again.
 *
 * <p>{@code hash} remains the one local helper that uses the application's own hasher: the migration step needs the bootstrap
 * credential's Argon2id hash, and there is no API before the first migration has run.
 *
 * <p>Everything arrives through the environment. Standard output carries only {@code NAME=value} lines the script reads (ids and
 * progress markers, never a credential); standard error carries the progress text. Commands: {@code hash} | {@code seed} |
 * {@code register-target}.
 *
 * <p>Environment for {@code seed}: {@code SM_LOCAL_BASE_URL} (for example {@code http://127.0.0.1:8080/api}),
 * {@code SM_LOCAL_ORIGIN} (a trusted origin), {@code SM_LOCAL_ADMIN_USER}, {@code SM_LOCAL_ADMIN_NEW_PASSWORD},
 * {@code SM_LOCAL_ADMIN_INITIAL_PASSWORD} (needed only while {@code SM_LOCAL_ADMIN_ROTATED} is unset),
 * {@code SM_LOCAL_OPERATOR_USER}, {@code SM_LOCAL_OPERATOR_PASSWORD}. {@code register-target} adds the ids {@code seed} printed and
 * the {@code SM_LOCAL_TARGET_*} values of the target file.
 */
public final class LocalSeed {

    static final String ORGANIZATION_NAME = "ACME";
    static final String[] SUB_ORGANIZATIONS = {"ACME-Sub-1", "ACME-Sub-2"};
    static final String[] ENVIRONMENTS = {"Dev", "UAT", "Prod"};
    static final String SOLUTION = "cache";
    static final int MAX_INSTANCES = 5;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION_COOKIE = "__Host-SM_SESSION";
    private static final String CSRF_COOKIE = "__Host-SM_CSRF";

    private LocalSeed() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "" : args[0];
        try {
            switch (command) {
                case "hash" -> System.out.println(new CredentialHasher().hash(env("SM_LOCAL_PW").toCharArray()));
                case "seed" -> seed();
                case "register-target" -> registerTarget();
                default -> {
                    System.err.println("usage: LocalSeed hash | seed | register-target");
                    System.exit(2);
                }
            }
        } catch (ApiException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    // ---- seed: the hierarchy, the entitlement and the Operator, all through the API -----------------------

    static void seed() throws Exception {
        Api admin = signInAdministrator();

        JsonNode organizations = admin.call("GET", "/cp/hierarchy/organizations", null, 200).path("items");
        if (organizations.size() != 1) {
            throw new ApiException("the administrator sees " + organizations.size()
                    + " organizations; this stack expects exactly one (the install's)");
        }
        String organization = organizations.get(0).get("id").asText();
        if (!ORGANIZATION_NAME.equals(organizations.get(0).get("name").asText())) {
            say("naming the install's organization " + ORGANIZATION_NAME + " (renameOrganization)");
            admin.call("PATCH", "/cp/hierarchy/organizations/" + organization, Map.of("name", ORGANIZATION_NAME), 200);
        }
        String unit = null;
        String environment = null;
        for (String subName : SUB_ORGANIZATIONS) {
            String subId = admin.call("POST", "/cp/hierarchy/organizations/" + organization + "/sub-orgs",
                    Map.of("name", subName), 200, 201).get("id").asText();
            for (String envName : ENVIRONMENTS) {
                String envId = admin.call("POST", "/cp/hierarchy/organizations/" + organization + "/sub-orgs/" + subId
                        + "/environments", Map.of("name", envName), 200, 201).get("id").asText();
                if (unit == null) {
                    // The first sub-organization's first environment (ACME-Sub-1 / Dev) is where the Operator is bound and
                    // the target is owned.
                    unit = subId;
                    environment = envId;
                }
            }
        }
        say("hierarchy: " + ORGANIZATION_NAME + " > " + String.join(", ", SUB_ORGANIZATIONS) + " > " + String.join(", ", ENVIRONMENTS));
        System.out.println("SM_LOCAL_ORGANIZATION_ID=" + organization);
        System.out.println("SM_LOCAL_UNIT_ID=" + unit);
        System.out.println("SM_LOCAL_ENVIRONMENT_ID=" + environment);

        say("entitlement: '" + SOLUTION + "', up to " + MAX_INSTANCES + " instances (setEntitlement)");
        admin.call("PUT", "/cp/entitlements/" + SOLUTION, Map.of("maxInstances", MAX_INSTANCES), 200);

        seedOperator(admin, unit, environment);
        System.out.println("SM_LOCAL_SEED_DONE=1");
        admin.logout();
    }

    /**
     * The environment-bound Operator. createUser makes it with a provisional credential that only this process knows; a user that
     * exists already (a re-run after a failure) gets a fresh provisional one through resetUserCredential. The Operator then signs
     * in with it and rotates it to the one it keeps, so it ends as it was before the API did the seeding: usable at once.
     */
    static void seedOperator(Api admin, String unit, String environment) throws Exception {
        String username = env("SM_LOCAL_OPERATOR_USER");
        String finalCredential = env("SM_LOCAL_OPERATOR_PASSWORD");
        String provisional = randomCredential();
        String userId = null;
        for (JsonNode user : admin.call("GET", "/cp/admin/users", null, 200).path("items")) {
            if (user.get("username").asText().equalsIgnoreCase(username)) {
                userId = user.get("id").asText();
            }
        }
        if (userId == null) {
            say("creating the Operator '" + username + "' (createUser)");
            userId = admin.call("POST", "/cp/admin/users",
                    Map.of("username", username, "initialCredential", provisional), 201).get("id").asText();
        } else {
            say("the Operator '" + username + "' exists: setting a provisional credential (resetUserCredential)");
            admin.call("PUT", "/cp/admin/users/" + userId + "/credential", Map.of("resetCredential", provisional), 200);
        }
        String operatorRole = null;
        for (JsonNode role : admin.call("GET", "/cp/admin/roles", null, 200).path("items")) {
            if ("Operator".equals(role.get("name").asText())) {
                operatorRole = role.get("id").asText();
            }
        }
        if (operatorRole == null) {
            throw new ApiException("listRoles returned no Operator role");
        }
        say("binding the Operator at " + SUB_ORGANIZATIONS[0] + " / " + ENVIRONMENTS[0] + " (grantAssignment)");
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("principalId", userId);
        grant.put("roleId", operatorRole);
        grant.put("scope", Map.of("subOrganizationId", unit, "environmentId", environment));
        admin.call("POST", "/cp/admin/assignments", grant, 200, 201);

        Api operator = new Api();
        operator.login(username, provisional);
        operator.rotate(provisional, finalCredential);
        operator.logout();
        System.out.println("SM_LOCAL_OPERATOR_DONE=1");
    }

    // ---- register-target: scan, register, test -----------------------------------------------------------

    static void registerTarget() throws Exception {
        String unit = env("SM_LOCAL_UNIT_ID");
        String environment = env("SM_LOCAL_ENVIRONMENT_ID");
        String host = env("SM_LOCAL_TARGET_HOST");
        int port = Integer.parseInt(envOr("SM_LOCAL_TARGET_PORT", "22"));
        String fingerprint = env("SM_LOCAL_TARGET_FINGERPRINT");
        String keyText = new String(Files.readAllBytes(Path.of(env("SM_LOCAL_TARGET_KEY_FILE"))), StandardCharsets.UTF_8);
        Map<String, String> scope = Map.of("subOrganizationId", unit, "environmentId", environment);

        Api admin = signInAdministrator();
        say("scanning the target host for its SSH host keys (scanHostKeys; nothing is pinned by this call)");
        Map<String, Object> scanRequest = new LinkedHashMap<>();
        scanRequest.put("host", host);
        scanRequest.put("port", port);
        scanRequest.put("ownershipScope", scope);
        JsonNode offered = admin.call("POST", "/dp/host-key-scans", scanRequest, 200).path("offered");
        boolean pinOffered = false;
        List<String> algorithms = new ArrayList<>();
        for (JsonNode key : offered) {
            algorithms.add(key.get("algorithm").asText());
            pinOffered |= fingerprint.equals(key.get("fingerprint").asText());
        }
        if (!pinOffered) {
            throw new ApiException("the host offers " + algorithms + " but none has the fingerprint in SM_LOCAL_TARGET_FINGERPRINT;"
                    + " nothing was registered. Confirm the fingerprint on the host (ssh-keygen -lf) and fix the target file");
        }

        say("registering the SSH-Docker target (registerTarget: authorized, pre-checked, then written to both planes)");
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("host", host);
        connection.put("port", port);
        connection.put("sshUser", env("SM_LOCAL_TARGET_USER"));
        connection.put("hostKeyFingerprint", fingerprint);
        connection.put("allowedConfigRoot", env("SM_LOCAL_TARGET_CONFIG_ROOT"));
        connection.put("allowedContainerPrefix", env("SM_LOCAL_TARGET_CONTAINER_PREFIX"));
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("name", envOr("SM_LOCAL_TARGET_NAME", "local-e2e-host"));
        registration.put("kind", "docker");
        registration.put("transport", "ssh-docker");
        registration.put("ownershipScope", scope);
        registration.put("connection", connection);
        registration.put("privateKey", keyText);
        JsonNode target = admin.call("POST", "/cp/targets", registration, 200, 201);
        String targetId = target.get("targetId").asText();
        System.out.println("SM_LOCAL_TARGET_ID=" + targetId);

        // A failed test is a result, not an error: report the furthest step reached and carry on.
        JsonNode test = admin.call("POST", "/dp/targets/" + targetId + "/connection/test", null, 200);
        say("testTargetConnection: hostKeyPinned=" + test.path("hostKeyPinned").asBoolean() + ", authenticated="
                + test.path("authenticated").asBoolean() + ", docker.available=" + test.path("docker").path("available").asBoolean());
        admin.logout();
    }

    // ---- the administrator's session --------------------------------------------------------------------

    /**
     * Signs in as the bootstrap administrator. While it has not been rotated (SM_LOCAL_ADMIN_ROTATED unset) the single-use
     * initial credential is used and then replaced by SM_LOCAL_ADMIN_NEW_PASSWORD; the line {@code SM_LOCAL_ADMIN_ROTATED=1}
     * is printed the moment the rotation succeeds, before anything else can fail. A run that rotated but never told the script
     * (it was killed in between) is recovered: if the initial credential is refused, the new one is tried.
     */
    static Api signInAdministrator() throws Exception {
        String user = envOr("SM_LOCAL_ADMIN_USER", "bootstrap-admin");
        String next = env("SM_LOCAL_ADMIN_NEW_PASSWORD");
        Api api = new Api();
        if (System.getenv("SM_LOCAL_ADMIN_ROTATED") != null) {
            api.login(user, next);
            return api;
        }
        String initial = env("SM_LOCAL_ADMIN_INITIAL_PASSWORD");
        if (!api.loginOrNull(user, initial)) {
            say("the initial credential is refused; trying the one this script would have rotated it to");
            api.login(user, next);
            System.out.println("SM_LOCAL_ADMIN_ROTATED=1");
            return api;
        }
        if (!api.mustRotate()) {
            throw new ApiException("'" + user + "' signed in with the initial credential and is not marked for rotation, so"
                    + " the new credential cannot be installed; this script only starts from the single-use bootstrap state");
        }
        say("rotating the bootstrap administrator's single-use credential (rotateOwnCredential)");
        api.rotate(initial, next);
        System.out.println("SM_LOCAL_ADMIN_ROTATED=1");
        return api;
    }

    // ---- a minimal client: one session, cookies and the CSRF header by hand ------------------------------

    /**
     * One signed-in (or signing-in) session. The session cookies are {@code __Host-} prefixed and {@code Secure}, and the JDK's
     * cookie manager will not send a {@code Secure} cookie over {@code http}, so the two values are kept and sent by hand, which
     * is what the Postman collection's {@code cookieMode=manual} does.
     */
    static final class Api {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        private final String base = env("SM_LOCAL_BASE_URL");
        private final String origin = env("SM_LOCAL_ORIGIN");
        private String session;
        private String csrfCookie;
        private String csrfToken;
        private boolean mustRotate;

        void login(String username, String credential) throws Exception {
            if (!loginOrNull(username, credential)) {
                throw new ApiException("sign-in as '" + username + "' was refused (401): wrong credential");
            }
        }

        /** @return false on 401 (the credential is wrong), true on 200; any other status is an error */
        boolean loginOrNull(String username, String credential) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("credential", credential);
            // The organization is optional on an install holding exactly one (OR-131 Q3), and this stack holds one.
            Response r = send("POST", "/cp/auth/login", body);
            if (r.status == 401) {
                return false;
            }
            if (r.status != 200) {
                throw r.error("login");
            }
            session = null;
            csrfCookie = null;
            for (String header : r.setCookies) {
                String pair = header.split(";", 2)[0];
                if (pair.startsWith(SESSION_COOKIE + "=")) {
                    session = pair;
                } else if (pair.startsWith(CSRF_COOKIE + "=")) {
                    csrfCookie = pair;
                }
            }
            JsonNode identity = r.json();
            csrfToken = identity.path("csrfToken").asText(null);
            mustRotate = identity.path("mustRotate").asBoolean();
            if (session == null || csrfToken == null) {
                throw new ApiException("login answered 200 without a session cookie and a CSRF token");
            }
            return true;
        }

        boolean mustRotate() {
            return mustRotate;
        }

        void rotate(String current, String next) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("currentCredential", current);
            body.put("newCredential", next);
            call("POST", "/cp/auth/credential", body, 204);
            mustRotate = false;
            // The session survives its own rotation; read the identity once more so the CSRF token is the current one.
            csrfToken = call("GET", "/cp/auth/identity", null, 200).path("csrfToken").asText(csrfToken);
        }

        void logout() throws Exception {
            send("POST", "/cp/auth/logout", null);
        }

        /** Sends a request and requires one of the given statuses; an empty body is a missing node. */
        JsonNode call(String method, String path, Object body, int... accepted) throws Exception {
            Response r = send(method, path, body);
            for (int status : accepted) {
                if (r.status == status) {
                    return r.body.isBlank() ? JSON.missingNode() : r.json();
                }
            }
            throw r.error(method + " " + path);
        }

        private Response send(String method, String path, Object body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120));
            if (!method.equals("GET")) {
                b.header("Origin", origin);
                if (csrfToken != null) {
                    b.header("X-SM-CSRF", csrfToken);
                }
            }
            if (session != null) {
                b.header("Cookie", csrfCookie == null ? session : session + "; " + csrfCookie);
            }
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body() == null ? "" : response.body(),
                    response.headers().allValues("Set-Cookie"));
        }
    }

    static final class Response {
        final int status;
        final String body;
        final List<String> setCookies;

        Response(int status, String body, List<String> setCookies) {
            this.status = status;
            this.body = body;
            this.setCookies = setCookies;
        }

        JsonNode json() throws Exception {
            return JSON.readTree(body);
        }

        /** A problem body carries a code, a correlation id and a detail; none holds a credential, a key or a hash. */
        ApiException error(String what) {
            String problem = "";
            try {
                JsonNode p = json();
                problem = " " + p.path("code").asText("") + " correlationId=" + p.path("correlationId").asText("")
                        + (p.hasNonNull("detail") ? " (" + p.get("detail").asText() + ")" : "");
            } catch (Exception ignored) {
                // not a problem document
            }
            return new ApiException(what + " answered " + status + problem);
        }
    }

    static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ApiException(String message) {
            super(message);
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------

    static void say(String text) {
        System.err.println("    " + text);
    }

    static String randomCredential() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println(name + " is not set");
            System.exit(2);
        }
        return value;
    }

    static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

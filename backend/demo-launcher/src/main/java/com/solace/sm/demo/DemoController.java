package com.solace.sm.demo;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.solace.sm.dp.apply.runtime.RuntimeDiagnostics;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The narrow local-demo HTTP surface. It never accepts SSH material or arbitrary remote commands. */
@RestController
@RequestMapping("/api/cp")
final class DemoController {
    private final DemoSessionService sessions;
    private final DemoStore store;
    private final DemoInstanceService instances;
    private final DemoKeyVault keys;
    private final DemoHostKeyProbe hostKeys;

    DemoController(DemoSessionService sessions, DemoStore store, DemoInstanceService instances, DemoKeyVault keys,
            DemoHostKeyProbe hostKeys) {
        this.sessions = sessions;
        this.store = store;
        this.instances = instances;
        this.keys = keys;
        this.hostKeys = hostKeys;
    }

    @PostMapping("/auth/login")
    ResponseEntity<Identity> login(@RequestBody LoginRequest request) {
        DemoSessionService.Login login = sessions.initialized()
                ? sessions.login(request.password())
                : sessions.setup(request.password());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, login.session().toString())
                .header(HttpHeaders.SET_COOKIE, login.csrf().toString())
                .body(identity(request.username()));
    }

    @GetMapping("/setup-status")
    SetupStatus setupStatus() { return new SetupStatus(sessions.initialized()); }

    @PostMapping("/setup")
    ResponseEntity<Identity> setup(@RequestBody LoginRequest request) {
        DemoSessionService.Login login = sessions.setup(request.password());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, login.session().toString())
                .header(HttpHeaders.SET_COOKIE, login.csrf().toString())
                .body(identity(request.username()));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        ResponseCookie cookie = sessions.logout(cookie(request, DemoSessionService.SESSION_COOKIE));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    @GetMapping("/auth/identity")
    Identity identity(HttpServletRequest request) {
        require(request, false);
        return identity("bootstrap-super-admin");
    }

    @PostMapping(path = "/packages", consumes = "multipart/form-data")
    DemoStore.PackageSummary upload(@RequestPart("file") MultipartFile file, HttpServletRequest request)
            throws IOException {
        require(request, true);
        if (file.isEmpty()) {
            throw new DemoBadRequestException("Choose a package file to upload.");
        }
        return store.save(file.getBytes());
    }

    @PostMapping("/solutions/load")
    PackageLoadResult stagePackage(@RequestBody PackageLoadRequest input, HttpServletRequest request)
            throws IOException {
        require(request, true);
        if (input.packageJson() == null || input.packageJson().isBlank()) {
            throw new DemoBadRequestException("Choose a package file to upload.");
        }
        store.stage(sessionId(request), input.packageJson().getBytes(StandardCharsets.UTF_8));
        return new PackageLoadResult(false, true, "Package validated. Confirm Load to create the Cache Solution.");
    }

    @PostMapping("/solutions/load/confirm")
    ResponseEntity<Void> confirmPackage(HttpServletRequest request) throws IOException {
        require(request, true);
        store.confirm(sessionId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/solutions/load/discard")
    ResponseEntity<Void> discardPackage(HttpServletRequest request) {
        require(request, true);
        store.discard(sessionId(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ssh-keys")
    KeyReference uploadKey(@RequestBody KeyUploadRequest input, HttpServletRequest request) throws IOException {
        require(request, true);
        if (input.reference() == null || !input.reference().matches("key-[A-Za-z0-9-]{1,80}")
                || input.pem() == null || input.pem().isBlank()) {
            throw new DemoBadRequestException("Provide a valid SSH key file.");
        }
        keys.put(input.reference(), input.pem().getBytes(StandardCharsets.UTF_8), vaultKey(request));
        return new KeyReference(input.reference());
    }

    @PostMapping("/ssh-host-key")
    DemoHostKeyProbe.HostKeyFingerprint probeHostKey(@RequestBody HostKeyRequest request,
            HttpServletRequest servletRequest) {
        require(servletRequest, true);
        return hostKeys.probe(request.host(), request.port());
    }

    @GetMapping("/solutions")
    List<DemoStore.ConsoleSolution> solutions(HttpServletRequest request) {
        require(request, false);
        return store.catalog();
    }

    @GetMapping("/hierarchy/organizations")
    List<String> organizations(HttpServletRequest request) {
        require(request, false);
        return List.of("Local demo");
    }

    @GetMapping("/hierarchy/organizations/{organization}/sub-orgs")
    List<String> subOrganizations(@PathVariable String organization, HttpServletRequest request) {
        require(request, false);
        return "Local demo".equals(organization) ? List.of("Default") : List.of();
    }

    @GetMapping("/hierarchy/organizations/{organization}/sub-orgs/{subOrganization}/environments")
    List<String> environments(@PathVariable String organization, @PathVariable String subOrganization,
            HttpServletRequest request) {
        require(request, false);
        return "Local demo".equals(organization) && "Default".equals(subOrganization) ? List.of("DEV") : List.of();
    }

    @PostMapping("/instance")
    DemoInstanceService.InstanceView register(@RequestBody InstanceRequest input,
            HttpServletRequest request) throws IOException {
        require(request, true);
        return instances.register(new DemoInstanceService.InstanceDeclaration(input.solutionId(), input.label(), input.host(), input.port(),
                input.user(), input.sshKeyReference(), input.pinnedHostKeyFingerprint(), input.containerName(),
                input.remoteConfigDirectory(), input.containerConfigPath(), input.configFileName()), vaultKey(request));
    }

    @GetMapping("/instance/status")
    DemoInstanceService.InstanceView status(HttpServletRequest request) throws IOException {
        require(request, false);
        return instances.status(vaultKey(request));
    }

    @PostMapping("/instance/restart")
    DemoInstanceService.InstanceView restart(HttpServletRequest request) throws IOException {
        require(request, true);
        return instances.restart(vaultKey(request));
    }

    @PostMapping("/instance/config/ttl")
    DemoInstanceService.InstanceView applyTtl(@RequestBody TtlRequest input, HttpServletRequest request)
            throws IOException {
        require(request, true);
        return instances.applyTtl(input.ttl(), vaultKey(request));
    }

    @GetMapping("/instance/logs")
    RuntimeDiagnostics.DiagnosticLog logs(HttpServletRequest request) throws IOException {
        require(request, false);
        return instances.logs(vaultKey(request));
    }

    private void require(HttpServletRequest request, boolean mutation) {
        sessions.require(cookie(request, DemoSessionService.SESSION_COOKIE),
                request.getHeader("X-SM-CSRF"), mutation);
    }
    private byte[] vaultKey(HttpServletRequest request) {
        return sessions.vaultKey(sessionId(request));
    }
    private String sessionId(HttpServletRequest request) {
        return cookie(request, DemoSessionService.SESSION_COOKIE);
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private static Identity identity(String username) {
        String email = username == null || username.isBlank() ? "bootstrap-super-admin" : username;
        return new Identity("Bootstrap Super Admin", email, "Bootstrap Super Admin", "Local demo", true,
                List.of("solution.load", "instance.declare", "config.apply", "instance.restart"));
    }

    record LoginRequest(String username, String password) {}
    record SetupStatus(boolean initialized) {}
    record KeyReference(String reference) {}
    record KeyUploadRequest(String reference, String pem) {}
    record PackageLoadResult(boolean verified, boolean devMode, String message) {}
    record PackageLoadRequest(String packageJson) {}
    record HostKeyRequest(String host, int port) {}
    record Identity(String name, String email, String role, String organization, boolean superAdmin,
            List<String> permissions) {}
    record InstanceRequest(String solutionId, String label, String host, int port, String user, String sshKeyReference,
            String pinnedHostKeyFingerprint, String containerName, String remoteConfigDirectory,
            String containerConfigPath, String configFileName) {}
    record TtlRequest(String ttl) {}

    @ExceptionHandler(DemoUnauthorizedException.class)
    ResponseEntity<ApiError> unauthorized() { return error(HttpStatus.UNAUTHORIZED, "Authentication is required."); }
    @ExceptionHandler(DemoBadRequestException.class)
    ResponseEntity<ApiError> badRequest(DemoBadRequestException error) { return error(HttpStatus.BAD_REQUEST, error.getMessage()); }
    @ExceptionHandler(DemoNotFoundException.class)
    ResponseEntity<ApiError> notFound() { return error(HttpStatus.NOT_FOUND, "No demo instance is registered."); }
    @ExceptionHandler(DemoUnavailableException.class)
    ResponseEntity<ApiError> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "The configured instance is unavailable."); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "The demo operation could not be completed."); }
    private static ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(message));
    }
    record ApiError(String message) {}
}

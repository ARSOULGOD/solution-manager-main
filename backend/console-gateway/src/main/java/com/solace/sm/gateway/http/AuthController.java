package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.identity.CredentialRotation;
import com.solace.sm.cp.api.identity.EffectivePermissionView;
import com.solace.sm.cp.api.identity.IdentityDirectory;
import com.solace.sm.cp.api.identity.IdentityView;
import com.solace.sm.cp.api.identity.LocalAuthenticationService;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionAuthentication;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The session lifecycle: login, identity, logout, own-credential rotation.
 *
 * <p>Thin by design: depends only on cp-api ports, does no authorization itself (login/logout/
 * identity/rotation are all {@code LocalOperation}s or session-establishing — see
 * {@link OperationPolicy} — so none of them is a CATALOG operation and none is ever routed
 * through {@link AuthenticationInterceptor}'s authorization step), and lets
 * {@link GatewayErrorAdvice} render every failure. A too-short {@code newCredential} never
 * reaches {@link CredentialRotation#rotate} at all: {@link RotateOwnCredentialRequest}'s own
 * compact constructor enforces the bound during JSON deserialization, before this controller is
 * invoked, and that failure is reported as {@code BAD_REQUEST} by the advice's
 * {@code HttpMessageNotReadableException} handler.
 *
 * <p>⚠️ <b>{@code __Host-} cookie names (ruling {@code OR-21}).</b> Both the session and CSRF
 * cookies carry the {@code __Host-} prefix. A browser refuses to store ANY cookie under that name
 * unless the {@code Set-Cookie} that sets it also carries {@code Secure}, {@code Path=/}, and NO
 * {@code Domain} attribute at all — and refuses to honour such a {@code Set-Cookie} from anywhere
 * but the exact host it was received from. A sibling subdomain therefore has no way to make a
 * browser store a same-named cookie here at all — the classic "cookie tossing" plant is not
 * merely detected, it is unreachable. {@link AuthenticationInterceptor} still refuses two
 * presented session cookies outright as defence in depth, but the attack that defence was
 * originally written against is no longer reachable once every client is a browser honouring the
 * {@code __Host-} prefix.
 */
@RestController
@RequestMapping("/api/cp/auth")
public class AuthController {

    private static final String SESSION_COOKIE = "__Host-SM_SESSION";
    private static final String CSRF_COOKIE = "__Host-SM_CSRF";
    // __Host- requires Path=/ on both cookies (OR-21) — there is no longer a separate
    // "/api"-scoped session-cookie path distinct from the CSRF cookie's "/".
    private static final String COOKIE_PATH = "/";

    private final LocalAuthenticationService localAuthenticationService;
    private final IdentityDirectory identityDirectory;
    private final SessionAuthentication sessionAuthentication;
    private final CredentialRotation credentialRotation;
    private final RequestOriginFactory requestOriginFactory;
    private final HierarchyDirectory hierarchyDirectory;

    public AuthController(LocalAuthenticationService localAuthenticationService, IdentityDirectory identityDirectory,
            SessionAuthentication sessionAuthentication, CredentialRotation credentialRotation,
            RequestOriginFactory requestOriginFactory, HierarchyDirectory hierarchyDirectory) {
        this.localAuthenticationService = localAuthenticationService;
        this.identityDirectory = identityDirectory;
        this.sessionAuthentication = sessionAuthentication;
        this.credentialRotation = credentialRotation;
        this.requestOriginFactory = requestOriginFactory;
        this.hierarchyDirectory = hierarchyDirectory;
    }

    @PostMapping("/login")
    @ConsoleOperation("login")
    public ResponseEntity<Identity> login(@RequestBody LoginRequest request) {
        char[] credential = request.credential().toCharArray();
        LocalLoginResult result;
        try {
            result = localAuthenticationService.authenticate(new LocalCredentials(request.organization(), request.username(), credential),
                    requestOriginFactory.create());
        } finally {
            Arrays.fill(credential, '\0');
        }
        IdentityView view = identityDirectory.describe(result.session());
        Identity identity = toIdentity(view, visibleOrganizations(result.session()), result.csrfToken().value());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.sessionToken().value()).toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie(result.csrfToken().value()).toString())
                .body(identity);
    }

    @PostMapping("/logout")
    @ConsoleOperation("logout")
    public ResponseEntity<Void> logout(Session session) {
        sessionAuthentication.revoke(session.id(), requestOriginFactory.create());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString())
                .header(HttpHeaders.SET_COOKIE, expiredCsrfCookie().toString())
                .build();
    }

    @GetMapping("/identity")
    @ConsoleOperation("getIdentity")
    public ResponseEntity<Identity> getIdentity(Session session) {
        IdentityView view = identityDirectory.describe(session);
        CsrfToken csrfToken = sessionAuthentication.csrfTokenFor(session.id())
                .orElseThrow(() -> new GatewayRejectionException(ErrorCode.UNAUTHENTICATED));
        Identity identity = toIdentity(view, visibleOrganizations(session), csrfToken.value());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, csrfCookie(csrfToken.value()).toString())
                .body(identity);
    }

    @PostMapping("/credential")
    @ConsoleOperation("rotateOwnCredential")
    public ResponseEntity<Void> rotateOwnCredential(@RequestBody RotateOwnCredentialRequest request, Session session) {
        char[] current = request.currentCredential().toCharArray();
        char[] replacement = request.newCredential().toCharArray();
        try {
            credentialRotation.rotate(session, current, replacement, requestOriginFactory.create());
        } finally {
            Arrays.fill(current, '\0');
            Arrays.fill(replacement, '\0');
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie sessionCookie(String value) {
        return ResponseCookie.from(SESSION_COOKIE, value)
                .httpOnly(true).sameSite("Strict").path(COOKIE_PATH).secure(true)
                .build();
    }

    private ResponseCookie csrfCookie(String value) {
        return ResponseCookie.from(CSRF_COOKIE, value)
                .httpOnly(false).sameSite("Strict").path(COOKIE_PATH).secure(true)
                .build();
    }

    private ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true).sameSite("Strict").path(COOKIE_PATH).secure(true)
                .maxAge(0).build();
    }

    private ResponseCookie expiredCsrfCookie() {
        return ResponseCookie.from(CSRF_COOKIE, "")
                .httpOnly(false).sameSite("Strict").path(COOKIE_PATH).secure(true)
                .maxAge(0).build();
    }

    /**
     * {@code OR-116}: the caller's visible organizations and sub-organizations, computed by the
     * SAME {@link HierarchyDirectory} {@code listOrganizations} / {@code listSubOrganizations} use —
     * never a second rule. Populated only when those lists would answer: they answer {@code 428}
     * until the credential is rotated, so for a {@code mustRotate} session this is empty and the
     * directory is not consulted at all.
     */
    private List<VisibleOrganization> visibleOrganizations(Session session) {
        if (session.mustRotate()) {
            return List.of();
        }
        return hierarchyDirectory.visibleOrganizations(session).stream()
                .map(organization -> new VisibleOrganization(organization.organization().id(),
                        organization.organization().name(),
                        organization.subOrganizations().stream()
                                .map(sub -> new ScopeNode(sub.id(), sub.name())).toList()))
                .toList();
    }

    private static Identity toIdentity(IdentityView view, List<VisibleOrganization> organizations, String csrfToken) {
        List<EffectivePermission> permissions = view.permissions().stream()
                .map(AuthController::toEffectivePermission)
                .toList();
        return new Identity(view.principal().id(), view.username(), toIdentitySource(view.source()),
                view.mustRotate(), permissions, organizations, csrfToken);
    }

    private static IdentitySource toIdentitySource(PrincipalSource source) {
        return switch (source) {
            case LOCAL -> IdentitySource.LOCAL;
            case IDP -> IdentitySource.IDP;
        };
    }

    private static EffectivePermission toEffectivePermission(EffectivePermissionView view) {
        List<ScopePath> scopes = view.scopes().stream().map(AuthController::toScopePath).toList();
        return new EffectivePermission(view.permission().id(), scopes);
    }

    private static ScopePath toScopePath(com.solace.sm.cp.api.authz.ScopePath scope) {
        return new ScopePath(scope.organization(), scope.subOrganization(), scope.environment(),
                scope.solution(), scope.instance());
    }
}

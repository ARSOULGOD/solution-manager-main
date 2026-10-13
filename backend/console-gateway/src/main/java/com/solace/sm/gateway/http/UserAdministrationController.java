package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.identity.PrincipalAdministration;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.identity.UserView;
import com.solace.sm.cp.api.session.Session;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The local users of the session's organization over the API (plan 013 T3.2; D-REG 5.4 U1 to U3, split into four
 * operations because one operation maps to one verb): {@code listUsers}, {@code createUser}, {@code deactivateUser} and
 * {@code resetUserCredential}. A translator; the rules are {@link PrincipalAdministration}'s.
 *
 * <p>The verb ({@code user.view}, {@code user.create}, {@code user.deactivate}, {@code user.edit}) has been checked by
 * {@link AuthenticationInterceptor} before any handler here runs: at the session's organization for the list and the
 * create ({@link SessionOrganizationResourceResolver}), at the principal the path names for the other two
 * ({@link UserResolver}). Every handler passes the SESSION's principal and organization: no request field or path
 * segment names an organization ({@code I5}), so there is no "another organization's user" to ask for.
 * {@code createUser} and {@code resetUserCredential} are also {@link CredentialBearing}: a channel that is not secure
 * was refused {@code 403 INSECURE_TRANSPORT} before this handler's body was read.
 *
 * <p>⛔ {@code I6}: a credential reaches the service as a {@code char[]} taken from the request record and is zeroised
 * in a {@code finally}; nothing here puts one in a message, a log line or a response, and {@link User} has no field that
 * could carry one.
 */
@RestController
@RequestMapping("/api/cp/admin/users")
public class UserAdministrationController {

    private final PrincipalAdministration users;
    private final RequestOriginFactory origins;

    public UserAdministrationController(PrincipalAdministration users, RequestOriginFactory origins) {
        this.users = Objects.requireNonNull(users, "users");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    @GetMapping
    @ConsoleOperation(value = "listUsers", resource = SessionOrganizationResourceResolver.class)
    public ResponseEntity<UserList> listUsers(Session session) {
        List<User> items = users.list(session.principal(), session.organizationId()).stream()
                .map(UserAdministrationController::toWire).toList();
        return ResponseEntity.ok(new UserList(items));
    }

    @PostMapping
    @CredentialBearing
    @ConsoleOperation(value = "createUser", resource = SessionOrganizationResourceResolver.class)
    public ResponseEntity<User> createUser(@RequestBody CreateUserRequest request, Session session) {
        char[] credential = request.credential();
        try {
            UserView created = users.create(session.principal(), origins.create(), session.organizationId(),
                    request.usernameValue(), credential);
            return ResponseEntity.status(HttpStatus.CREATED).body(toWire(created));
        } catch (IllegalArgumentException shape) {
            // a username the service will not store (whitespace, a zero-width character, not NFC): a malformed
            // request. Nothing of it, and nothing of the exception's message, is kept.
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    @PostMapping("/{userId}/deactivate")
    @ConsoleOperation(value = "deactivateUser", resource = UserResolver.class)
    public ResponseEntity<User> deactivateUser(@PathVariable UUID userId, Session session) {
        return ResponseEntity.ok(toWire(users.deactivate(session.principal(), origins.create(),
                session.organizationId(), userId)));
    }

    @PutMapping("/{userId}/credential")
    @CredentialBearing
    @ConsoleOperation(value = "resetUserCredential", resource = UserResolver.class)
    public ResponseEntity<User> resetUserCredential(@PathVariable UUID userId,
            @RequestBody ResetUserCredentialRequest request, Session session) {
        char[] credential = request.credential();
        try {
            return ResponseEntity.ok(toWire(users.resetCredential(session.principal(), origins.create(),
                    session.organizationId(), userId, credential)));
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    private static User toWire(UserView view) {
        return new User(view.id(), view.username(), toWire(view.source()), toWire(view.status()), view.mustRotate(),
                view.createdAt().toString());
    }

    private static IdentitySource toWire(PrincipalSource source) {
        return switch (source) {
            case LOCAL -> IdentitySource.LOCAL;
            case IDP -> IdentitySource.IDP;
        };
    }

    private static UserStatus toWire(UserView.Status status) {
        return switch (status) {
            case ACTIVE -> UserStatus.ACTIVE;
            case DEACTIVATED -> UserStatus.DEACTIVATED;
        };
    }
}

package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.session.Session;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entitlements over the API (plan 013 T2.3; D-REG 5.2 E1 and E2): {@code listEntitlements} and {@code setEntitlement}. A
 * translator; the rules are {@link EntitlementAdministration}'s.
 *
 * <p>{@code solution.view} at the session's organization ({@link SessionOrganizationResourceResolver}) or
 * {@code entitlement.set} at install scope ({@link InstallResourceResolver}) has been checked by
 * {@link AuthenticationInterceptor} before any handler here runs. Both handlers pass the SESSION's organization: no
 * request field or path segment names one ({@code I5}), so there is no "another organization's entitlement" to ask for.
 *
 * <p>A {@code PUT} is idempotent by nature: the same limit again answers {@code 200} and the service writes nothing. A
 * limit below the declared count is {@code 409 ENTITLEMENT_REFUSED} (the service's {@code EntitlementRefusedException},
 * rendered by {@link GatewayErrorAdvice}). There is no delete: an entitlement is lowered, never removed.
 */
@RestController
@RequestMapping("/api/cp/entitlements")
public class EntitlementController {

    /** The catalog's solution-name grammar, the contract's {@code pattern}; see {@link EntitlementAdministration}. */
    static final Pattern SOLUTION_NAME = EntitlementAdministration.SOLUTION_NAME;

    private final EntitlementAdministration entitlements;
    private final RequestOriginFactory origins;

    public EntitlementController(EntitlementAdministration entitlements, RequestOriginFactory origins) {
        this.entitlements = Objects.requireNonNull(entitlements, "entitlements");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    @GetMapping
    @ConsoleOperation(value = "listEntitlements", resource = SessionOrganizationResourceResolver.class)
    public ResponseEntity<EntitlementList> listEntitlements(Session session) {
        List<Entitlement> items = entitlements.list(session.principal(), session.organizationId()).stream()
                .map(EntitlementController::toWire).toList();
        return ResponseEntity.ok(new EntitlementList(items));
    }

    @PutMapping("/{solution}")
    @ConsoleOperation(value = "setEntitlement", resource = InstallResourceResolver.class)
    public ResponseEntity<Entitlement> setEntitlement(@PathVariable String solution,
            @RequestBody SetEntitlementRequest body, Session session) {
        if (!SOLUTION_NAME.matcher(solution).matches()) {
            // the grammar the service would refuse with an IllegalArgumentException (a 500); a name outside it names no
            // solution, so it is a malformed request
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return ResponseEntity.ok(toWire(entitlements.set(session.principal(), origins.create(),
                session.organizationId(), solution, body.limit())));
    }

    private static Entitlement toWire(EntitlementAdministration.Entitlement entitlement) {
        return new Entitlement(entitlement.solution(), entitlement.maxInstances(), entitlement.declaredInstances(),
                entitlement.setAt().toString());
    }
}

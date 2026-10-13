package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code setConfigSecret} (plan 010 T10.1): an {@code instance}-scope CATALOG operation. {@code config.edit} at the
 * instance named by the path has passed in {@link AuthenticationInterceptor} before the body is read
 * ({@link InstanceResolver}). A translator: the protocol is {@link ConfigSecretOrchestrator}'s.
 *
 * <p>⛔ {@code I6}: the literal is turned into a {@code char[]} and that array is overwritten in a {@code finally},
 * whether the call stored it, refused it or failed. (The request's own {@code String} is the JDK's to reclaim; no code
 * here can scrub it.)
 */
@RestController
@RequestMapping("/api/dp")
public class ConfigSecretController {

    private final ConfigSecretOrchestrator secrets;

    public ConfigSecretController(ConfigSecretOrchestrator secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @PostMapping("/instances/{instanceId}/config/secrets/{fieldKey}")
    @ConsoleOperation(value = "setConfigSecret", resource = InstanceResolver.class)
    public ResponseEntity<ConfigSecretResult> setConfigSecret(@PathVariable UUID instanceId,
            @PathVariable String fieldKey, @RequestBody SetConfigSecretRequest request, Session session) {
        char[] value = request.value().toCharArray();
        try {
            return ResponseEntity.ok(secrets.setSecret(session, instanceId, fieldKey, value,
                    request.baseStateVersion()));
        } finally {
            Arrays.fill(value, '\0');
        }
    }
}

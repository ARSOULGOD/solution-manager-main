package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getHomeSummary} and {@code getHomeAttention} (plan 010 T10.5, {@code OR-45}). Both are
 * {@code x-sm-scope: reach} (S0 point 24): {@link AuthenticationInterceptor} has already required the operation's own
 * verb ({@code solution.view}, {@code health.view}) at one or more grants in the session's organization, which
 * authorizes NO item. {@link HomeOrchestrator} composes the two planes and filters every item by the caller's reach.
 */
@RestController
@RequestMapping("/api/cp/home")
public class HomeController {

    private final HomeOrchestrator home;

    public HomeController(HomeOrchestrator home) {
        this.home = Objects.requireNonNull(home, "home");
    }

    @GetMapping("/summary")
    @ConsoleOperation("getHomeSummary")
    public ResponseEntity<HomeSummary> getHomeSummary(Session session) {
        return ResponseEntity.ok(home.summary(session));
    }

    @GetMapping("/attention")
    @ConsoleOperation("getHomeAttention")
    public ResponseEntity<HomeAttention> getHomeAttention(Session session) {
        return ResponseEntity.ok(home.attention(session));
    }
}

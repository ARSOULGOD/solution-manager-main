package com.solace.sm.cp.authz;

import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Objects;

/** Navigation visibility is prefix-comparability; authorization reach remains downward only. */
public final class ScopeVisibility {

    private ScopeVisibility() {
    }

    public static boolean isVisible(ScopePath held, ScopePath candidate) {
        Objects.requireNonNull(held, "held");
        Objects.requireNonNull(candidate, "candidate");
        return held.authorizes(candidate) || candidate.authorizes(held);
    }
}

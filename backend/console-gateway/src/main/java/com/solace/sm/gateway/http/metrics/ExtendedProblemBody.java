package com.solace.sm.gateway.http.metrics;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.solace.sm.gateway.http.ProblemBody;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link ProblemBody} plus the extension members the monitoring contract adds to {@code Problem} (RFC 9457): {@code reason}
 * on {@code ReasonedProblem}, {@code {cap, projected, seriesBy}} on {@code SeriesCapExceededProblem} and
 * {@code {projectedRows, limit}} on {@code ExportTooLargeProblem}.
 *
 * <p>Wraps rather than extends: {@link ProblemBody} is a record whose title and detail are fixed per code and whose only
 * constructors are its factories, and that guarantee ("nothing a caller supplies can reach a body") must not be loosened
 * for one family of routes. The extension values here are closed constants and COUNTS, never request content.
 */
public record ExtendedProblemBody(@JsonUnwrapped ProblemBody problem, @JsonAnyGetter Map<String, Object> extensions) {

    public ExtendedProblemBody {
        Objects.requireNonNull(problem, "problem");
        extensions = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(extensions, "extensions")));
    }
}

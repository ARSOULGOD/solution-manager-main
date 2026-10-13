package com.solace.sm.dp.api.metrics.query;

import java.util.Objects;

/**
 * A request the data plane declines to compute (see {@link QueryRefusal}). {@link #cap()}, {@link #projected()} and
 * {@link #seriesBy()} are set only for {@link QueryRefusal#SERIES_CAP_EXCEEDED}, which carries
 * {@code {cap, projected, seriesBy}}. The message names the code and never a tenant, instance or value.
 */
public final class MetricsQueryRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final QueryRefusal refusal;
    private final Integer cap;
    private final Integer projected;
    private final RangeQuery.SeriesBy seriesBy;

    public MetricsQueryRefusedException(QueryRefusal refusal) {
        this(refusal, null, null, null);
    }

    public MetricsQueryRefusedException(QueryRefusal refusal, Integer cap, Integer projected, RangeQuery.SeriesBy seriesBy) {
        super("the metrics query was refused: " + Objects.requireNonNull(refusal, "refusal").wire());
        this.refusal = refusal;
        this.cap = cap;
        this.projected = projected;
        this.seriesBy = seriesBy;
    }

    public QueryRefusal refusal() {
        return refusal;
    }

    /** The series cap, set only for {@link QueryRefusal#SERIES_CAP_EXCEEDED}. */
    public Integer cap() {
        return cap;
    }

    /** The rendered series the request would have produced, set only for {@link QueryRefusal#SERIES_CAP_EXCEEDED}. */
    public Integer projected() {
        return projected;
    }

    /** The series mode the projection was made for, set only for {@link QueryRefusal#SERIES_CAP_EXCEEDED}. */
    public RangeQuery.SeriesBy seriesBy() {
        return seriesBy;
    }
}

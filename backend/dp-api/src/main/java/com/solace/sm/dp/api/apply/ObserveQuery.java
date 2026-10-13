package com.solace.sm.dp.api.apply;

/**
 * What {@link ApplyService#observeApply} is asked for.
 *
 * @param applyId           selects one request (what a browser polls for a stop), or {@code null} for all
 * @param sinceStateVersion strictly newer than this, or {@code null} for everything
 * @param limit             the page bound; the OLDEST rows are kept so a repeated backfill advances without skipping
 */
public record ObserveQuery(java.util.UUID applyId, Long sinceStateVersion, int limit) {

    public ObserveQuery {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (sinceStateVersion != null && sinceStateVersion < 0) {
            throw new IllegalArgumentException("sinceStateVersion must not be negative");
        }
    }
}

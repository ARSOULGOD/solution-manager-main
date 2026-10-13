package com.solace.sm.cp.api.dashboard;

import java.util.Objects;

/**
 * A widget as the caller submitted it: the widget configuration document (DM design section 7) as JSON text.
 *
 * <p>Text, not a tree, because {@code cp-api} carries no JSON library by design; {@code cp-core} parses it. The
 * server assigns the widget's id and layer, so whatever the document says for either is ignored.
 */
public record WidgetSubmission(String json) {

    public WidgetSubmission {
        Objects.requireNonNull(json, "json");
    }
}

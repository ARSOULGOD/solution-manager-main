package com.solace.sm.cp.designer;

import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.DesignerStageValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Designer projection port ({@code OR-34}), its stage-time validator and the JSON codec as beans, each
 * exposed BY ITS {@code cp-api} INTERFACE TYPE so a caller — the console-gateway's stage endpoint
 * (plan 010 T5.3) — depends on {@code cp-api} only ({@code I7}).
 *
 * <p>Imported by {@link com.solace.sm.cp.config.ControlPlaneConfiguration}, the one aggregate the
 * launcher composes through; the launcher never names a cp-core class. Both beans are pure
 * (no datasource, no clock, no I/O), so there is nothing here to qualify against the Data Plane's
 * beans. The validator takes the context's own projector, so it can never re-project with a
 * different engine than the one the context serves.
 */
@Configuration(proxyBeanMethods = false)
public class DesignerConfiguration {

    @Bean
    DesignerProjector designerProjector() {
        return new DesignerProjectorImpl();
    }

    @Bean
    DesignerStageValidator designerStageValidator(DesignerProjector designerProjector) {
        return new DesignerStageValidatorImpl(designerProjector);
    }

    /** The gateway's only route from a {@code ViewModel} to the wire, and from a stored package to the projector. */
    @Bean
    DesignerCodec designerCodec() {
        return new DesignerCodecImpl();
    }
}

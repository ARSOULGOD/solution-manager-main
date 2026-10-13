package com.solace.sm.cp.designer;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.DesignerStageValidator;
import com.solace.sm.cp.config.ControlPlaneConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * {@link DesignerConfiguration} wires the landed projector and stage validator as beans, by their
 * {@code cp-api} interface types, and {@link ControlPlaneConfiguration} pulls it in — so the
 * launcher (which composes only through that aggregate) gets them without naming a cp-core class.
 */
class DesignerConfigurationTest {

    @Test
    void theContextHasExactlyOneProjectorAndOneStageValidator() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DesignerConfiguration.class)) {
            assertThat(ctx.getBeansOfType(DesignerProjector.class)).hasSize(1);
            assertThat(ctx.getBeansOfType(DesignerStageValidator.class)).hasSize(1);
            assertThat(ctx.getBean(DesignerProjector.class)).isInstanceOf(DesignerProjectorImpl.class);
            assertThat(ctx.getBean(DesignerStageValidator.class)).isInstanceOf(DesignerStageValidatorImpl.class);
        }
    }

    @Test
    void theStageValidatorIsBuiltOnTheContextsProjectorNotAPrivateCopy() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DesignerConfiguration.class)) {
            // The validator re-projects server-side (OR-34); if it held its own projector, a
            // later decorated/replaced projector bean would leave the stage check on the old one.
            assertThat(ctx.getBean(DesignerStageValidatorImpl.class)).extracting("projector")
                    .isSameAs(ctx.getBean(DesignerProjector.class));
        }
    }

    @Test
    void theControlPlaneConfigurationImportsIt() {
        Import imports = ControlPlaneConfiguration.class.getAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(imports.value()).contains(DesignerConfiguration.class);
    }
}

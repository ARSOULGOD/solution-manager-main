package com.solace.sm.dp.meshbridge;

import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The mesh publish path as one bean graph (backend design §2/§3, {@code BE-D1}):
 * {@code EventPublisher} = after-commit ordering over JCSMP over a reconnecting session that starts
 * disconnected. Domain code injects {@link EventPublisher} and nothing else; the
 * {@link JcsmpEventPublisher} bean exists separately so its drop counter stays reachable for the
 * metrics plan without widening the seam.
 *
 * <p>Not auto-configured: the future application class {@code @Import}s this deliberately. Nothing
 * here is auth-bearing — this is the server's own publish identity, not the browser path.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeshProperties.class)
public class MeshBridgeConfiguration {

    /**
     * The raw transport. Closed by the context on shutdown ({@code AutoCloseable} — retires the
     * connect thread too), which is one of the two reasons this bean exists separately at all; the
     * other is the metrics plan's counter reach ({@code droppedCount()} stays retrievable by
     * concrete type).
     *
     * <p>It is deliberately <b>not a default autowire candidate</b>. {@link JcsmpEventPublisher}
     * <i>is itself</i> an {@link EventPublisher}, so without this attribute a
     * {@code List<EventPublisher>} or {@code ObjectProvider<EventPublisher>} injection point
     * receives BOTH beans — measured hazard: a future fan-out service injecting the list
     * double-publishes, and the second copy goes through the raw transport BEFORE commit, defeating
     * the after-commit ordering this whole graph exists to guarantee. {@code @Primary} on the
     * composition only settles <i>single</i>-bean injection; it does nothing for collections.
     *
     * <p><b>Why {@code defaultCandidate} and not {@code autowireCandidate}</b> — measured on
     * spring-context 6.2.19, not assumed. {@code autowireCandidate = false} is absolute: it removes
     * the bean from dependency resolution entirely, including {@code @Bean} <i>method-parameter</i>
     * resolution, so {@link #eventPublisher} below could no longer be satisfied and the whole
     * context failed to start ({@code UnsatisfiedDependencyException} … "expected at least 1 bean
     * which qualifies as autowire candidate"). An explicit {@code @Qualifier} does not recover it —
     * also measured. {@code defaultCandidate = false} is the narrower tool that fits: the bean is
     * excluded from <i>unqualified</i> injection points (which is exactly what the collection
     * hazard is) while an explicitly qualified one — {@link #eventPublisher}'s parameter, the one
     * intended consumer — still resolves. Reaching the raw transport therefore now requires naming
     * it on purpose.
     *
     * <p>{@code context.getBean(JcsmpEventPublisher.class)} is unaffected either way —
     * {@code getBean} ignores candidate status — so the destroy hook and the counter reach both
     * survive.
     */
    @Bean(defaultCandidate = false)
    JcsmpEventPublisher meshEventPublisher(MeshProperties props) {
        return JcsmpEventPublisher.connectingInBackground(props);
    }

    /**
     * {@code @Primary} because {@link JcsmpEventPublisher} <i>is itself</i> an
     * {@link EventPublisher}: without it, two beans answer to the type and every injection point
     * that asks for the seam by type alone fails with {@code NoUniqueBeanDefinition} — measured, not
     * assumed (the Step 2 context test failed exactly that way before this annotation existed).
     * It is kept alongside the raw bean's {@code defaultCandidate = false} because it documents the
     * single-injection contract at the bean that wins, and costs nothing.
     * The class javadoc's "domain code injects {@link EventPublisher} and nothing else" is only true
     * with the after-commit composition marked primary; the raw transport stays reachable by its
     * concrete type for the metrics plan's drop counter.
     *
     * <p>The {@code @Qualifier} is load-bearing, not decoration: it is what makes this the one
     * <i>deliberate</i> consumer of a bean that no longer answers unqualified requests. Deleting it
     * as tidy-up does not degrade quietly — it stops the context starting, and all four
     * {@code MeshBridgeConfigurationTest} cases go red on
     * {@code UnsatisfiedDependencyException} (measured, not asserted). No extra guard test is
     * needed: the existing context tests already are the guard.
     */
    @Bean
    @Primary
    EventPublisher eventPublisher(
            @Qualifier("meshEventPublisher") JcsmpEventPublisher meshEventPublisher) {
        return new AfterCommitEventPublisher(meshEventPublisher, new SpringTransactionBoundary());
    }
}

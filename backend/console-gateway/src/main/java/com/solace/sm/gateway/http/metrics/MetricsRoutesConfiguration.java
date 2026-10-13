package com.solace.sm.gateway.http.metrics;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The monitoring routes' beans that are not themselves components (plan 011 P11-7.2). Constructs no control-plane or
 * data-plane service: every collaborator is an {@code -api} interface the launcher supplies.
 *
 * <p>The launcher component-scans {@code com.solace.sm.gateway}, so this class and every controller beside it are picked
 * up automatically.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MetricsGatewayProperties.class, DataPlaneQueryLimits.class})
public class MetricsRoutesConfiguration {

    /** The per-session query rate. Monotonic time, never the wall clock (a backwards NTP step must not refill a bucket). */
    @Bean
    public MetricsRateGate metricsRateGate(MetricsGatewayProperties properties) {
        return new MetricsRateGate(properties.queryRatePerSecond(), System::nanoTime);
    }

    /** The refused export's {@code DENY} audit row (OR-112 (6), W4-04): see {@link ExportRefusalAuditInterceptor}. */
    @Bean
    public ExportRefusalAuditInterceptor exportRefusalAuditInterceptor(com.solace.sm.cp.api.audit.ExportAudit audit,
            com.solace.sm.gateway.http.RequestOriginFactory originFactory) {
        return new ExportRefusalAuditInterceptor(audit, originFactory);
    }

    @Bean
    public WebMvcConfigurer exportRefusalAuditRegistration(ExportRefusalAuditInterceptor interceptor) {
        return ExportRefusalAuditInterceptor.registration(interceptor);
    }

    /**
     * The export's bound, set EXPLICITLY (MF18): {@code StreamingResponseBody} runs as Spring MVC async processing, so the
     * request is bounded by the MVC async timeout, never only by the database's {@code statement_timeout}, and the
     * container's own default is not a number anyone chose.
     *
     * <p>The executor is BOUNDED (W4-09): {@code sm.gateway.metrics.export-max-threads} threads, no queue. The data plane's
     * concurrency caps are taken inside {@code export.stream()}, ON the streaming thread, so an unbounded executor would
     * create a thread for every request that passed {@code plan()} and refuse it only afterwards. Past this bound the
     * submit is refused ({@code TaskRejectedException}) and {@link MetricsErrorAdvice} answers the same
     * {@code 429 export-concurrency}. The executor serves only exports in v1; a future async route shares it knowingly.
     */
    @Bean
    public ExportAsyncSupport metricsAsyncSupport(MetricsGatewayProperties properties) {
        return new ExportAsyncSupport(properties);
    }

    /** The MVC async configuration, which owns (and on shutdown stops) the bounded export executor. */
    public static final class ExportAsyncSupport implements WebMvcConfigurer, DisposableBean {

        private final MetricsGatewayProperties properties;
        private final ThreadPoolTaskExecutor executor;

        ExportAsyncSupport(MetricsGatewayProperties properties) {
            this.properties = properties;
            this.executor = boundedExecutor(properties.exportMaxThreads());
        }

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.setDefaultTimeout(properties.exportAsyncTimeout().toMillis());
            configurer.setTaskExecutor(executor);
        }

        @Override
        public void destroy() {
            executor.shutdown();
        }
    }

    /** {@code maxThreads} threads, no queue, idle threads let go: a submit past the bound is refused, never parked. */
    static ThreadPoolTaskExecutor boundedExecutor(int maxThreads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sm-export-");
        executor.setCorePoolSize(maxThreads);
        executor.setMaxPoolSize(maxThreads);
        executor.setQueueCapacity(0);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(30);
        executor.initialize();
        return executor;
    }
}

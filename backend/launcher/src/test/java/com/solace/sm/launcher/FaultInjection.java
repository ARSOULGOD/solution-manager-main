package com.solace.sm.launcher;

import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentTelemetryBatch;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.api.agw.CommandAckResult;
import com.solace.sm.dp.api.agw.CommandOffer;
import com.solace.sm.dp.api.agw.CommandPayload;
import com.solace.sm.dp.api.agw.AgentTelemetryResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;

/**
 * Deterministic fault injection for the failure matrix (plan 011 P7.2, ported by plan 015 T4.4). The decorator sits on the gateway's
 * PORT seam (the command, report, lease, telemetry and session ports) between the gRPC translation and the durable adapters, so each
 * fault is exactly "the data plane did not do (or was late doing) the durable step the agent believes it asked for". Nothing here
 * touches the agent, the wire or the database; every scenario still runs the real Go agent, the real gateway and PostgreSQL.
 *
 * <p>Faults are JVM-wide statics (one DP per matrix run) and are reset by {@link #reset()} between scenarios.
 */
final class FaultInjection {
    private FaultInjection() {
    }

    /** Next N {@code received} calls are swallowed: the agent's CommandReceived never reaches the durable record. */
    static final AtomicInteger DROP_RECEIVED = new AtomicInteger();
    /**
     * How many {@code received}/{@code ack} calls pass untouched before a drop takes effect. A restart-bearing apply is three commands
     * (PREFLIGHT, APPLY, RESTART): skipping two drops the RESTART's.
     */
    static final AtomicInteger RECEIVED_SKIP = new AtomicInteger();
    static final AtomicInteger ACK_SKIP = new AtomicInteger();
    /** Next N {@code ack} calls are swallowed: the agent's terminal CommandAck never reaches the durable record. */
    static final AtomicInteger DROP_ACK = new AtomicInteger();
    /** Added to every {@code report} call, before it commits: the actual-state report lands late. */
    static final AtomicLong REPORT_DELAY_MILLIS = new AtomicLong();
    /** While set, {@code renew} is refused with this code. */
    static volatile GatewayCode RENEW_REFUSAL;
    /** While set, {@code ingest} is refused with this code. */
    static volatile GatewayCode INGEST_REFUSAL;
    /**
     * Next N {@code ingest} calls answer {@code DO_NOT_ACK} with {@code retryAfter} 2 s and commit NOTHING: the data plane's answer to a
     * batch it will not take now (the built port's {@code RetryAfter}, T3.4). The real agent must retry the same batch.
     */
    static final AtomicInteger INGEST_RETRY_AFTER = new AtomicInteger();
    /** Added to every {@code poll}/{@code pull}/{@code ack}/{@code ingest}: a slow database. */
    static final AtomicLong DB_DELAY_MILLIS = new AtomicLong();
    /** Next N {@code ingest} calls answer {@code REJECTED_COUNTED} (the data plane drops the batch and counts it; the gateway acknowledges it as rejected). */
    static final AtomicInteger INGEST_REJECTED_COUNTED = new AtomicInteger();
    /** Next N pulled APPLY offers carry different bytes than the hash they were offered with: a payload the agent must refuse. */
    static final AtomicInteger TAMPER_PULL = new AtomicInteger();
    /** Added to every {@code renew}: the renewal is in flight for this long (it is then answered normally). */
    static final AtomicLong RENEW_DELAY_MILLIS = new AtomicLong();
    /** Wall-clock milliseconds at which each {@code renew} started and finished: {@code start@<ms>} / {@code done@<ms>}. */
    static final List<String> RENEW_EVENTS = new CopyOnWriteArrayList<>();
    /** Calls of {@code renew}/{@code issue} made while a drain is the test's concern (counted, never refused). */
    static final AtomicInteger RENEWALS = new AtomicInteger();

    /** Every call the gateway made, in order, as {@code method:detail}, for the evidence trail. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    static final AtomicInteger CLAIMS = new AtomicInteger();
    static final AtomicInteger INGESTS = new AtomicInteger();

    static void reset() {
        DROP_RECEIVED.set(0);
        DROP_ACK.set(0);
        RECEIVED_SKIP.set(0);
        ACK_SKIP.set(0);
        REPORT_DELAY_MILLIS.set(0);
        RENEW_REFUSAL = null;
        RENEW_DELAY_MILLIS.set(0);
        INGEST_REFUSAL = null;
        INGEST_RETRY_AFTER.set(0);
        DB_DELAY_MILLIS.set(0);
        TAMPER_PULL.set(0);
        INGEST_REJECTED_COUNTED.set(0);
    }

    /**
     * Deliberately NOT a {@code @Configuration}: it sits in the launcher's own package, which every {@code @SpringBootTest} here component-scans,
     * and a stereotype would put the port decorator into every other launcher IT (an earlier form turned the real report intake into a proxy
     * in AgentGatewayCompositionIT). A class with a {@code @Bean} method is still a configuration candidate when it is registered
     * explicitly, which is what {@code RealAgentHarness#startDp} does.
     */
    static class Decorator {
        @Bean
        static BeanPostProcessor faultInjectingPorts() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (Proxy.isProxyClass(bean.getClass()) || !isPort(bean)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), bean.getClass().getInterfaces(),
                            (proxy, method, args) -> {
                                String call = method.getName();
                                switch (call) {
                                    case "received" -> {
                                        if (bean instanceof AgentCommandPort && DROP_RECEIVED.get() > 0 && !skip(RECEIVED_SKIP)
                                                && take(DROP_RECEIVED)) {
                                            CALLS.add("received:DROPPED");
                                            return null;
                                        }
                                    }
                                    case "ack" -> {
                                        if (bean instanceof AgentCommandPort) {
                                            if (DROP_ACK.get() > 0 && !skip(ACK_SKIP) && take(DROP_ACK)) {
                                                CALLS.add("ack:DROPPED:" + args[1]);
                                                return CommandAckResult.COMMITTED;
                                            }
                                            sleep(DB_DELAY_MILLIS.get());
                                        }
                                    }
                                    case "report" -> sleep(REPORT_DELAY_MILLIS.get());
                                    case "renew" -> {
                                        RENEWALS.incrementAndGet();
                                        GatewayCode refusal = RENEW_REFUSAL;
                                        if (refusal != null) {
                                            CALLS.add("renew:REFUSED:" + refusal);
                                            throw new GatewayPortException(refusal, "injected");
                                        }
                                        RENEW_EVENTS.add("start@" + System.currentTimeMillis());
                                        sleep(RENEW_DELAY_MILLIS.get());
                                        try {
                                            return method.invoke(bean, args);
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        } finally {
                                            RENEW_EVENTS.add("done@" + System.currentTimeMillis());
                                        }
                                    }
                                    case "ingest" -> {
                                        INGESTS.incrementAndGet();
                                        CALLS.add("ingest:" + ((AgentTelemetryBatch) args[1]).batchSeq());
                                        GatewayCode refusal = INGEST_REFUSAL;
                                        if (refusal != null) {
                                            CALLS.add("ingest:REFUSED:" + refusal);
                                            throw new GatewayPortException(refusal, "injected");
                                        }
                                        if (take(INGEST_REJECTED_COUNTED)) {
                                            CALLS.add("ingest:REJECTED_COUNTED:" + ((AgentTelemetryBatch) args[1]).batchSeq());
                                            return new AgentTelemetryResult(AgentTelemetryResult.Disposition.REJECTED_COUNTED,
                                                    ((AgentTelemetryBatch) args[1]).batchSeq(), 1, null, GatewayCode.BINDING_NOT_IN_LEASE);
                                        }
                                        if (take(INGEST_RETRY_AFTER)) {
                                            CALLS.add("ingest:RETRY_AFTER:" + ((AgentTelemetryBatch) args[1]).batchSeq());
                                            return new AgentTelemetryResult(AgentTelemetryResult.Disposition.DO_NOT_ACK, 0, 0,
                                                    java.time.Duration.ofSeconds(2), null);
                                        }
                                        sleep(DB_DELAY_MILLIS.get());
                                    }
                                    case "pendingFor" -> sleep(DB_DELAY_MILLIS.get());
                                    case "pull" -> {
                                        sleep(DB_DELAY_MILLIS.get());
                                        Object offered;
                                        try {
                                            offered = method.invoke(bean, args);
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                        if (offered instanceof CommandOffer offer && offer.payload().bytes().length > 0
                                                && take(TAMPER_PULL)) {
                                            CALLS.add("pull:TAMPERED:" + offer.commandId());
                                            return new CommandOffer(offer.commandId(), offer.generation(), offer.idempotencyKey(),
                                                    offer.placementId(), offer.operation(), offer.supersedesGeneration(), offer.deadline(),
                                                    offer.correlation(), new CommandPayload("tampered: true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                                            offer.payload().configSha256(), offer.payload().expectedCurrentSha256()));
                                        }
                                        return offered;
                                    }
                                    case "claim" -> {
                                        CLAIMS.incrementAndGet();
                                        CALLS.add("claim");
                                    }
                                    default -> { }
                                }
                                try {
                                    return method.invoke(bean, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
            };
        }

        private static boolean isPort(Object bean) {
            return bean instanceof AgentCommandPort || bean instanceof ActualStatePort || bean instanceof AgentLeasePort
                    || bean instanceof AgentTelemetryPort || bean instanceof AgentSessionPort;
        }

        /** True (and counts one down) while calls are still to be let through. */
        private static boolean skip(AtomicInteger counter) {
            while (true) {
                int n = counter.get();
                if (n <= 0) {
                    return false;
                }
                if (counter.compareAndSet(n, n - 1)) {
                    return true;
                }
            }
        }

        private static boolean take(AtomicInteger counter) {
            while (true) {
                int n = counter.get();
                if (n <= 0) {
                    return false;
                }
                if (counter.compareAndSet(n, n - 1)) {
                    return true;
                }
            }
        }

        private static void sleep(long millis) {
            if (millis > 0) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

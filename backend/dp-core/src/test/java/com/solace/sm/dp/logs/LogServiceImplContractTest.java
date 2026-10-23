package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.ssh.HostLogs;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The shared {@link LogService} contract, run against the SSH implementation. */
class LogServiceImplContractTest extends LogServiceContractTest {

    private final AtomicInteger touched = new AtomicInteger();

    private LogServiceImpl over(Runnable onRead) {
        SshDockerPlacementCatalog catalog = ref -> {
            touched.incrementAndGet();
            if (!ref.tenant().value().equals(ORGANIZATION) || !ref.instance().value().equals(INSTANCE)
                    || !ref.placement().value().equals(PLACEMENT)) {
                throw new RuntimeAdapterException(new ErrorClass("placement.unresolvable"));
            }
            return new SshDockerPlacement(new TenantId(ORGANIZATION), "10.9.8.7", 22, "sm", UUID.randomUUID(),
                    "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "sm-orders", "/srv/sm", "/srv/sm/orders", "/config",
                    "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
        };
        HostLogs logs = (p, limit, after, before) -> {
            touched.incrementAndGet();
            onRead.run();
            return "2026-10-06T06:25:01.0Z a line\n";
        };
        return new LogServiceImpl(catalog, logs, 1, Duration.ofMillis(50));
    }

    @Override
    LogService service() {
        return over(() -> { });
    }

    @Override
    LogService blockingService(CountDownLatch entered, CountDownLatch release) {
        return over(() -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    boolean untouched() {
        return touched.get() == 0;
    }
}

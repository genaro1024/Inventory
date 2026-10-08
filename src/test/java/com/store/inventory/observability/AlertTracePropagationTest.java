package com.store.inventory.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.application.InventoryApplicationService;
import com.store.inventory.application.notification.StockAlertDelivery;
import com.store.inventory.application.notification.StockAlertNotifications;
import com.store.inventory.infrastructure.jpa.H2InventoryDatabase;
import com.store.inventory.support.ManualRetryScheduler;
import com.store.inventory.support.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AlertTracePropagationTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void retryOnAnotherThreadUsesTheStoredTraceAndRestoresTheWorkerContext() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-10-08T12:00:00Z"));
        var scheduler = new ManualRetryScheduler(clock);
        var attempts = new AtomicInteger();
        List<String> traces = new CopyOnWriteArrayList<>();
        try (var database = new H2InventoryDatabase();
                var delivery = new StockAlertDelivery(database.alerts(), clock, (sku, units) -> {
                    traces.add(MDC.get(TraceContext.KEY));
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("Temporary provider failure");
                    }
                }, scheduler, () -> 0.5);
                var service = new InventoryApplicationService(database.inventories(), database.reservations(),
                        database.settlements(), database.operations(), clock,
                        new StockAlertNotifications(database.alerts(), delivery))) {
            MDC.put(TraceContext.KEY, "caller-before");
            try (var request = TraceContext.open("origin-request-123")) {
                service.registerProduct("SKU-1", ProductCategory.STANDARD);
                service.addStock("SKU-1", 3);
            }
            assertThat(MDC.get(TraceContext.KEY)).isEqualTo("caller-before");
            assertThat(database.alerts().findBySku("SKU-1").getFirst().traceId()).isEqualTo("origin-request-123");

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var retry = executor.submit(() -> {
                    MDC.put(TraceContext.KEY, "worker-before");
                    try {
                        scheduler.runNext();
                        return MDC.get(TraceContext.KEY);
                    } finally {
                        MDC.clear();
                    }
                });
                assertThat(retry.get(5, TimeUnit.SECONDS)).isEqualTo("worker-before");
            }
            assertThat(traces).containsExactly("origin-request-123", "origin-request-123");
            assertThat(MDC.get(TraceContext.KEY)).isEqualTo("caller-before");
        }
    }
}

package com.store.inventory.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.InventoryApplicationService;
import com.store.inventory.application.InventoryOperationExecutor;
import com.store.inventory.domain.ProductInventory;
import com.store.inventory.domain.notification.StockAlert;
import com.store.inventory.domain.notification.StockAlertState;
import com.store.inventory.infrastructure.jpa.H2InventoryDatabase;
import com.store.inventory.infrastructure.jpa.JpaInventoryPersistence;
import com.store.inventory.support.ManualRetryScheduler;
import com.store.inventory.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class StockAlertIntegrationTest {

    private H2InventoryDatabase database;
    private MutableClock clock;
    private ManualRetryScheduler scheduler;
    private StockAlertDelivery delivery;
    private InventoryApplicationService service;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<StockAlertListener> handler = new AtomicReference<>();

    @BeforeEach
    void initialize() {
        database = new H2InventoryDatabase();
        clock = new MutableClock(Instant.parse("2026-10-08T12:00:00Z"));
        scheduler = new ManualRetryScheduler(clock);
        healthyListener();
        delivery = new StockAlertDelivery(database.alerts(), clock, (sku, units) -> {
            calls.incrementAndGet();
            handler.get().onLowStock(sku, units);
        }, scheduler, () -> 0.5);
        service = newService(database.operations(), delivery);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @AfterEach
    void close() {
        service.close();
        delivery.close();
        database.close();
    }

    @Test
    void registrationAndUnloadedInventoryDoNotNotify() {
        assertThat(service.available("SKU-1")).isZero();
        assertThat(calls.get()).isZero();
        assertThat(database.alerts().findBySku("SKU-1")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void initialLowStockSendsOneImmediateAlert(int quantity) {
        service.addStock("SKU-1", quantity);
        service.available("SKU-1");

        assertThat(received).containsExactly("SKU-1:" + quantity);
        assertThat(single().state()).isEqualTo(StockAlertState.DELIVERED);
        assertThat(single().attempts()).isEqualTo(1);
        assertThat(scheduler.pendingCount()).isZero();
    }

    @Test
    void reservingToTheThresholdNotifiesOnceAndReplaysDoNotRepeatIt() {
        service.addStock("SKU-1", 10);
        var original = service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 1);
        service.confirm("ORDER-1");
        service.confirm("ORDER-1");
        assertThat(service.reserve("ORDER-1", "SKU-1", 5)).isEqualTo(original);
        service.available("SKU-1");

        assertThat(received).containsExactly("SKU-1:5");
        assertThat(database.alerts().findBySku("SKU-1")).hasSize(1);
    }

    @Test
    void everyReplenishmentOpensANewCycleIncludingSmallReplenishments() {
        service.addStock("SKU-1", 2);
        service.addStock("SKU-1", 1);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 8);

        assertThat(received).containsExactly("SKU-1:2", "SKU-1:3", "SKU-1:5");
        assertThat(database.alerts().findBySku("SKU-1")).extracting(StockAlert::cycle).containsExactly(1L, 2L, 3L);
    }

    @Test
    void rejectedOperationsDoNotResetTheNotificationCycle() {
        service.addStock("SKU-1", 3);
        assertThatIllegalArgumentException().isThrownBy(() -> service.addStock("SKU-1", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.addStock("SKU-1", Integer.MAX_VALUE));
        assertThatIllegalArgumentException().isThrownBy(() -> service.registerProduct("SKU-1", ProductCategory.STANDARD));
        service.available("SKU-1");

        assertThat(calls.get()).isEqualTo(1);
        assertThat(single().cycle()).isEqualTo(1);
        assertThat(service.available("SKU-1")).isEqualTo(3);
    }

    @Test
    void listenerFailureDoesNotUndoTheInventoryAndSuccessStopsRetries() {
        handler.set((sku, units) -> {
            if (calls.get() <= 2) {
                throw new IllegalStateException("Temporary mail outage");
            }
            received.add(sku + ":" + units);
        });
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 1);
        service.confirm("ORDER-1");

        assertThat(service.available("SKU-1")).isEqualTo(4);
        assertThat(single().state()).isEqualTo(StockAlertState.RETRY_WAIT);
        assertThat(single().nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(2));
        assertThat(scheduler.runAll()).isEqualTo(2);

        assertThat(calls.get()).isEqualTo(3);
        assertThat(received).containsExactly("SKU-1:5");
        assertThat(single().state()).isEqualTo(StockAlertState.DELIVERED);
        assertThat(single().attempts()).isEqualTo(3);
        assertThat(single().nextAttemptAt()).isNull();
        assertThat(scheduler.delays()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void sixFailuresProduceADurableDlqEntryWithoutAutomaticRedelivery() {
        failAlways();
        service.addStock("SKU-1", 3);
        assertThat(scheduler.runAll()).isEqualTo(5);

        assertThat(calls.get()).isEqualTo(6);
        assertThat(single().state()).isEqualTo(StockAlertState.DEAD_LETTER);
        assertThat(single().attempts()).isEqualTo(6);
        assertThat(single().lastError()).contains("provider-down-6");
        assertThat(single().nextAttemptAt()).isNull();
        assertThat(database.alerts().deadLetters()).containsExactly(single());
        assertThat(scheduler.delays()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(8), Duration.ofSeconds(16), Duration.ofSeconds(32));
        service.available("SKU-1");
        service.reserve("ORDER-1", "SKU-1", 1);
        assertThat(calls.get()).isEqualTo(6);
        assertThat(scheduler.runAll()).isZero();
    }

    @Test
    void highReplenishmentCancelsPendingRetries() {
        failAlways();
        service.addStock("SKU-1", 3);
        var old = single();
        healthyListener();
        service.addStock("SKU-1", 10);

        assertThat(database.alerts().findById(old.id()).orElseThrow().state()).isEqualTo(StockAlertState.CANCELLED);
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(scheduler.runAll()).isZero();
        assertThat(calls.get()).isEqualTo(1);
        service.reserve("ORDER-1", "SKU-1", 8);
        assertThat(received).containsExactly("SKU-1:5");
    }

    @Test
    void lowReplenishmentReplacesThePendingAlertWithCurrentUnits() {
        failAlways();
        service.addStock("SKU-1", 2);
        var old = single();
        healthyListener();
        service.addStock("SKU-1", 1);

        assertThat(received).containsExactly("SKU-1:3");
        assertThat(database.alerts().findById(old.id()).orElseThrow().state()).isEqualTo(StockAlertState.CANCELLED);
        assertThat(database.alerts().findBySku("SKU-1").getLast().state()).isEqualTo(StockAlertState.DELIVERED);
        assertThat(scheduler.runAll()).isZero();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void cancellationInAnotherServiceIsAuthoritativeEvenForAnAlreadyScheduledTask() {
        failAlways();
        service.addStock("SKU-1", 3);
        var persistence = new JpaInventoryPersistence(database.entityManagerFactory());
        try (var other = new InventoryApplicationService(persistence.inventories(), persistence.reservations(),
                persistence.settlements(), persistence.operations(), clock,
                persistence.notifications(clock, (sku, units) -> { }))) {
            other.addStock("SKU-1", 10);
        }

        scheduler.runAll();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(single().state()).isEqualTo(StockAlertState.CANCELLED);
    }

    @Test
    void manualReprocessingWorksOnlyForTheCurrentStockCycle() {
        failAlways();
        service.addStock("SKU-1", 3);
        scheduler.runAll();
        var id = single().id();
        healthyListener();

        assertThat(delivery.reprocess(id)).isTrue();
        assertThat(single().state()).isEqualTo(StockAlertState.DELIVERED);
        assertThat(single().attempts()).isEqualTo(1);
        assertThat(delivery.reprocess(id)).isFalse();
        assertThat(received).containsExactly("SKU-1:3");
    }

    @Test
    void aDeadLetterSupersededByReplenishmentIsPreservedButCannotBeReprocessed() {
        failAlways();
        service.addStock("SKU-1", 3);
        scheduler.runAll();
        var original = single();
        healthyListener();
        service.addStock("SKU-1", 10);

        assertThat(delivery.reprocess(original.id())).isFalse();
        assertThat(database.alerts().deadLetters()).containsExactly(original);
        assertThat(calls.get()).isEqualTo(6);
    }

    @Test
    void queuedDeliveryCanResumeFromH2WithANewDispatcher() {
        failAlways();
        service.addStock("SKU-1", 3);
        delivery.close();
        var replacementScheduler = new ManualRetryScheduler(clock);
        try (var replacement = new StockAlertDelivery(database.alerts(), clock,
                (sku, units) -> received.add(sku + ":" + units), replacementScheduler, () -> 0.5)) {
            replacement.resumePending();
            replacement.resumePending();
            assertThat(replacementScheduler.pendingCount()).isEqualTo(1);
            assertThat(replacementScheduler.runAll()).isEqualTo(1);
            assertThat(single().state()).isEqualTo(StockAlertState.DELIVERED);
            assertThat(single().attempts()).isEqualTo(2);
        }
    }

    @Test
    void twoDispatchersCannotSkipTheBackoffWithDuplicateScheduledAttempts() {
        failAlways();
        service.addStock("SKU-1", 3);
        var otherScheduler = new ManualRetryScheduler(clock);
        try (var other = new StockAlertDelivery(database.alerts(), clock,
                (sku, units) -> { calls.incrementAndGet(); throw new IllegalStateException("Duplicate worker"); },
                otherScheduler, () -> 0.5)) {
            other.resumePending();
            scheduler.runNext();
            otherScheduler.runNext();

            assertThat(calls.get()).isEqualTo(2);
            assertThat(single().attempts()).isEqualTo(2);
            assertThat(single().state()).isEqualTo(StockAlertState.RETRY_WAIT);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anInFlightListenerDoesNotBlockReplenishmentAndCannotRestartCancelledRetries(boolean fail) throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        handler.set((sku, units) -> {
            entered.countDown();
            await(resume);
            if (fail) {
                throw new IllegalStateException("Failure after replenishment");
            }
        });
        var persistence = new JpaInventoryPersistence(database.entityManagerFactory());
        try (var other = new InventoryApplicationService(persistence.inventories(), persistence.reservations(),
                persistence.settlements(), persistence.operations(), clock,
                persistence.notifications(clock, (sku, units) -> { }));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var initial = executor.submit(() -> service.addStock("SKU-1", 3));
            try {
                await(entered);
                var replenishment = executor.submit(() -> other.addStock("SKU-1", 10));
                replenishment.get(2, TimeUnit.SECONDS);
                resume.countDown();
                initial.get(5, TimeUnit.SECONDS);

                assertThat(single().state()).isEqualTo(StockAlertState.CANCELLED);
                assertThat(service.available("SKU-1")).isEqualTo(13);
                assertThat(scheduler.pendingCount()).isZero();
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void aRolledBackProductOperationDoesNotDeliverAnOrphanAlert() {
        var fail = new AtomicBoolean(true);
        var injected = new InventoryOperationExecutor() {
            @Override
            public <T> T execute(String sku, Function<Optional<ProductInventory>, T> operation) {
                return database.operations().execute(sku, inventory -> {
                    var result = operation.apply(inventory);
                    if (fail.getAndSet(false)) {
                        throw new RuntimeException("Injected failure before commit");
                    }
                    return result;
                });
            }
        };
        try (var failing = newService(injected, delivery)) {
            assertThatThrownBy(() -> failing.addStock("SKU-1", 3)).isInstanceOf(RuntimeException.class);
            assertThat(database.inventories().findBySku("SKU-1").orElseThrow().onHand()).isZero();
            assertThat(database.alerts().findBySku("SKU-1")).isEmpty();
            assertThat(calls.get()).isZero();
            failing.addStock("SKU-1", 3);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(single().cycle()).isEqualTo(1);
        }
    }

    @Test
    void theListenerCanReadAndCloseTheServiceWithoutHoldingItsTransactionOrLifecycleLock() {
        handler.set((sku, units) -> {
            assertThat(service.available(sku)).isEqualTo(units);
            service.close();
        });
        service.addStock("SKU-1", 3);

        assertThat(calls.get()).isEqualTo(1);
        assertThatIllegalStateException().isThrownBy(() -> service.available("SKU-1"));
    }

    @Test
    void concurrentServicesGenerateAndDeliverOneAlertForTheSameStockCycle() throws Exception {
        service.addStock("SKU-1", 10);
        var persistence = new JpaInventoryPersistence(database.entityManagerFactory());
        var otherScheduler = new ManualRetryScheduler(clock);
        var otherDelivery = new StockAlertDelivery(persistence.alerts(), clock, (sku, units) -> {
            calls.incrementAndGet();
            handler.get().onLowStock(sku, units);
        }, otherScheduler, () -> 0.5);
        try (var other = new InventoryApplicationService(persistence.inventories(), persistence.reservations(),
                persistence.settlements(), persistence.operations(), clock,
                new StockAlertNotifications(persistence.alerts(), otherDelivery));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var requests = IntStream.range(0, 10).mapToObj(index -> executor.submit(() -> {
                await(start);
                return (index % 2 == 0 ? service : other).reserve("ORDER-" + index, "SKU-1", 1);
            })).toList();
            start.countDown();
            for (var request : requests) {
                request.get(5, TimeUnit.SECONDS);
            }

            assertThat(calls.get()).isEqualTo(1);
            assertThat(received).containsExactly("SKU-1:5");
            assertThat(single().state()).isEqualTo(StockAlertState.DELIVERED);
            assertThat(service.available("SKU-1")).isZero();
        }
    }

    private InventoryApplicationService newService(InventoryOperationExecutor operations, StockAlertDelivery dispatcher) {
        return new InventoryApplicationService(database.inventories(), database.reservations(), database.settlements(),
                operations, clock, new StockAlertNotifications(database.alerts(), dispatcher));
    }

    private StockAlert single() {
        var alerts = database.alerts().findBySku("SKU-1");
        assertThat(alerts).hasSize(1);
        return alerts.getFirst();
    }

    private void healthyListener() {
        handler.set((sku, units) -> received.add(sku + ":" + units));
    }

    private void failAlways() {
        handler.set((sku, units) -> { throw new IllegalStateException("provider-down-" + calls.get()); });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test synchronization timed out");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interruption);
        }
    }
}

package com.store.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.domain.ProductInventory;
import com.store.inventory.domain.ReservationState;
import com.store.inventory.infrastructure.jpa.H2InventoryDatabase;
import com.store.inventory.infrastructure.jpa.JpaInventoryPersistence;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class InventoryConcurrencyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
    private H2InventoryDatabase database;
    private InventoryApplicationService first;
    private InventoryApplicationService second;

    @BeforeEach
    void initializeServicesWithIndependentContextsOnTheSameDatabase() {
        database = new H2InventoryDatabase();
        first = new InventoryApplicationService(database.inventories(), database.reservations(), database.settlements(),
                database.operations(), CLOCK, (sku, available) -> { });
        var independent = new JpaInventoryPersistence(database.entityManagerFactory());
        second = new InventoryApplicationService(independent.inventories(), independent.reservations(),
                independent.settlements(), independent.operations(), CLOCK, (sku, available) -> { });
        first.registerProduct("SKU-1", ProductCategory.STANDARD);
        first.registerProduct("SKU-2", ProductCategory.STANDARD);
        first.addStock("SKU-1", 10);
        first.addStock("SKU-2", 10);
    }

    @AfterEach
    void closeDatabase() {
        first.close();
        second.close();
        database.close();
    }

    @Test
    void differentOrdersAcrossServicesCannotOversell() throws Exception {
        var tasks = IntStream.range(0, 20).<Callable<Boolean>>mapToObj(index -> () -> {
            try {
                service(index).reserve("ORDER-" + index, "SKU-1", 1);
                return true;
            } catch (InsufficientStockException expected) {
                return false;
            }
        }).toList();

        assertThat(runTogether(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(10);
        assertThat(first.available("SKU-1")).isZero();
        assertThat(database.reservations().findBySku("SKU-1")).hasSize(10);
    }

    @Test
    void identicalReplaysAcrossServicesReturnOneReservation() throws Exception {
        var tasks = IntStream.range(0, 12).<Callable<Reservation>>mapToObj(index ->
                () -> service(index).reserve("ORDER-1", "SKU-1", 10)).toList();
        var results = runTogether(tasks);

        assertThat(results).allMatch(results.getFirst()::equals);
        assertThat(database.reservations().findBySku("SKU-1")).hasSize(1);
        assertThat(first.available("SKU-1")).isZero();
    }

    @Test
    void oneOrderIdentifierCannotConsumeTwoProducts() throws Exception {
        var tasks = IntStream.range(0, 12).<Callable<Boolean>>mapToObj(index -> () -> {
            try {
                service(index).reserve("SAME-ORDER", index % 2 == 0 ? "SKU-1" : "SKU-2", 2);
                return true;
            } catch (IllegalArgumentException expected) {
                return false;
            }
        }).toList();
        var results = runTogether(tasks);
        var winner = database.reservations().findByOrderId("SAME-ORDER").orElseThrow();

        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(6);
        assertThat(database.reservations().findBySku("SKU-1").size()
                + database.reservations().findBySku("SKU-2").size()).isEqualTo(1);
        assertThat(first.available(winner.sku())).isEqualTo(8);
        assertThat(first.available(winner.sku().equals("SKU-1") ? "SKU-2" : "SKU-1")).isEqualTo(10);
    }

    @Test
    void mixedReplenishmentsReservationsAndConfirmationsConserveUnits() throws Exception {
        first.reserve("PAID", "SKU-1", 3);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int index = 0; index < 10; index++) {
            int id = index;
            tasks.add(() -> { service(id).addStock("SKU-1", 2); return true; });
            tasks.add(() -> { service(id).confirm("PAID"); return true; });
            tasks.add(() -> {
                try {
                    service(id).reserve("NEW-" + id, "SKU-1", 1);
                    return true;
                } catch (InsufficientStockException expected) {
                    return false;
                }
            });
        }
        runTogether(tasks);
        var held = database.reservations().findBySku("SKU-1").stream()
                .filter(reservation -> reservation.stateAt(CLOCK.instant())
                        == ReservationState.ACTIVE)
                .mapToInt(reservation -> reservation.quantity()).sum();

        assertThat(database.inventories().findBySku("SKU-1").orElseThrow().onHand()).isEqualTo(27);
        assertThat(first.available("SKU-1")).isEqualTo(27 - held);
        assertThat(held).isLessThanOrEqualTo(10);
    }

    @Test
    void availabilityCannotCombineOldStockWithANewConfirmedState() throws Exception {
        first.reserve("PAID", "SKU-1", 3);
        var snapshotRead = new CountDownLatch(1);
        var continueReading = new CountDownLatch(1);
        var reader = serviceWith(paused(database.operations(), snapshotRead, continueReading), () -> { });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var availability = executor.submit(() -> reader.available("SKU-1"));
            try {
                await(snapshotRead);
                var confirmation = executor.submit(() -> second.confirm("PAID"));
                assertThatThrownBy(() -> confirmation.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                continueReading.countDown();

                assertThat(availability.get(5, TimeUnit.SECONDS)).isEqualTo(7);
                confirmation.get(5, TimeUnit.SECONDS);
                assertThat(second.available("SKU-1")).isEqualTo(7);
            } finally {
                continueReading.countDown();
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void aLockedProductDoesNotBlockAnUnrelatedProduct() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> database.operations().execute("SKU-1", inventory -> {
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                await(locked);
                var unrelated = executor.submit(() -> second.reserve("OTHER", "SKU-2", 1));
                assertThat(unrelated.get(2, TimeUnit.SECONDS).sku()).isEqualTo("SKU-2");
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aProductRegisteredDuringAnUnknownProductRequestCannotBypassItsLock() throws Exception {
        var unknownRead = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var requester = serviceWith(paused(database.operations(), unknownRead, resume), () -> { });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = executor.submit(() -> requester.reserve("NEW-ORDER", "NEW-SKU", 1));
            try {
                await(unknownRead);
                second.registerProduct("NEW-SKU", ProductCategory.STANDARD);
                second.addStock("NEW-SKU", 1);
                resume.countDown();

                assertThatThrownBy(() -> request.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(InsufficientStockException.class);
                assertThat(database.reservations().findByOrderId("NEW-ORDER")).isEmpty();
                assertThat(second.reserve("NEW-ORDER", "NEW-SKU", 1).quantity()).isEqualTo(1);
            } finally {
                resume.countDown();
            }
        } finally {
            requester.close();
        }
    }

    @Test
    void closingTheServiceWaitsForAnInFlightOperation() throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var cleaned = new AtomicInteger();
        var closingService = serviceWith(paused(database.operations(), entered, resume), cleaned::incrementAndGet);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = executor.submit(() -> closingService.available("SKU-1"));
            try {
                await(entered);
                var closing = executor.submit(closingService::close);
                assertThatThrownBy(() -> closing.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(cleaned.get()).isZero();
                resume.countDown();

                assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(10);
                closing.get(5, TimeUnit.SECONDS);
                assertThat(cleaned.get()).isEqualTo(1);
                assertThatIllegalStateException().isThrownBy(() -> closingService.available("SKU-1"));
            } finally {
                resume.countDown();
            }
        } finally {
            closingService.close();
        }
    }

    private InventoryApplicationService service(int index) {
        return index % 2 == 0 ? first : second;
    }

    private InventoryApplicationService serviceWith(InventoryOperationExecutor operations, Runnable cleanup) {
        return new InventoryApplicationService(database.inventories(), database.reservations(), database.settlements(),
                operations, CLOCK, (sku, available) -> { }, cleanup);
    }

    private static InventoryOperationExecutor paused(InventoryOperationExecutor delegate,
            CountDownLatch entered, CountDownLatch resume) {
        return new InventoryOperationExecutor() {
            @Override
            public <T> T execute(String sku, Function<Optional<ProductInventory>, T> operation) {
                return delegate.execute(sku, inventory -> {
                    entered.countDown();
                    await(resume);
                    return operation.apply(inventory);
                });
            }
        };
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                await(start);
                return task.call();
            })).toList();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test synchronization timed out");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test synchronization was interrupted", interruption);
        }
    }
}

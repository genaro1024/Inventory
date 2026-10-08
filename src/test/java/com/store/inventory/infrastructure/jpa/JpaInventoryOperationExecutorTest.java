package com.store.inventory.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.domain.Category;
import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.Product;
import com.store.inventory.domain.ProductInventory;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JpaInventoryOperationExecutorTest extends JpaRepositoryTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private final ProductInventory initial = new ProductInventory(new Product("SKU-1", Category.STANDARD), 10);

    @BeforeEach
    void initializeProduct() {
        database.inventories().insert(initial);
    }

    @Test
    void aFailedOperationRollsBackAllRepositoriesAndReleasesTheLock() {
        assertThatThrownBy(() -> database.operations().execute("SKU-1", inventory -> {
            database.inventories().replace(initial, initial.sell(3));
            database.reservations().insert(OrderReservation.create("ORDER-1", initial.product(), 3, NOW));
            throw new IllegalStateException("Injected failure after both writes");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(database.inventories().findBySku("SKU-1")).contains(initial);
        assertThat(database.reservations().findByOrderId("ORDER-1")).isEmpty();
        var afterRollback = database.operations().execute("SKU-1", inventory -> inventory.orElseThrow());
        assertThat(afterRollback).isEqualTo(initial);
    }

    @Test
    void aRetryStartsFromAFreshTransactionWithoutPreviousWrites() {
        var attempts = new AtomicInteger();
        database.operations().execute("SKU-1", inventory -> {
            int attempt = attempts.incrementAndGet();
            database.reservations().insert(OrderReservation.create("ORDER-" + attempt, initial.product(), 1, NOW));
            if (attempt == 1) {
                throw new RetryOperationException(new IllegalStateException("Injected conflict"));
            }
            return null;
        });

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(database.reservations().findByOrderId("ORDER-1")).isEmpty();
        assertThat(database.reservations().findByOrderId("ORDER-2")).isPresent();
    }

    @Test
    void retriesAreBoundedAndDoNotCommitFailedAttempts() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> database.operations().execute("SKU-1", inventory -> {
            int attempt = attempts.incrementAndGet();
            database.reservations().insert(OrderReservation.create("ORDER-" + attempt, initial.product(), 1, NOW));
            throw new RetryOperationException(new IllegalStateException("Repeated conflict"));
        })).isInstanceOf(RetryOperationException.class);

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(database.reservations().findBySku("SKU-1")).isEmpty();
    }

    @Test
    void settlementConflictRollsBackTheWholeOperationBeforeRetrying() {
        var reservation = OrderReservation.create("ORDER-1", initial.product(), 3, NOW);
        database.reservations().insert(reservation);
        var attempts = new AtomicInteger();
        database.operations().execute("SKU-1", inventory -> {
            var locked = inventory.orElseThrow();
            if (attempts.incrementAndGet() == 1) {
                database.inventories().replace(locked, locked.replenish(5));
            }
            return database.settlements().confirm(reservation, locked, NOW);
        });

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(database.inventories().findBySku("SKU-1")).contains(initial.sell(3));
        assertThat(database.reservations().findByOrderId("ORDER-1")).contains(reservation.confirmAt(NOW));
    }

    @Test
    void bulkUpdatesAreVisibleToLaterReadsInsideTheSameOperation() {
        var reservation = OrderReservation.create("ORDER-1", initial.product(), 3, NOW);
        database.reservations().insert(reservation);
        database.operations().execute("SKU-1", inventory -> {
            assertThat(database.reservations().findByOrderId("ORDER-1")).contains(reservation);
            database.reservations().replace(reservation, reservation.expireAt(reservation.expiresAt()));
            assertThat(database.reservations().findByOrderId("ORDER-1"))
                    .contains(reservation.expireAt(reservation.expiresAt()));
            return null;
        });
    }
}

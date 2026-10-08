package com.store.inventory.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.domain.Category;
import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.Product;
import com.store.inventory.domain.ProductInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;

class JpaReservationSettlementRepositoryTest extends JpaRepositoryTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00.123456789Z");
    private final ProductInventory inventory = new ProductInventory(new Product("SKU-1", Category.STANDARD), 10);
    private final OrderReservation reservation = OrderReservation.create("ORDER-1", inventory.product(), 3, NOW);

    @BeforeEach
    void insertRecords() {
        database.inventories().insert(inventory);
        database.reservations().insert(reservation);
    }

    @Test
    void commitsConfirmedOrderAndSoldStockTogether() {
        assertThat(database.settlements().confirm(reservation, inventory, NOW)).isTrue();

        assertThat(database.reservations().findByOrderId("ORDER-1")).contains(reservation.confirmAt(NOW));
        assertThat(database.inventories().findBySku("SKU-1")).contains(inventory.sell(3));
    }

    @Test
    void staleStockRollsBackTheReservationUpdate() {
        var replenished = inventory.replenish(5);
        database.inventories().replace(inventory, replenished);

        assertThat(database.settlements().confirm(reservation, inventory, NOW)).isFalse();
        assertThat(database.reservations().findByOrderId("ORDER-1")).contains(reservation);
        assertThat(database.inventories().findBySku("SKU-1")).contains(replenished);
        assertThat(database.settlements().confirm(reservation, replenished, NOW)).isTrue();
        assertThat(database.inventories().findBySku("SKU-1")).contains(replenished.sell(3));
    }

    @Test
    void staleReservationDoesNotModifyStock() {
        var expired = reservation.expireAt(reservation.expiresAt());
        database.reservations().replace(reservation, expired);

        assertThat(database.settlements().confirm(reservation, inventory, NOW)).isFalse();
        assertThat(database.reservations().findByOrderId("ORDER-1")).contains(expired);
        assertThat(database.inventories().findBySku("SKU-1")).contains(inventory);
    }

    @Test
    void retryingTheOriginalSnapshotsCannotSellTwice() {
        assertThat(database.settlements().confirm(reservation, inventory, NOW)).isTrue();
        assertThat(database.settlements().confirm(reservation, inventory, NOW)).isFalse();

        assertThat(database.inventories().findBySku("SKU-1")).contains(inventory.sell(3));
    }
}

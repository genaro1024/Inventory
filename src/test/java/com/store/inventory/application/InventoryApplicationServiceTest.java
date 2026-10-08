package com.store.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.store.inventory.domain.Category;
import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.Product;
import com.store.inventory.domain.ProductInventory;
import com.store.inventory.domain.ReservationState;
import com.store.inventory.infrastructure.jpa.H2InventoryDatabase;
import com.store.inventory.infrastructure.jpa.JpaProductInventoryRepository;
import com.store.inventory.infrastructure.jpa.JpaReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

class InventoryApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private H2InventoryDatabase database;
    private JpaProductInventoryRepository inventories;
    private JpaReservationRepository reservations;
    private final Product product = new Product("SKU-1", Category.STANDARD);

    @BeforeEach
    void initializeDatabase() {
        database = new H2InventoryDatabase();
        inventories = database.inventories();
        reservations = database.reservations();
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    void availabilityUsesStoredStockAndOnlyActiveReservations() {
        inventories.insert(new ProductInventory(product, 8));
        reservations.insert(OrderReservation.create("ACTIVE", product, 3, NOW));
        reservations.insert(OrderReservation.create("EXPIRED", product, 1, NOW.minusSeconds(900)));
        reservations.insert(OrderReservation.create("CONFIRMED", product, 2, NOW).confirmAt(NOW));
        var otherProduct = new Product("SKU-2", Category.STANDARD);
        inventories.insert(new ProductInventory(otherProduct, 5));
        reservations.insert(OrderReservation.create("OTHER", otherProduct, 5, NOW));

        assertThat(serviceAt(NOW).available("SKU-1")).isEqualTo(5);
        assertThat(serviceAt(NOW).available("UNKNOWN")).isZero();
    }

    @Test
    void exactDeadlineReleasesStockAndPersistsTheExpiredState() {
        inventories.insert(new ProductInventory(product, 10));
        var reservation = OrderReservation.create("ORDER-1", product, 3, NOW);
        reservations.insert(reservation);

        assertThat(serviceAt(reservation.expiresAt().minusNanos(1)).available("SKU-1")).isEqualTo(7);
        assertThat(serviceAt(reservation.expiresAt()).available("SKU-1")).isEqualTo(10);
        assertThat(reservations.findByOrderId("ORDER-1")).contains(reservation.expireAt(reservation.expiresAt()));
        assertThat(reservations.findByOrderId("ORDER-1").orElseThrow().state()).isEqualTo(ReservationState.EXPIRED);
    }

    @Test
    void rejectsInvalidSku() {
        assertThatIllegalArgumentException().isThrownBy(() -> serviceAt(NOW).available(null));
        assertThatIllegalArgumentException().isThrownBy(() -> serviceAt(NOW).available(" "));
    }

    private InventoryApplicationService serviceAt(Instant now) {
        return new InventoryApplicationService(inventories, reservations, database.settlements(),
                database.operations(), Clock.fixed(now, ZoneOffset.UTC), (sku, available) -> { });
    }
}

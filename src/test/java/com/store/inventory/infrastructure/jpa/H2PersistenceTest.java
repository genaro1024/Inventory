package com.store.inventory.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.domain.Category;
import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.Product;
import com.store.inventory.domain.ProductInventory;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class H2PersistenceTest extends JpaRepositoryTestSupport {

    @Test
    void storesCommittedRowsInTheDatabase() {
        database.inventories().insert(new ProductInventory(new Product("SKU-1", Category.STANDARD), 12));

        try (var manager = database.entityManagerFactory().createEntityManager()) {
            var storedStock = (Number) manager.createNativeQuery(
                    "select on_hand from product_inventory where sku = 'SKU-1'", Integer.class).getSingleResult();
            assertThat(storedStock.intValue()).isEqualTo(12);
        }
    }

    @Test
    void retainsNanosecondPrecisionAndCanUpdateTheRoundTripReservation() {
        var product = new Product("SKU-1", Category.STANDARD);
        database.inventories().insert(new ProductInventory(product, 5));
        var now = Instant.parse("2026-10-08T12:00:00.123456789Z");
        var original = OrderReservation.create("ORDER-1", product, 1, now);
        var repository = database.reservations();

        assertThat(repository.insert(original)).isTrue();
        assertThat(repository.findByOrderId("ORDER-1")).contains(original);
        assertThat(repository.replace(original, original.confirmAt(now))).isTrue();
        assertThat(repository.findByOrderId("ORDER-1")).contains(original.confirmAt(now));
    }

    @Test
    void foreignKeyFailureIsNotMistakenForADuplicateAndRollsBack() {
        var reservation = OrderReservation.create("ORDER-1", new Product("UNKNOWN", Category.STANDARD),
                1, Instant.parse("2026-10-08T12:00:00Z"));

        assertThatThrownBy(() -> database.reservations().insert(reservation)).isInstanceOf(RuntimeException.class);
        assertThat(database.reservations().findByOrderId("ORDER-1")).isEmpty();
        database.inventories().insert(new ProductInventory(new Product("UNKNOWN", Category.STANDARD), 5));
        assertThat(database.reservations().insert(reservation)).isTrue();
    }

    @Test
    void recreatingTheDatabaseStartsWithoutPreviousRows() {
        database.inventories().insert(new ProductInventory(new Product("SKU-1", Category.STANDARD), 5));
        database.close();
        database = new H2InventoryDatabase();

        assertThat(database.inventories().findBySku("SKU-1")).isEmpty();
        assertThat(database.reservations().findBySku("SKU-1")).isEmpty();
    }
}

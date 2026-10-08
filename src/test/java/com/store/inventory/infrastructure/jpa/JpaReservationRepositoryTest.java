package com.store.inventory.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.domain.Category;
import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.Product;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class JpaReservationRepositoryTest extends JpaRepositoryTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private JpaReservationRepository repository;

    @BeforeEach
    void initializeRepository() {
        repository = database.reservations();
        database.inventories().insert(new com.store.inventory.domain.ProductInventory(
                new Product("SKU-1", Category.STANDARD), 0));
        database.inventories().insert(new com.store.inventory.domain.ProductInventory(
                new Product("SKU-2", Category.STANDARD), 0));
    }

    @Test
    void startsEmptyAndQueriesOnlyTheRequestedProduct() {
        assertThat(repository.findByOrderId("ORDER-1")).isEmpty();
        assertThat(repository.findBySku("SKU-1")).isEmpty();
        var first = reservation("ORDER-1", "SKU-1");
        var second = reservation("ORDER-2", "SKU-2");
        repository.insert(first);
        repository.insert(second);

        assertThat(repository.findBySku("SKU-1")).containsExactly(first);
        assertThat(repository.findByOrderId("ORDER-2")).contains(second);
        assertThat(repository.findBySku("sku-1")).isEmpty();
    }

    @Test
    void duplicateOrderCannotReplaceItsOriginalProduct() {
        var original = reservation("ORDER-1", "SKU-1");
        repository.insert(original);

        assertThat(repository.insert(reservation("ORDER-1", "SKU-2"))).isFalse();
        assertThat(repository.findByOrderId("ORDER-1")).contains(original);
        assertThat(repository.findBySku("SKU-2")).isEmpty();
    }

    @Test
    void retainsConfirmedAndExpiredOrdersForIdempotency() {
        var confirmed = reservation("CONFIRMED", "SKU-1");
        var expired = reservation("EXPIRED", "SKU-1");
        repository.insert(confirmed);
        repository.insert(expired);
        var confirmedState = confirmed.confirmAt(NOW);
        var expiredState = expired.expireAt(expired.expiresAt());

        assertThat(repository.replace(confirmed, confirmedState)).isTrue();
        assertThat(repository.replace(expired, expiredState)).isTrue();
        assertThat(repository.findBySku("SKU-1")).containsExactlyInAnyOrder(confirmedState, expiredState);
        assertThat(repository.insert(reservation("CONFIRMED", "SKU-1"))).isFalse();
        assertThat(repository.insert(reservation("EXPIRED", "SKU-1"))).isFalse();
    }

    @Test
    void staleUpdateCannotOverwriteAConfirmedReservation() {
        var original = reservation("ORDER-1", "SKU-1");
        repository.insert(original);
        var confirmed = original.confirmAt(NOW);

        assertThat(repository.replace(original, confirmed)).isTrue();
        assertThat(repository.replace(original, original.expireAt(original.expiresAt()))).isFalse();
        assertThat(repository.findByOrderId("ORDER-1")).contains(confirmed);
    }

    @Test
    void replacementCannotChangeOrderIdentityOrTerms() {
        var original = reservation("ORDER-1", "SKU-1");
        repository.insert(original);

        assertThatIllegalArgumentException().isThrownBy(
                () -> repository.replace(original, reservation("ORDER-2", "SKU-1")));
        assertThatIllegalArgumentException().isThrownBy(
                () -> repository.replace(original, reservation("ORDER-1", "SKU-2")));
        assertThatIllegalArgumentException().isThrownBy(() -> repository.replace(original,
                OrderReservation.create("ORDER-1", new Product("SKU-1", Category.STANDARD), 2, NOW)));
        assertThatIllegalArgumentException().isThrownBy(() -> repository.replace(original,
                OrderReservation.create("ORDER-1", new Product("SKU-1", Category.STANDARD), 1, NOW.plusSeconds(1))));
        assertThat(repository.findByOrderId("ORDER-1")).contains(original);
    }

    @Test
    void queryResultsAreImmutableAndDoNotExposeALiveCollection() {
        var first = reservation("ORDER-1", "SKU-1");
        repository.insert(first);
        var snapshot = repository.findBySku("SKU-1");

        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
        repository.insert(reservation("ORDER-2", "SKU-1"));
        assertThat(snapshot).containsExactly(first);
        assertThat(repository.findBySku("SKU-1")).hasSize(2);
    }

    @Test
    void replacementDoesNotCreateAnUnknownOrderAndInstancesAreIsolated() {
        var original = reservation("ORDER-1", "SKU-1");
        assertThat(repository.replace(original, original.confirmAt(NOW))).isFalse();
        repository.insert(original);

        try (var other = new H2InventoryDatabase()) {
            assertThat(other.reservations().findByOrderId("ORDER-1")).isEmpty();
        }
    }

    @Test
    void concurrentOrdersForDifferentProductsCannotShareAnIdentifier() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.<Callable<Boolean>>of(
                    () -> repository.insert(reservation("ORDER-1", "SKU-1")),
                    () -> repository.insert(reservation("ORDER-1", "SKU-2")));
            var results = executor.invokeAll(tasks);

            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
            assertThat(repository.findBySku("SKU-1").size() + repository.findBySku("SKU-2").size()).isEqualTo(1);
        }
    }

    private static OrderReservation reservation(String orderId, String sku) {
        return OrderReservation.create(orderId, new Product(sku, Category.STANDARD), 1, NOW);
    }
}

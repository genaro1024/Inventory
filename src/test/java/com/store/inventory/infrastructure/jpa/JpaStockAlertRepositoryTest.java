package com.store.inventory.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.store.inventory.domain.Category;
import com.store.inventory.domain.Product;
import com.store.inventory.domain.ProductInventory;
import com.store.inventory.domain.notification.StockAlert;
import com.store.inventory.domain.notification.StockAlertState;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JpaStockAlertRepositoryTest extends JpaRepositoryTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00.123456789Z");
    private StockAlert alert;

    @BeforeEach
    void preparePendingAlert() {
        database.inventories().insert(new ProductInventory(new Product("SKU-1", Category.STANDARD), 3));
        alert = database.operations().execute("SKU-1", inventory ->
                database.alerts().evaluate("SKU-1", 3, true, NOW).alert().orElseThrow());
    }

    @Test
    void notificationEvaluationRequiresTheInventoryTransactionButDeliveryMustBeOutsideIt() {
        assertThatIllegalStateException().isThrownBy(() -> database.alerts().evaluate("SKU-1", 3, false, NOW));
        assertThatIllegalStateException().isThrownBy(() -> database.operations().execute("SKU-1", inventory ->
                database.alerts().claim(alert.id(), 0)));
        assertThat(database.alerts().findById(alert.id()).orElseThrow().state()).isEqualTo(StockAlertState.PENDING);
    }

    @Test
    void claimsAndCompletionsAreGuardedByTheAttemptNumber() {
        assertThat(database.alerts().claim(alert.id(), 0).orElseThrow().attempts()).isEqualTo(1);
        assertThat(database.alerts().claim(alert.id(), 0)).isEmpty();
        database.alerts().failed(alert.id(), 1, "Failure", NOW.plusSeconds(2));
        assertThat(database.alerts().delivered(alert.id(), 1)).isFalse();
        assertThat(database.alerts().claim(alert.id(), 0)).isEmpty();
        assertThat(database.alerts().claim(alert.id(), 1).orElseThrow().attempts()).isEqualTo(2);
        assertThat(database.alerts().failed(alert.id(), 1, "Old failure", NOW.plusSeconds(4))).isEmpty();
        assertThat(database.alerts().delivered(alert.id(), 1)).isFalse();
        assertThat(database.alerts().delivered(alert.id(), 2)).isTrue();
        assertThat(database.alerts().findById(alert.id()).orElseThrow().state()).isEqualTo(StockAlertState.DELIVERED);
    }

    @Test
    void pendingAlertsArePersistedAndCanBeEnumeratedForRecovery() {
        assertThat(database.alerts().awaitingDelivery()).containsExactly(alert);
        assertThat(database.alerts().findById(alert.id())).contains(alert);
        assertThat(database.alerts().deadLetters()).isEmpty();
        try (var manager = database.entityManagerFactory().createEntityManager()) {
            var count = (Number) manager.createNativeQuery("select count(*) from stock_alerts", Long.class).getSingleResult();
            assertThat(count.longValue()).isEqualTo(1);
        }
    }
}

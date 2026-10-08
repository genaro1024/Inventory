package com.store.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.store.inventory.application.InventoryApplicationService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InventoryFactoryTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsEmptyServicesWithoutSpringOrNotifications() {
        var alerts = new AtomicInteger();
        try (var first = (InventoryApplicationService) Inventory.create(clock, (sku, available) -> alerts.incrementAndGet());
                var second = (InventoryApplicationService) Inventory.create(clock, (sku, available) -> alerts.incrementAndGet())) {
            assertThat(first).isNotSameAs(second);
            assertThat(first.available("SKU-1")).isZero();
            assertThat(second.available("SKU-1")).isZero();
            assertThat(alerts.get()).isZero();
        }
    }

    @Test
    void requiresClockAndListener() {
        assertThatNullPointerException().isThrownBy(() -> Inventory.create(null, (sku, available) -> { }));
        assertThatNullPointerException().isThrownBy(() -> Inventory.create(clock, null));
    }

    @Test
    void releasesDatabaseResourcesAndRejectsUseAfterClosing() {
        var service = (InventoryApplicationService) Inventory.create(clock, (sku, available) -> { });
        service.close();
        service.close();

        assertThatIllegalStateException().isThrownBy(() -> service.available("SKU-1"));
    }
}

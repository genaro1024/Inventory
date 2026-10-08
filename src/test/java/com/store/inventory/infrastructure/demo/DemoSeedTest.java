package com.store.inventory.infrastructure.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.store.inventory.Inventory;
import com.store.inventory.application.InventoryApplicationService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DemoSeedTest {

    @Test
    void loadsAllCategoriesAndPaidOrdersOnlyThroughThePublicService() {
        var clock = new DemoClock(Instant.parse("2026-10-08T12:00:00Z"));
        try (var service = (InventoryApplicationService) Inventory.create(clock, (sku, available) -> { })) {
            assertThat(service.available("DEMO-STANDARD")).isZero();
            var summary = new DemoInventorySeed().load(service);

            assertThat(summary.products()).hasSize(5);
            assertThat(summary.activeOrders()).hasSize(3);
            assertThat(summary.paidOrders()).hasSize(3);
            assertThat(service.available("DEMO-STANDARD")).isEqualTo(15);
            assertThat(service.available("DEMO-PREORDER")).isEqualTo(22);
            assertThat(service.available("DEMO-FLASH")).isEqualTo(4);
            assertThat(service.available("DEMO-LOW")).isEqualTo(4);
            assertThat(service.available("DEMO-EMPTY")).isZero();
            clock.advance(Duration.ofMinutes(16));
            assertThat(service.available("DEMO-STANDARD")).isEqualTo(18);
            assertThat(service.available("DEMO-FLASH")).isEqualTo(6);
            assertThat(service.available("DEMO-PREORDER")).isEqualTo(22);
            clock.advance(Duration.ofHours(24));
            assertThat(service.available("DEMO-PREORDER")).isEqualTo(27);
            service.confirm("DEMO-STANDARD-PAID");
            assertThat(service.available("DEMO-STANDARD")).isEqualTo(18);
        }
    }

    @Test
    void demoClockIsSharedAcrossZonesAndCannotMoveBackwards() {
        var start = Instant.parse("2026-10-08T12:00:00Z");
        var clock = new DemoClock(start);
        var shifted = clock.withZone(ZoneOffset.ofHours(-6));
        clock.advance(Duration.ofMinutes(1));

        assertThat(shifted.instant()).isEqualTo(start.plusSeconds(60));
        assertThat(shifted.getZone()).isEqualTo(ZoneOffset.ofHours(-6));
        assertThatIllegalArgumentException().isThrownBy(() -> clock.advance(Duration.ofSeconds(-1)));
    }
}

package com.store.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.OrderReservation;
import com.store.inventory.infrastructure.jpa.H2InventoryDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ProductStockServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private H2InventoryDatabase database;
    private InventoryApplicationService service;

    @BeforeEach
    void createService() {
        database = new H2InventoryDatabase();
        service = new InventoryApplicationService(database.inventories(), database.reservations(), database.settlements(),
                database.operations(), Clock.fixed(NOW, ZoneOffset.UTC), (sku, available) -> { });
    }

    @AfterEach
    void closeDatabase() {
        service.close();
        database.close();
    }

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void registersEveryCategoryWithZeroStock(ProductCategory category) {
        service.registerProduct("SKU-1", category);

        var stored = database.inventories().findBySku("SKU-1").orElseThrow();
        assertThat(stored.product().category().name()).isEqualTo(category.name());
        assertThat(stored.onHand()).isZero();
        assertThat(service.available("SKU-1")).isZero();
    }

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void rejectsDuplicateRegistrationWithoutChangingStockCategoryOrReservations(ProductCategory category) {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        var original = database.inventories().findBySku("SKU-1").orElseThrow();
        var reservation = OrderReservation.create("ORDER-1", original.product(), 3, NOW);
        database.reservations().insert(reservation);

        assertThatIllegalArgumentException().isThrownBy(() -> service.registerProduct("SKU-1", category))
                .withMessage("El producto ya existe");
        assertThat(database.inventories().findBySku("SKU-1")).contains(original);
        assertThat(database.reservations().findByOrderId("ORDER-1")).contains(reservation);
        assertThat(service.available("SKU-1")).isEqualTo(7);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidIdentifiersInEveryProductOperation(String sku) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.registerProduct(sku, ProductCategory.STANDARD));
        assertThatIllegalArgumentException().isThrownBy(() -> service.addStock(sku, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> service.available(sku));
    }

    @Test
    void rejectsMissingCategoryWithoutRegisteringTheProduct() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.registerProduct("SKU-1", null));

        assertThat(database.inventories().findBySku("SKU-1")).isEmpty();
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        assertThat(service.available("SKU-1")).isZero();
    }

    @Test
    void distinguishesCaseAndPreservesIdentifierSpaces() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.registerProduct("sku-1", ProductCategory.STANDARD);
        service.registerProduct(" SKU-1 ", ProductCategory.STANDARD);
        service.addStock("SKU-1", 3);
        service.addStock("sku-1", 5);
        service.addStock(" SKU-1 ", 7);

        assertThat(service.available("SKU-1")).isEqualTo(3);
        assertThat(service.available("sku-1")).isEqualTo(5);
        assertThat(service.available(" SKU-1 ")).isEqualTo(7);
        assertThat(service.available("Sku-1")).isZero();
    }

    @Test
    void accumulatesReplenishmentsAndDoesNotApplyOrderLimitsToWarehouseStock() {
        service.registerProduct("FLASH-1", ProductCategory.FLASH_SALE);
        service.addStock("FLASH-1", 10);
        service.addStock("FLASH-1", 5);

        assertThat(service.available("FLASH-1")).isEqualTo(15);
        assertThat(database.inventories().findBySku("FLASH-1").orElseThrow().onHand()).isEqualTo(15);
    }

    @Test
    void replenishmentPreservesActiveReservationsAndIncreasesOnlyFreeUnits() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        var product = database.inventories().findBySku("SKU-1").orElseThrow().product();
        var reservation = OrderReservation.create("ORDER-1", product, 3, NOW);
        database.reservations().insert(reservation);

        service.addStock("SKU-1", 5);

        assertThat(service.available("SKU-1")).isEqualTo(12);
        assertThat(database.reservations().findByOrderId("ORDER-1")).contains(reservation);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveStockWithoutChangingExistingUnits(int quantity) {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        assertThatIllegalArgumentException().isThrownBy(() -> service.addStock("SKU-1", quantity));
        assertThat(service.available("SKU-1")).isEqualTo(10);
    }

    @Test
    void unknownProductHasNoStockAndCannotBeReplenished() {
        assertThat(service.available("UNKNOWN")).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> service.addStock("UNKNOWN", 5));
        assertThat(database.inventories().findBySku("UNKNOWN")).isEmpty();
    }

    @Test
    void overflowingStockIsRejectedWithoutChangingTheDatabase() {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", Integer.MAX_VALUE);

        assertThatIllegalArgumentException().isThrownBy(() -> service.addStock("SKU-1", 1));
        assertThat(service.available("SKU-1")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void simultaneousReplenishmentsDoNotLoseUnits() throws Exception {
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                start.await();
                service.addStock("SKU-1", 3);
                return null;
            })).toList();
            start.countDown();
            for (var result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(service.available("SKU-1")).isEqualTo(24);
    }
}

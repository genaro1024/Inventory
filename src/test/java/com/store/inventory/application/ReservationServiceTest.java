package com.store.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ProductInventory;
import com.store.inventory.infrastructure.jpa.H2InventoryDatabase;
import java.time.Clock;
import java.time.Duration;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00.123456789Z");
    private H2InventoryDatabase database;
    private InventoryApplicationService service;

    @BeforeEach
    void initializeService() {
        database = new H2InventoryDatabase();
        service = serviceAt(NOW);
        service.registerProduct("STANDARD", ProductCategory.STANDARD);
        service.registerProduct("PRE_ORDER", ProductCategory.PRE_ORDER);
        service.registerProduct("FLASH_SALE", ProductCategory.FLASH_SALE);
        service.addStock("STANDARD", 10);
        service.addStock("PRE_ORDER", 100);
        service.addStock("FLASH_SALE", 10);
    }

    @AfterEach
    void closeDatabase() {
        service.close();
        database.close();
    }

    @ParameterizedTest
    @CsvSource({"STANDARD,15", "PRE_ORDER,1440", "FLASH_SALE,5"})
    void createsAReservationUsingTheCategoryDeadline(String sku, long minutes) {
        var response = service.reserve("ORDER-1", sku, 2);

        assertThat(response.orderId()).isEqualTo("ORDER-1");
        assertThat(response.sku()).isEqualTo(sku);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(minutes)));
        var stored = database.reservations().findByOrderId("ORDER-1").orElseThrow();
        assertThat(stored.expiresAt()).isEqualTo(response.expiresAt());
        assertThat(service.available(sku)).isEqualTo(sku.equals("PRE_ORDER") ? 98 : 8);
    }

    @Test
    void reservingAllAvailableUnitsLeavesZeroWithoutSellingThem() {
        service.reserve("ORDER-1", "STANDARD", 10);

        assertThat(service.available("STANDARD")).isZero();
        assertThat(database.inventories().findBySku("STANDARD").orElseThrow().onHand()).isEqualTo(10);
    }

    @Test
    void replayReturnsTheOriginalDeadlineEvenWhenNoFreeStockRemains() {
        var original = service.reserve("ORDER-1", "STANDARD", 10);
        var later = serviceAt(NOW.plusSeconds(30));

        assertThat(later.reserve("ORDER-1", "STANDARD", 10)).isEqualTo(original);
        assertThat(later.available("STANDARD")).isZero();
        assertThat(database.reservations().findBySku("STANDARD")).hasSize(1);
    }

    @Test
    void changedQuantityOrProductIsRejectedWithoutChangingTheOriginal() {
        var original = service.reserve("ORDER-1", "STANDARD", 3);

        assertThatIllegalArgumentException().isThrownBy(() -> service.reserve("ORDER-1", "STANDARD", 2));
        assertThatIllegalArgumentException().isThrownBy(() -> service.reserve("ORDER-1", "FLASH_SALE", 3));
        assertThatIllegalArgumentException().isThrownBy(() -> service.reserve("ORDER-1", "UNKNOWN", 3));
        assertThat(service.reserve("ORDER-1", "STANDARD", 3)).isEqualTo(original);
        assertThat(service.available("STANDARD")).isEqualTo(7);
        assertThat(service.available("FLASH_SALE")).isEqualTo(10);
    }

    @Test
    void orderIdentifiersRemainCaseSensitiveAndAreNotTrimmed() {
        service.reserve("ORDER-1", "STANDARD", 1);
        service.reserve("order-1", "STANDARD", 1);
        service.reserve(" ORDER-1 ", "STANDARD", 1);

        assertThat(database.reservations().findBySku("STANDARD")).hasSize(3);
        assertThat(service.available("STANDARD")).isEqualTo(7);
    }

    @Test
    void insufficientStockDoesNotConsumeTheOrderIdentifier() {
        assertThatThrownBy(() -> service.reserve("ORDER-1", "STANDARD", 11))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(database.reservations().findByOrderId("ORDER-1")).isEmpty();
        assertThat(service.available("STANDARD")).isEqualTo(10);

        service.addStock("STANDARD", 5);
        assertThat(service.reserve("ORDER-1", "STANDARD", 11).quantity()).isEqualTo(11);
        assertThat(service.available("STANDARD")).isEqualTo(4);
    }

    @Test
    void unknownProductIsRejectedUsingThePublicStockException() {
        assertThatThrownBy(() -> service.reserve("ORDER-1", "UNKNOWN", 1))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("available 0");
        assertThat(database.reservations().findByOrderId("ORDER-1")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10})
    void flashSaleLimitIsCheckedBeforeStockAndDoesNotConsumeTheOrder(int stock) {
        var current = database.inventories().findBySku("FLASH_SALE").orElseThrow();
        database.inventories().replace(current, new ProductInventory(current.product(), stock));

        assertThatThrownBy(() -> service.reserve("ORDER-1", "FLASH_SALE", 3))
                .isInstanceOf(OrderLimitExceededException.class);
        assertThat(database.reservations().findByOrderId("ORDER-1")).isEmpty();
        assertThat(service.available("FLASH_SALE")).isEqualTo(stock);
        if (stock > 0) {
            assertThat(service.reserve("ORDER-1", "FLASH_SALE", 2).quantity()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidOrderIdentifiers(String orderId) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.reserve(orderId, "STANDARD", 1));
        assertThat(database.reservations().findBySku("STANDARD")).isEmpty();
        assertThat(service.available("STANDARD")).isEqualTo(10);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidProductIdentifiers(String sku) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.reserve("ORDER-1", sku, 1));
        assertThat(database.reservations().findByOrderId("ORDER-1")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveQuantitiesWithoutReserving(int quantity) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.reserve("ORDER-1", "STANDARD", quantity));
        assertThat(database.reservations().findByOrderId("ORDER-1")).isEmpty();
        assertThat(service.available("STANDARD")).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void expiredOrdersCannotBeReactivatedButTheirUnitsCanBeReservedByANewOrder(long nanosAfterDeadline) {
        var original = service.reserve("ORDER-1", "STANDARD", 10);
        var later = serviceAt(original.expiresAt().plusNanos(nanosAfterDeadline));

        assertThatIllegalStateException().isThrownBy(() -> later.reserve("ORDER-1", "STANDARD", 10));
        assertThat(later.available("STANDARD")).isEqualTo(10);
        assertThat(later.reserve("ORDER-2", "STANDARD", 10).orderId()).isEqualTo("ORDER-2");
        assertThat(later.available("STANDARD")).isZero();
        assertThat(database.reservations().findByOrderId("ORDER-1").orElseThrow().expiresAt())
                .isEqualTo(original.expiresAt());
    }

    @Test
    void replayImmediatelyBeforeExpirationRemainsValid() {
        var original = service.reserve("ORDER-1", "STANDARD", 3);

        assertThat(serviceAt(original.expiresAt().minusNanos(1)).reserve("ORDER-1", "STANDARD", 3))
                .isEqualTo(original);
    }

    @Test
    void confirmedOrderReplayDoesNotReserveOrSellAgainAfterItsOriginalDeadline() {
        var response = service.reserve("ORDER-1", "STANDARD", 3);
        var stored = database.reservations().findByOrderId("ORDER-1").orElseThrow();
        database.reservations().replace(stored, stored.confirmAt(NOW));
        var inventory = database.inventories().findBySku("STANDARD").orElseThrow();
        database.inventories().replace(inventory, new ProductInventory(inventory.product(), 7));
        var later = serviceAt(response.expiresAt().plus(Duration.ofDays(1)));

        assertThat(later.reserve("ORDER-1", "STANDARD", 3)).isEqualTo(response);
        assertThat(later.available("STANDARD")).isEqualTo(7);
        assertThat(database.reservations().findBySku("STANDARD")).hasSize(1);
    }

    @Test
    void simultaneousIdenticalRequestsShareOneReservation() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                start.await();
                return service.reserve("ORDER-1", "STANDARD", 10);
            })).toList();
            start.countDown();
            var first = requests.getFirst().get(10, TimeUnit.SECONDS);
            for (var request : requests) {
                assertThat(request.get(10, TimeUnit.SECONDS)).isEqualTo(first);
            }
        }

        assertThat(database.reservations().findBySku("STANDARD")).hasSize(1);
        assertThat(service.available("STANDARD")).isZero();
    }

    @Test
    void competingOrdersCannotReserveMoreThanAvailableInOneServiceInstance() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                try {
                    service.reserve("ORDER-" + index, "STANDARD", 10);
                    return true;
                } catch (InsufficientStockException expected) {
                    return false;
                }
            })).toList();
            start.countDown();

            assertThat(java.util.List.of(requests.get(0).get(10, TimeUnit.SECONDS),
                    requests.get(1).get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }

        assertThat(database.reservations().findBySku("STANDARD")).hasSize(1);
        assertThat(service.available("STANDARD")).isZero();
    }

    @Test
    void competingProductsCannotShareAnOrderIdentifier() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = java.util.List.of("STANDARD", "PRE_ORDER").stream()
                    .map(sku -> executor.submit(() -> {
                        start.await();
                        try {
                            return service.reserve("ORDER-1", sku, 2);
                        } catch (IllegalArgumentException expected) {
                            return null;
                        }
                    })).toList();
            start.countDown();
            var first = requests.get(0).get(10, TimeUnit.SECONDS);
            var second = requests.get(1).get(10, TimeUnit.SECONDS);
            assertThat(first == null ^ second == null).isTrue();
            var winner = first != null ? first : second;
            assertThat(service.available("STANDARD")).isEqualTo(winner.sku().equals("STANDARD") ? 8 : 10);
            assertThat(service.available("PRE_ORDER")).isEqualTo(winner.sku().equals("PRE_ORDER") ? 98 : 100);
        }
    }

    private InventoryApplicationService serviceAt(Instant now) {
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        return new InventoryApplicationService(database.inventories(), database.reservations(), database.settlements(),
                database.operations(), clock, database.notifications(clock, (sku, available) -> { }));
    }
}

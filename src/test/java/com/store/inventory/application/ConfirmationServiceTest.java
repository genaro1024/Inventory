package com.store.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ReservationState;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class ConfirmationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00.123456789Z");
    private H2InventoryDatabase database;
    private InventoryApplicationService service;

    @BeforeEach
    void initializeService() {
        database = new H2InventoryDatabase();
        service = serviceAt(NOW);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
    }

    @AfterEach
    void closeDatabase() {
        service.close();
        database.close();
    }

    @Test
    void confirmationSellsUnitsAndKeepsAvailabilityUnchanged() {
        service.reserve("ORDER-1", "SKU-1", 3);
        service.reserve("ORDER-2", "SKU-1", 2);
        assertThat(service.available("SKU-1")).isEqualTo(5);

        service.confirm("ORDER-1");

        assertThat(state("ORDER-1")).isEqualTo(ReservationState.CONFIRMED);
        assertThat(state("ORDER-2")).isEqualTo(ReservationState.ACTIVE);
        assertThat(stock()).isEqualTo(7);
        assertThat(service.available("SKU-1")).isEqualTo(5);
    }

    @Test
    void repeatedConfirmationNeverSellsAgainEvenAfterOriginalExpiration() {
        var original = service.reserve("ORDER-1", "SKU-1", 3);
        service.confirm("ORDER-1");
        var later = serviceAt(original.expiresAt().plus(Duration.ofDays(1)));

        later.confirm("ORDER-1");
        later.confirm("ORDER-1");

        assertThat(later.reserve("ORDER-1", "SKU-1", 3)).isEqualTo(original);
        assertThat(state("ORDER-1")).isEqualTo(ReservationState.CONFIRMED);
        assertThat(stock()).isEqualTo(7);
        assertThat(later.available("SKU-1")).isEqualTo(7);
    }

    @Test
    void confirmingAllUnitsLeavesNoStockToReturnAfterExpiration() {
        var response = service.reserve("ORDER-1", "SKU-1", 10);
        service.confirm("ORDER-1");

        assertThat(stock()).isZero();
        assertThat(serviceAt(response.expiresAt()).available("SKU-1")).isZero();
        assertThat(state("ORDER-1")).isEqualTo(ReservationState.CONFIRMED);
    }

    @Test
    void confirmationIsAllowedOneNanosecondBeforeDeadline() {
        var response = service.reserve("ORDER-1", "SKU-1", 3);
        var beforeDeadline = serviceAt(response.expiresAt().minusNanos(1));

        beforeDeadline.confirm("ORDER-1");

        assertThat(state("ORDER-1")).isEqualTo(ReservationState.CONFIRMED);
        assertThat(stock()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void confirmationAtOrAfterDeadlineIsRejectedAndReleasesUnits(long nanosAfterDeadline) {
        var response = service.reserve("ORDER-1", "SKU-1", 3);
        var later = serviceAt(response.expiresAt().plusNanos(nanosAfterDeadline));

        assertThatIllegalStateException().isThrownBy(() -> later.confirm("ORDER-1"));

        assertThat(state("ORDER-1")).isEqualTo(ReservationState.EXPIRED);
        assertThat(stock()).isEqualTo(10);
        assertThat(later.available("SKU-1")).isEqualTo(10);
        assertThatIllegalStateException().isThrownBy(() -> later.reserve("ORDER-1", "SKU-1", 3));
        later.reserve("ORDER-2", "SKU-1", 10);
        assertThat(later.available("SKU-1")).isZero();
    }

    @Test
    void unknownOrderIsRejectedWithoutChangingTheInventory() {
        assertThatIllegalStateException().isThrownBy(() -> service.confirm("UNKNOWN"));
        assertThat(stock()).isEqualTo(10);
        assertThat(database.reservations().findBySku("SKU-1")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingOrderIdentifier(String orderId) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.confirm(orderId));
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void expiredOrderIsNotReactivatedIfTheClockMovesBackwards() {
        var response = service.reserve("ORDER-1", "SKU-1", 3);
        serviceAt(response.expiresAt()).available("SKU-1");

        assertThat(state("ORDER-1")).isEqualTo(ReservationState.EXPIRED);
        assertThatIllegalStateException().isThrownBy(() -> service.confirm("ORDER-1"));
        assertThatIllegalStateException().isThrownBy(() -> service.reserve("ORDER-1", "SKU-1", 3));
        assertThat(service.available("SKU-1")).isEqualTo(10);
    }

    @Test
    void replenishmentAlsoProcessesExpiredReservations() {
        var response = service.reserve("ORDER-1", "SKU-1", 3);
        var later = serviceAt(response.expiresAt());

        later.addStock("SKU-1", 2);

        assertThat(state("ORDER-1")).isEqualTo(ReservationState.EXPIRED);
        assertThat(stock()).isEqualTo(12);
        assertThat(later.available("SKU-1")).isEqualTo(12);
    }

    @Test
    void confirmationExpiresOtherOrdersWithoutSellingTheirUnits() {
        service.reserve("OLD-1", "SKU-1", 2);
        service.reserve("OLD-2", "SKU-1", 3);
        serviceAt(NOW.plus(Duration.ofMinutes(14))).reserve("NEW", "SKU-1", 4);
        var later = serviceAt(NOW.plus(Duration.ofMinutes(15)));

        later.confirm("NEW");

        assertThat(state("OLD-1")).isEqualTo(ReservationState.EXPIRED);
        assertThat(state("OLD-2")).isEqualTo(ReservationState.EXPIRED);
        assertThat(state("NEW")).isEqualTo(ReservationState.CONFIRMED);
        assertThat(stock()).isEqualTo(6);
        assertThat(later.available("SKU-1")).isEqualTo(6);
    }

    @Test
    void categoryDeadlinesReleaseOnlyTheReservationsThatHaveExpired() {
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.registerProduct("PRE", ProductCategory.PRE_ORDER);
        service.addStock("FLASH", 10);
        service.addStock("PRE", 10);
        service.reserve("STANDARD-ORDER", "SKU-1", 2);
        service.reserve("FLASH-ORDER", "FLASH", 2);
        service.reserve("PRE-ORDER", "PRE", 2);

        var afterFiveMinutes = serviceAt(NOW.plus(Duration.ofMinutes(5)));
        assertThat(afterFiveMinutes.available("FLASH")).isEqualTo(10);
        assertThat(afterFiveMinutes.available("SKU-1")).isEqualTo(8);
        assertThat(afterFiveMinutes.available("PRE")).isEqualTo(8);
        assertThat(serviceAt(NOW.plus(Duration.ofMinutes(15))).available("SKU-1")).isEqualTo(10);
        assertThat(serviceAt(NOW.plus(Duration.ofHours(24))).available("PRE")).isEqualTo(10);
        assertThat(state("FLASH-ORDER")).isEqualTo(ReservationState.EXPIRED);
        assertThat(state("STANDARD-ORDER")).isEqualTo(ReservationState.EXPIRED);
        assertThat(state("PRE-ORDER")).isEqualTo(ReservationState.EXPIRED);
    }

    @Test
    void confirmationsFromTwoServiceObjectsSellTheSameOrderOnlyOnce() throws Exception {
        service.reserve("ORDER-1", "SKU-1", 3);
        var other = serviceAt(NOW);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var confirmations = IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                start.await();
                (index % 2 == 0 ? service : other).confirm("ORDER-1");
                return null;
            })).toList();
            start.countDown();
            for (var confirmation : confirmations) {
                confirmation.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(state("ORDER-1")).isEqualTo(ReservationState.CONFIRMED);
        assertThat(stock()).isEqualTo(7);
        assertThat(service.available("SKU-1")).isEqualTo(7);
    }

    private int stock() {
        return database.inventories().findBySku("SKU-1").orElseThrow().onHand();
    }

    private ReservationState state(String orderId) {
        return database.reservations().findByOrderId(orderId).orElseThrow().state();
    }

    private InventoryApplicationService serviceAt(Instant now) {
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        return new InventoryApplicationService(database.inventories(), database.reservations(), database.settlements(),
                database.operations(), clock, database.notifications(clock, (sku, available) -> { }));
    }
}

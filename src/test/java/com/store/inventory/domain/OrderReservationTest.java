package com.store.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderReservationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-08T12:00:00Z");
    private static final Product PRODUCT = new Product("SKU-1", Category.STANDARD);

    @ParameterizedTest
    @CsvSource({"STANDARD,15", "PRE_ORDER,1440", "FLASH_SALE,5"})
    void createsActiveReservationUsingProductPolicy(Category category, long minutes) {
        var reservation = OrderReservation.create("ORDER-1", new Product("SKU-1", category), 2, CREATED_AT);

        assertThat(reservation.state()).isEqualTo(ReservationState.ACTIVE);
        assertThat(reservation.createdAt()).isEqualTo(CREATED_AT);
        assertThat(reservation.expiresAt()).isEqualTo(CREATED_AT.plus(Duration.ofMinutes(minutes)));
    }

    @Test
    void rejectsFlashSaleLimitViolationWithItsContext() {
        assertThatThrownBy(() -> OrderReservation.create(
                "ORDER-1", new Product("FLASH-1", Category.FLASH_SALE), 3, CREATED_AT))
                .isInstanceOfSatisfying(OrderLimitViolationException.class, error -> {
                    assertThat(error.sku()).isEqualTo("FLASH-1");
                    assertThat(error.requested()).isEqualTo(3);
                    assertThat(error.limit()).isEqualTo(2);
                });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingOrderId(String orderId) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> OrderReservation.create(orderId, PRODUCT, 1, CREATED_AT));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveQuantity(int quantity) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> OrderReservation.create("ORDER-1", PRODUCT, quantity, CREATED_AT));
    }

    @Test
    void identifiesChangedProductOrQuantityWithoutNormalizing() {
        var reservation = reservation();

        assertThat(reservation.matches("SKU-1", 2)).isTrue();
        assertThat(reservation.matches("SKU-2", 2)).isFalse();
        assertThat(reservation.matches("sku-1", 2)).isFalse();
        assertThat(reservation.matches("SKU-1", 3)).isFalse();
    }

    @Test
    void remainsActiveImmediatelyBeforeExpiration() {
        var reservation = reservation();
        var now = reservation.expiresAt().minusNanos(1);

        assertThat(reservation.stateAt(now)).isEqualTo(ReservationState.ACTIVE);
        assertThat(reservation.expireAt(now)).isSameAs(reservation);
        assertThat(reservation.confirmAt(now).state()).isEqualTo(ReservationState.CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void expiresAtOrAfterDeadlineAndCannotBeConfirmed(long nanosAfterDeadline) {
        var reservation = reservation();
        var now = reservation.expiresAt().plusNanos(nanosAfterDeadline);

        assertThat(reservation.stateAt(now)).isEqualTo(ReservationState.EXPIRED);
        assertThat(reservation.expireAt(now).state()).isEqualTo(ReservationState.EXPIRED);
        assertThatIllegalStateException().isThrownBy(() -> reservation.confirmAt(now));
        assertThat(reservation.state()).isEqualTo(ReservationState.ACTIVE);
    }

    @Test
    void confirmedReservationNeverExpiresAndRepeatedConfirmationIsIdempotent() {
        var original = reservation();
        var confirmed = original.confirmAt(CREATED_AT.plusSeconds(1));
        var afterDeadline = original.expiresAt().plus(Duration.ofDays(1));

        assertThat(confirmed.stateAt(afterDeadline)).isEqualTo(ReservationState.CONFIRMED);
        assertThat(confirmed.expireAt(afterDeadline)).isSameAs(confirmed);
        assertThat(confirmed.confirmAt(afterDeadline)).isSameAs(confirmed);
        assertThat(confirmed.orderId()).isEqualTo(original.orderId());
        assertThat(confirmed.quantity()).isEqualTo(original.quantity());
        assertThat(confirmed.expiresAt()).isEqualTo(original.expiresAt());
    }

    @Test
    void persistedExpiredStateCannotBeReactivatedByAnEarlierTime() {
        var original = reservation();
        var expired = original.expireAt(original.expiresAt());

        assertThat(expired.stateAt(CREATED_AT)).isEqualTo(ReservationState.EXPIRED);
        assertThat(expired.expireAt(CREATED_AT)).isSameAs(expired);
        assertThatIllegalStateException().isThrownBy(() -> expired.confirmAt(CREATED_AT));
    }

    @Test
    void rejectsInvalidReconstructedReservation() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderReservation(
                "ORDER-1", "SKU-1", 2, CREATED_AT, CREATED_AT, ReservationState.ACTIVE));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderReservation(
                "ORDER-1", "SKU-1", 2, CREATED_AT, CREATED_AT.minusSeconds(1), ReservationState.ACTIVE));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderReservation(
                "ORDER-1", " ", 2, CREATED_AT, CREATED_AT.plusSeconds(1), ReservationState.ACTIVE));
        assertThatIllegalArgumentException().isThrownBy(() -> new OrderReservation(
                "ORDER-1", "SKU-1", 2, CREATED_AT, CREATED_AT.plusSeconds(1), null));
    }

    private static OrderReservation reservation() {
        return OrderReservation.create("ORDER-1", PRODUCT, 2, CREATED_AT);
    }
}

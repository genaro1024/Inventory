package com.store.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class CategoryPoliciesTest {

    @ParameterizedTest
    @CsvSource({"STANDARD,15", "PRE_ORDER,1440", "FLASH_SALE,5"})
    void calculatesExpirationForEachCategory(Category category, long minutes) {
        var createdAt = Instant.parse("2026-10-08T12:00:00Z");

        assertThat(CategoryPolicies.forCategory(category).expiresAt(createdAt))
                .isEqualTo(createdAt.plus(Duration.ofMinutes(minutes)));
    }

    @ParameterizedTest
    @EnumSource(value = Category.class, names = {"STANDARD", "PRE_ORDER"})
    void unlimitedCategoriesAllowAnyPositiveInt(Category category) {
        var policy = CategoryPolicies.forCategory(category);

        assertThat(policy.orderLimit()).isEmpty();
        assertThat(policy.allowsQuantity(1)).isTrue();
        assertThat(policy.allowsQuantity(Integer.MAX_VALUE)).isTrue();
    }

    @Test
    void flashSaleAllowsTwoUnitsButRejectsThree() {
        var policy = CategoryPolicies.forCategory(Category.FLASH_SALE);

        assertThat(policy.orderLimit()).hasValue(2);
        assertThat(policy.allowsQuantity(1)).isTrue();
        assertThat(policy.allowsQuantity(2)).isTrue();
        assertThat(policy.allowsQuantity(3)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Category.class)
    void everyCategoryRejectsNonPositiveQuantities(Category category) {
        var policy = CategoryPolicies.forCategory(category);

        assertThat(policy.allowsQuantity(0)).isFalse();
        assertThat(policy.allowsQuantity(-1)).isFalse();
    }

    @Test
    void rejectsMissingCategory() {
        assertThatIllegalArgumentException().isThrownBy(() -> CategoryPolicies.forCategory(null));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositivePaymentWindow(long seconds) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReservationPolicy(Duration.ofSeconds(seconds), OptionalInt.empty()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveOrderLimit(int limit) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReservationPolicy(Duration.ofMinutes(5), OptionalInt.of(limit)));
    }

    @Test
    void rejectsMissingPolicyValues() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReservationPolicy(null, OptionalInt.empty()));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReservationPolicy(Duration.ofMinutes(5), null));
    }
}

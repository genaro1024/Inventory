package com.store.inventory.domain;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;

public final class CategoryPolicies {

    private static final Map<Category, ReservationPolicy> POLICIES = Map.of(
            Category.STANDARD, new ReservationPolicy(Duration.ofMinutes(15), OptionalInt.empty()),
            Category.PRE_ORDER, new ReservationPolicy(Duration.ofHours(24), OptionalInt.empty()),
            Category.FLASH_SALE, new ReservationPolicy(Duration.ofMinutes(5), OptionalInt.of(2)));

    private CategoryPolicies() {
    }

    public static ReservationPolicy forCategory(Category category) {
        if (category == null) {
            throw new IllegalArgumentException("Category is required");
        }
        return POLICIES.get(category);
    }
}

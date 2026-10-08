package com.store.inventory.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;

public record ReservationPolicy(Duration paymentWindow, OptionalInt orderLimit) {

    public ReservationPolicy {
        if (paymentWindow == null || paymentWindow.isZero() || paymentWindow.isNegative()) {
            throw new IllegalArgumentException("Payment window must be positive");
        }
        if (orderLimit == null || (orderLimit.isPresent() && orderLimit.getAsInt() <= 0)) {
            throw new IllegalArgumentException("Order limit must be absent or positive");
        }
    }

    public boolean allowsQuantity(int quantity) {
        return quantity > 0 && (orderLimit.isEmpty() || quantity <= orderLimit.getAsInt());
    }

    public Instant expiresAt(Instant createdAt) {
        return Objects.requireNonNull(createdAt, "Creation time is required").plus(paymentWindow);
    }
}

package com.store.inventory.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable reservation state. Inventory availability is checked by the use case.
 */
public record OrderReservation(
        String orderId,
        String sku,
        int quantity,
        Instant createdAt,
        Instant expiresAt,
        ReservationState state) {

    public OrderReservation {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Order ID must not be blank");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Expiration must be after creation time");
        }
        if (state == null) {
            throw new IllegalArgumentException("Reservation state is required");
        }
    }

    public static OrderReservation create(String orderId, Product product, int quantity, Instant createdAt) {
        Objects.requireNonNull(product, "Product is required");
        var policy = CategoryPolicies.forCategory(product.category());
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (!policy.allowsQuantity(quantity)) {
            throw new OrderLimitViolationException(product.sku(), quantity, policy.orderLimit().orElseThrow());
        }
        return new OrderReservation(orderId, product.sku(), quantity, createdAt,
                policy.expiresAt(createdAt), ReservationState.ACTIVE);
    }

    public ReservationState stateAt(Instant now) {
        Objects.requireNonNull(now, "Current time is required");
        if (state == ReservationState.ACTIVE && !now.isBefore(expiresAt)) {
            return ReservationState.EXPIRED;
        }
        return state;
    }

    public OrderReservation expireAt(Instant now) {
        return stateAt(now) == ReservationState.EXPIRED && state == ReservationState.ACTIVE
                ? withState(ReservationState.EXPIRED) : this;
    }

    public OrderReservation confirmAt(Instant now) {
        var currentState = stateAt(now);
        if (currentState == ReservationState.CONFIRMED) {
            return this;
        }
        if (currentState == ReservationState.EXPIRED) {
            throw new IllegalStateException("Reservation has expired");
        }
        return withState(ReservationState.CONFIRMED);
    }

    public boolean matches(String requestedSku, int requestedQuantity) {
        return sku.equals(requestedSku) && quantity == requestedQuantity;
    }

    private OrderReservation withState(ReservationState nextState) {
        return new OrderReservation(orderId, sku, quantity, createdAt, expiresAt, nextState);
    }
}

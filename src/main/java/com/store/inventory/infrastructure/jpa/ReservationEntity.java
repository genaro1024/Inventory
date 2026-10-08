package com.store.inventory.infrastructure.jpa;

import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.ReservationState;
import jakarta.persistence.Column;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "reservations", indexes = @Index(name = "idx_reservation_sku", columnList = "sku"),
        check = @CheckConstraint(name = "ck_reservation_terms", constraint = "quantity > 0 and expires_at > created_at"))
public class ReservationEntity {

    @Id
    @Column(name = "order_id")
    private String orderId;

    @Column(nullable = false)
    private String sku;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_reservation_product"))
    private ProductInventoryEntity product;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false, secondPrecision = 9)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, secondPrecision = 9)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationState state;

    protected ReservationEntity() {
    }

    ReservationEntity(OrderReservation reservation) {
        orderId = reservation.orderId();
        sku = reservation.sku();
        quantity = reservation.quantity();
        createdAt = reservation.createdAt();
        expiresAt = reservation.expiresAt();
        state = reservation.state();
    }

    OrderReservation toDomain() {
        return new OrderReservation(orderId, sku, quantity, createdAt, expiresAt, state);
    }
}

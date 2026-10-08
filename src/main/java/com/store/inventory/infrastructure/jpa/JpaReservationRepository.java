package com.store.inventory.infrastructure.jpa;

import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.repository.ReservationRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JpaReservationRepository implements ReservationRepository {

    private final JpaTransactions transactions;

    public JpaReservationRepository(EntityManagerFactory factory) {
        transactions = new JpaTransactions(factory);
    }

    @Override
    public Optional<OrderReservation> findByOrderId(String orderId) {
        Objects.requireNonNull(orderId, "Order ID is required");
        return transactions.read(manager -> Optional.ofNullable(manager.find(ReservationEntity.class, orderId))
                .map(ReservationEntity::toDomain));
    }

    @Override
    public List<OrderReservation> findBySku(String sku) {
        Objects.requireNonNull(sku, "SKU is required");
        return transactions.read(manager -> manager.createQuery(
                "select r from ReservationEntity r where r.sku = :sku", ReservationEntity.class)
                .setParameter("sku", sku).getResultList().stream().map(ReservationEntity::toDomain).toList());
    }

    @Override
    public boolean insert(OrderReservation reservation) {
        Objects.requireNonNull(reservation, "Reservation is required");
        return transactions.insert(new ReservationEntity(reservation));
    }

    @Override
    public boolean replace(OrderReservation expected, OrderReservation replacement) {
        Objects.requireNonNull(expected, "Expected reservation is required");
        Objects.requireNonNull(replacement, "Replacement reservation is required");
        if (!expected.orderId().equals(replacement.orderId())
                || !expected.matches(replacement.sku(), replacement.quantity())
                || !expected.createdAt().equals(replacement.createdAt())
                || !expected.expiresAt().equals(replacement.expiresAt())) {
            throw new IllegalArgumentException("An update must preserve the original order data");
        }
        return transactions.write(manager -> manager.createQuery("""
                update ReservationEntity r set r.state = :replacement
                where r.orderId = :orderId and r.sku = :sku and r.quantity = :quantity
                and r.createdAt = :createdAt and r.expiresAt = :expiresAt and r.state = :expected
                """).setParameter("replacement", replacement.state()).setParameter("orderId", expected.orderId())
                .setParameter("sku", expected.sku()).setParameter("quantity", expected.quantity())
                .setParameter("createdAt", expected.createdAt()).setParameter("expiresAt", expected.expiresAt())
                .setParameter("expected", expected.state()).executeUpdate() == 1);
    }
}

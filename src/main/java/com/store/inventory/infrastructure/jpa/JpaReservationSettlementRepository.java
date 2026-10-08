package com.store.inventory.infrastructure.jpa;

import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.ProductInventory;
import com.store.inventory.domain.ReservationState;
import com.store.inventory.domain.repository.ReservationSettlementRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.Objects;

public final class JpaReservationSettlementRepository implements ReservationSettlementRepository {

    private final JpaTransactions transactions;

    public JpaReservationSettlementRepository(EntityManagerFactory factory) {
        transactions = new JpaTransactions(factory);
    }

    @Override
    public boolean confirm(OrderReservation expectedReservation, ProductInventory expectedInventory, Instant now) {
        Objects.requireNonNull(expectedReservation, "Reservation is required");
        Objects.requireNonNull(expectedInventory, "Inventory is required");
        if (!expectedReservation.sku().equals(expectedInventory.sku())) {
            throw new IllegalArgumentException("Reservation and inventory must belong to the same product");
        }
        if (expectedReservation.stateAt(now) != ReservationState.ACTIVE) {
            throw new IllegalStateException("Settlement requires an active reservation");
        }
        var confirmed = expectedReservation.confirmAt(now);
        var sold = expectedInventory.sell(expectedReservation.quantity());
        try {
            return transactions.write(manager -> {
                int ordersUpdated = manager.createQuery("""
                        update ReservationEntity r set r.state = :confirmed
                        where r.orderId = :orderId and r.sku = :sku and r.quantity = :quantity
                        and r.createdAt = :createdAt and r.expiresAt = :expiresAt and r.state = :expected
                        """).setParameter("confirmed", confirmed.state())
                        .setParameter("orderId", expectedReservation.orderId())
                        .setParameter("sku", expectedReservation.sku())
                        .setParameter("quantity", expectedReservation.quantity())
                        .setParameter("createdAt", expectedReservation.createdAt())
                        .setParameter("expiresAt", expectedReservation.expiresAt())
                        .setParameter("expected", expectedReservation.state()).executeUpdate();
                if (ordersUpdated == 0) {
                    return false;
                }
                int stocksUpdated = manager.createQuery("""
                        update ProductInventoryEntity p set p.onHand = :sold
                        where p.sku = :sku and p.category = :category and p.onHand = :expected
                        """).setParameter("sold", sold.onHand()).setParameter("sku", expectedInventory.sku())
                        .setParameter("category", expectedInventory.product().category())
                        .setParameter("expected", expectedInventory.onHand()).executeUpdate();
                if (stocksUpdated == 0) {
                    throw new StockChangedException();
                }
                return true;
            });
        } catch (StockChangedException conflict) {
            return false;
        }
    }

    private static final class StockChangedException extends RuntimeException {
    }
}

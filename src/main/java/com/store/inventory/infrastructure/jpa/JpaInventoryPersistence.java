package com.store.inventory.infrastructure.jpa;

import jakarta.persistence.EntityManagerFactory;

/** Wires all adapters to the same transaction context for this database. */
public final class JpaInventoryPersistence {

    private final JpaProductInventoryRepository inventories;
    private final JpaReservationRepository reservations;
    private final JpaReservationSettlementRepository settlements;
    private final JpaInventoryOperationExecutor operations;

    public JpaInventoryPersistence(EntityManagerFactory factory) {
        var transactions = new JpaTransactions(factory);
        inventories = new JpaProductInventoryRepository(transactions);
        reservations = new JpaReservationRepository(transactions);
        settlements = new JpaReservationSettlementRepository(transactions);
        operations = new JpaInventoryOperationExecutor(transactions);
    }

    public JpaProductInventoryRepository inventories() {
        return inventories;
    }

    public JpaReservationRepository reservations() {
        return reservations;
    }

    public JpaReservationSettlementRepository settlements() {
        return settlements;
    }

    public JpaInventoryOperationExecutor operations() {
        return operations;
    }
}

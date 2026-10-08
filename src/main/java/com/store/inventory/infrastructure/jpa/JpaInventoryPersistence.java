package com.store.inventory.infrastructure.jpa;

import jakarta.persistence.EntityManagerFactory;
import com.store.inventory.application.notification.StockAlertDelivery;
import com.store.inventory.application.notification.StockAlertNotifications;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.infrastructure.notification.ExecutorRetryScheduler;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

/** Wires all adapters to the same transaction context for this database. */
public final class JpaInventoryPersistence {

    private final JpaProductInventoryRepository inventories;
    private final JpaReservationRepository reservations;
    private final JpaReservationSettlementRepository settlements;
    private final JpaInventoryOperationExecutor operations;
    private final JpaStockAlertRepository alerts;

    public JpaInventoryPersistence(EntityManagerFactory factory) {
        var transactions = new JpaTransactions(factory);
        inventories = new JpaProductInventoryRepository(transactions);
        reservations = new JpaReservationRepository(transactions);
        settlements = new JpaReservationSettlementRepository(transactions);
        operations = new JpaInventoryOperationExecutor(transactions);
        alerts = new JpaStockAlertRepository(transactions);
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

    public JpaStockAlertRepository alerts() {
        return alerts;
    }

    public StockAlertNotifications notifications(Clock clock, StockAlertListener listener) {
        return new StockAlertNotifications(alerts, new StockAlertDelivery(alerts, clock, listener,
                new ExecutorRetryScheduler(), () -> ThreadLocalRandom.current().nextDouble()));
    }
}

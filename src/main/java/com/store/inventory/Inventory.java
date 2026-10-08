package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.InventoryApplicationService;
import com.store.inventory.infrastructure.jpa.H2InventoryDatabase;
import java.time.Clock;
import java.util.Objects;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 */
public final class Inventory {

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        Objects.requireNonNull(clock, "Clock is required");
        Objects.requireNonNull(alertListener, "Alert listener is required");
        var database = new H2InventoryDatabase();
        try {
            return new InventoryApplicationService(database.inventories(), database.reservations(), database.settlements(),
                    database.operations(), clock, database.notifications(clock, alertListener), database::close);
        } catch (RuntimeException failure) {
            database.close();
            throw failure;
        }
    }
}

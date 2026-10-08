package com.store.inventory.application;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.ReservationState;
import com.store.inventory.domain.repository.ProductInventoryRepository;
import com.store.inventory.domain.repository.ReservationRepository;
import java.time.Clock;
import java.lang.ref.Cleaner;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application entry point. Mutation use cases will be added in the following tasks.
 */
public final class InventoryApplicationService implements InventoryService, AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final ProductInventoryRepository inventories;
    private final ReservationRepository reservations;
    private final Clock clock;
    private final StockAlertListener alertListener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Cleaner.Cleanable resources;

    public InventoryApplicationService(ProductInventoryRepository inventories,
            ReservationRepository reservations, Clock clock, StockAlertListener alertListener) {
        this(inventories, reservations, clock, alertListener, () -> { });
    }

    public InventoryApplicationService(ProductInventoryRepository inventories,
            ReservationRepository reservations, Clock clock, StockAlertListener alertListener, Runnable releaseResources) {
        this.inventories = Objects.requireNonNull(inventories, "Inventory repository is required");
        this.reservations = Objects.requireNonNull(reservations, "Reservation repository is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.alertListener = Objects.requireNonNull(alertListener, "Alert listener is required");
        resources = CLEANER.register(this, Objects.requireNonNull(releaseResources, "Resource cleanup is required"));
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        ensureOpen();
        throw new UnsupportedOperationException("Product registration is pending task 4");
    }

    @Override
    public void addStock(String sku, int quantity) {
        ensureOpen();
        throw new UnsupportedOperationException("Replenishment is pending task 4");
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        ensureOpen();
        throw new UnsupportedOperationException("Reservations are pending task 5");
    }

    @Override
    public void confirm(String orderId) {
        ensureOpen();
        throw new UnsupportedOperationException("Confirmation is pending task 6");
    }

    @Override
    public int available(String sku) {
        ensureOpen();
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        var inventory = inventories.findBySku(sku);
        if (inventory.isEmpty()) {
            return 0;
        }
        var now = clock.instant();
        long reservedUnits = reservations.findBySku(sku).stream()
                .filter(reservation -> reservation.stateAt(now) == ReservationState.ACTIVE)
                .mapToLong(reservation -> reservation.quantity()).sum();
        long availableUnits = inventory.get().onHand() - reservedUnits;
        if (availableUnits < 0) {
            throw new IllegalStateException("Active reservations exceed on-hand stock for " + sku);
        }
        return Math.toIntExact(availableUnits);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            resources.clean();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Inventory service is closed");
        }
    }
}

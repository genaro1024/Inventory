package com.store.inventory.application;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.Category;
import com.store.inventory.domain.OrderLimitViolationException;
import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.Product;
import com.store.inventory.domain.ProductInventory;
import com.store.inventory.domain.ReservationState;
import com.store.inventory.domain.repository.ProductInventoryRepository;
import com.store.inventory.domain.repository.ReservationRepository;
import com.store.inventory.domain.repository.ReservationSettlementRepository;
import java.lang.ref.Cleaner;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Application entry point. Notification use cases are still pending.
 */
public final class InventoryApplicationService implements InventoryService, AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final ProductInventoryRepository inventories;
    private final ReservationRepository reservations;
    private final ReservationSettlementRepository settlements;
    private final InventoryOperationExecutor operations;
    private final Clock clock;
    private final StockAlertListener alertListener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Cleaner.Cleanable resources;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    public InventoryApplicationService(ProductInventoryRepository inventories,
            ReservationRepository reservations, ReservationSettlementRepository settlements,
            InventoryOperationExecutor operations, Clock clock, StockAlertListener alertListener) {
        this(inventories, reservations, settlements, operations, clock, alertListener, () -> { });
    }

    public InventoryApplicationService(ProductInventoryRepository inventories,
            ReservationRepository reservations, ReservationSettlementRepository settlements,
            InventoryOperationExecutor operations, Clock clock, StockAlertListener alertListener, Runnable releaseResources) {
        this.inventories = Objects.requireNonNull(inventories, "Inventory repository is required");
        this.reservations = Objects.requireNonNull(reservations, "Reservation repository is required");
        this.settlements = Objects.requireNonNull(settlements, "Settlement repository is required");
        this.operations = Objects.requireNonNull(operations, "Operation executor is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.alertListener = Objects.requireNonNull(alertListener, "Alert listener is required");
        resources = CLEANER.register(this, Objects.requireNonNull(releaseResources, "Resource cleanup is required"));
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        ensureOpen();
        validateSku(sku);
        if (category == null) {
            throw new IllegalArgumentException("Category is required");
        }
        var product = new Product(sku, Category.valueOf(category.name()));
        withOpenService(() -> {
            if (!inventories.insert(new ProductInventory(product, 0))) {
                throw new IllegalArgumentException("El producto ya existe");
            }
            return null;
        });
    }

    @Override
    public void addStock(String sku, int quantity) {
        ensureOpen();
        validateSku(sku);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        productOperation(sku, lockedInventory -> {
            if (lockedInventory.isEmpty()) {
                throw new IllegalArgumentException("Product is not registered: " + sku);
            }
            expireReservations(sku, clock.instant());
            while (true) {
                var current = inventories.findBySku(sku)
                        .orElseThrow(() -> new IllegalArgumentException("Product is not registered: " + sku));
                var updated = current.replenish(quantity);
                if (inventories.replace(current, updated)) {
                    return null;
                }
            }
        });
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        ensureOpen();
        validateOrderId(orderId);
        validateSku(sku);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        return productOperation(sku, lockedInventory -> {
            var now = clock.instant();
            expireReservations(sku, now);
            var existing = reservations.findByOrderId(orderId);
            if (existing.isPresent()) {
                return reuseReservation(existing.get(), sku, quantity, now);
            }
            var inventory = lockedInventory
                    .orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));
            OrderReservation reservation;
            try {
                reservation = OrderReservation.create(orderId, inventory.product(), quantity, now);
            } catch (OrderLimitViolationException violation) {
                throw new OrderLimitExceededException(violation.sku(), violation.requested(), violation.limit());
            }
            int availableUnits = availableUnits(inventory, now);
            if (quantity > availableUnits) {
                throw new InsufficientStockException(sku, quantity, availableUnits);
            }
            if (!reservations.insert(reservation)) {
                // The primary key also protects order IDs competing across different SKUs.
                var winner = reservations.findByOrderId(orderId)
                        .orElseThrow(() -> new IllegalStateException("Conflicting order record is missing"));
                return reuseReservation(winner, sku, quantity, now);
            }
            return toResponse(reservation);
        });
    }

    @Override
    public void confirm(String orderId) {
        ensureOpen();
        validateOrderId(orderId);
        withOpenService(() -> {
            var original = reservations.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException("Order has no active reservation"));
            return productOperation(original.sku(), lockedInventory -> {
                while (true) {
                    var now = clock.instant();
                    expireReservations(original.sku(), now);
                    var reservation = reservations.findByOrderId(orderId)
                            .orElseThrow(() -> new IllegalStateException("Order has no active reservation"));
                    if (reservation.stateAt(now) == ReservationState.CONFIRMED) {
                        return null;
                    }
                    if (reservation.stateAt(now) == ReservationState.EXPIRED) {
                        throw new IllegalStateException("Order has no active reservation");
                    }
                    var inventory = lockedInventory
                            .orElseThrow(() -> new IllegalStateException("Reserved product is missing"));
                    if (settlements.confirm(reservation, inventory, now)) {
                        return null;
                    }
                }
            });
        });
    }

    @Override
    public int available(String sku) {
        ensureOpen();
        validateSku(sku);
        return productOperation(sku, inventory -> {
            if (inventory.isEmpty()) {
                return 0;
            }
            var now = clock.instant();
            expireReservations(sku, now);
            return availableUnits(inventory.get(), now);
        });
    }

    private int availableUnits(ProductInventory inventory, Instant now) {
        long reservedUnits = reservations.findBySku(inventory.sku()).stream()
                .filter(reservation -> reservation.stateAt(now) == ReservationState.ACTIVE)
                .mapToLong(reservation -> reservation.quantity()).sum();
        long availableUnits = inventory.onHand() - reservedUnits;
        if (availableUnits < 0) {
            throw new IllegalStateException("Active reservations exceed on-hand stock for " + inventory.sku());
        }
        return Math.toIntExact(availableUnits);
    }

    private void expireReservations(String sku, Instant now) {
        for (var reservation : reservations.findBySku(sku)) {
            var expired = reservation.expireAt(now);
            if (expired != reservation) {
                reservations.replace(reservation, expired);
            }
        }
    }

    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed.compareAndSet(false, true)) {
                resources.clean();
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Inventory service is closed");
        }
    }

    private <T> T withOpenService(Supplier<T> operation) {
        lifecycle.readLock().lock();
        try {
            ensureOpen();
            return operation.get();
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private <T> T productOperation(String sku, Function<Optional<ProductInventory>, T> operation) {
        return withOpenService(() -> {
            var result = operations.execute(sku, inventory -> {
                try {
                    return new OperationResult<T>(operation.apply(inventory), null);
                } catch (IllegalArgumentException | IllegalStateException
                        | InsufficientStockException | OrderLimitExceededException rejection) {
                    // Commit expiration cleanup for an expected rejection, without creating a sale or reservation.
                    return new OperationResult<T>(null, rejection);
                }
            });
            if (result.rejection() != null) {
                throw result.rejection();
            }
            return result.value();
        });
    }

    private record OperationResult<T>(T value, RuntimeException rejection) {
    }

    private static void validateSku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
    }

    private static void validateOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Order ID must not be blank");
        }
    }

    private static Reservation reuseReservation(OrderReservation reservation, String sku, int quantity, Instant now) {
        if (!reservation.matches(sku, quantity)) {
            throw new IllegalArgumentException("Order ID already belongs to a different product or quantity");
        }
        if (reservation.stateAt(now) == ReservationState.EXPIRED) {
            throw new IllegalStateException("Reservation has expired; use a new order ID");
        }
        return toResponse(reservation);
    }

    private static Reservation toResponse(OrderReservation reservation) {
        return new Reservation(reservation.orderId(), reservation.sku(), reservation.quantity(), reservation.expiresAt());
    }
}

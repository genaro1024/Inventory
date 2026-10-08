package com.store.inventory.application;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.application.notification.StockAlertNotifications;
import com.store.inventory.observability.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.store.inventory.domain.notification.AlertEvaluation;
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
 * Inventory operations and transactional preparation of stock alerts.
 */
public final class InventoryApplicationService implements InventoryService, AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final Logger LOG = LoggerFactory.getLogger(InventoryApplicationService.class);

    private final ProductInventoryRepository inventories;
    private final ReservationRepository reservations;
    private final ReservationSettlementRepository settlements;
    private final InventoryOperationExecutor operations;
    private final Clock clock;
    private final StockAlertNotifications notifications;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Cleaner.Cleanable resources;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    public InventoryApplicationService(ProductInventoryRepository inventories,
            ReservationRepository reservations, ReservationSettlementRepository settlements,
            InventoryOperationExecutor operations, Clock clock, StockAlertNotifications notifications) {
        this(inventories, reservations, settlements, operations, clock, notifications, () -> { });
    }

    public InventoryApplicationService(ProductInventoryRepository inventories,
            ReservationRepository reservations, ReservationSettlementRepository settlements,
            InventoryOperationExecutor operations, Clock clock, StockAlertNotifications notifications, Runnable releaseResources) {
        this.inventories = Objects.requireNonNull(inventories, "Inventory repository is required");
        this.reservations = Objects.requireNonNull(reservations, "Reservation repository is required");
        this.settlements = Objects.requireNonNull(settlements, "Settlement repository is required");
        this.operations = Objects.requireNonNull(operations, "Operation executor is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.notifications = Objects.requireNonNull(notifications, "Notifications are required");
        var cleanup = Objects.requireNonNull(releaseResources, "Resource cleanup is required");
        resources = CLEANER.register(this, () -> {
            try {
                notifications.close();
            } finally {
                cleanup.run();
            }
        });
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        ensureOpen();
        validateSku(sku);
        if (category == null) {
            throw new InventoryFailures.InvalidInput("Category is required");
        }
        var product = new Product(sku, Category.valueOf(category.name()));
        withOpenService(() -> {
            if (!inventories.insert(new ProductInventory(product, 0))) {
                throw new InventoryFailures.ProductAlreadyExists();
            }
            LOG.info("Product registered: sku={} category={}", TraceContext.logIdentifier(sku), category);
            return null;
        });
    }

    @Override
    public void addStock(String sku, int quantity) {
        ensureOpen();
        validateSku(sku);
        if (quantity <= 0) {
            throw new InventoryFailures.InvalidInput("Quantity must be positive");
        }
        try (var trace = TraceContext.ensure()) {
        var replenished = productOperation(sku, true, lockedInventory -> {
            if (lockedInventory.isEmpty()) {
                throw new InventoryFailures.UnknownProduct(sku);
            }
            expireReservations(sku, clock.instant());
            while (true) {
                var current = inventories.findBySku(sku)
                        .orElseThrow(() -> new InventoryFailures.UnknownProduct(sku));
                var updated = current.replenish(quantity);
                if (inventories.replace(current, updated)) {
                    return updated;
                }
            }
        });
        LOG.info("Stock replenished: sku={} added={} onHand={}", TraceContext.logIdentifier(sku), quantity, replenished.onHand());
        }
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        ensureOpen();
        validateOrderId(orderId);
        validateSku(sku);
        if (quantity <= 0) {
            throw new InventoryFailures.InvalidInput("Quantity must be positive");
        }
        try (var trace = TraceContext.ensure()) {
        var outcome = productOperation(sku, lockedInventory -> {
            var now = clock.instant();
            expireReservations(sku, now);
            var existing = reservations.findByOrderId(orderId);
            if (existing.isPresent()) {
                return new ReservationOutcome(reuseReservation(existing.get(), sku, quantity, now), false);
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
                return new ReservationOutcome(reuseReservation(winner, sku, quantity, now), false);
            }
            return new ReservationOutcome(toResponse(reservation), true);
        });
        if (outcome.created()) {
            LOG.info("Reservation created: order={} sku={} quantity={} expiresAt={}",
                    TraceContext.logIdentifier(orderId), TraceContext.logIdentifier(sku), quantity, outcome.response().expiresAt());
        } else {
            LOG.debug("Reservation replay: order={} sku={}", TraceContext.logIdentifier(orderId), TraceContext.logIdentifier(sku));
        }
        return outcome.response();
        }
    }

    @Override
    public void confirm(String orderId) {
        ensureOpen();
        validateOrderId(orderId);
        try (var trace = TraceContext.ensure()) {
        var original = withOpenService(() -> reservations.findByOrderId(orderId)
                .orElseThrow(() -> new InventoryFailures.NoActiveReservation(orderId, false)));
        boolean sold = productOperation(original.sku(), lockedInventory -> {
                while (true) {
                    var now = clock.instant();
                    expireReservations(original.sku(), now);
                    var reservation = reservations.findByOrderId(orderId)
                            .orElseThrow(() -> new InventoryFailures.NoActiveReservation(orderId, false));
                    if (reservation.stateAt(now) == ReservationState.CONFIRMED) {
                        return false;
                    }
                    if (reservation.stateAt(now) == ReservationState.EXPIRED) {
                        throw new InventoryFailures.NoActiveReservation(orderId, true);
                    }
                    var inventory = lockedInventory
                            .orElseThrow(() -> new IllegalStateException("Reserved product is missing"));
                    if (settlements.confirm(reservation, inventory, now)) {
                        return true;
                    }
                }
        });
        if (sold) {
            LOG.info("Order confirmed: order={} sku={}", TraceContext.logIdentifier(orderId), TraceContext.logIdentifier(original.sku()));
        } else {
            LOG.debug("Confirmation replay: order={}", TraceContext.logIdentifier(orderId));
        }
        }
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
                if (reservations.replace(reservation, expired)) {
                    LOG.debug("Expiration staged: order={} sku={} expiresAt={}", TraceContext.logIdentifier(reservation.orderId()),
                            TraceContext.logIdentifier(sku), reservation.expiresAt());
                }
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
        try (var trace = TraceContext.ensure()) {
            ensureOpen();
            return operation.get();
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private <T> T productOperation(String sku, Function<Optional<ProductInventory>, T> operation) {
        return productOperation(sku, false, operation);
    }

    private <T> T productOperation(String sku, boolean replenished,
            Function<Optional<ProductInventory>, T> operation) {
        try (var trace = TraceContext.ensure()) {
        var result = withOpenService(() -> operations.execute(sku, inventory -> {
                try {
                    var value = operation.apply(inventory);
                    var current = inventories.findBySku(sku);
                    var now = clock.instant();
                    var evaluation = current.isEmpty() ? AlertEvaluation.none()
                            : notifications.evaluate(sku, availableUnits(current.get(), now), replenished, now);
                    return new OperationResult<T>(value, null, evaluation);
                } catch (IllegalArgumentException | IllegalStateException
                        | InsufficientStockException | OrderLimitExceededException rejection) {
                    // Commit expiration cleanup for an expected rejection, without creating a sale or reservation.
                    return new OperationResult<T>(null, rejection, AlertEvaluation.none());
                }
            }));
        if (result.rejection() != null) {
            throw result.rejection();
        }
        notifications.afterCommit(sku, replenished, result.evaluation());
        return result.value();
        }
    }

    private record OperationResult<T>(T value, RuntimeException rejection, AlertEvaluation evaluation) {
    }

    private record ReservationOutcome(Reservation response, boolean created) {
    }

    private static void validateSku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new InventoryFailures.InvalidInput("SKU must not be blank");
        }
    }

    private static void validateOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new InventoryFailures.InvalidInput("Order ID must not be blank");
        }
    }

    private static Reservation reuseReservation(OrderReservation reservation, String sku, int quantity, Instant now) {
        if (!reservation.matches(sku, quantity)) {
            throw new InventoryFailures.OrderConflict();
        }
        if (reservation.stateAt(now) == ReservationState.EXPIRED) {
            throw new InventoryFailures.NoActiveReservation(reservation.orderId(), true);
        }
        return toResponse(reservation);
    }

    private static Reservation toResponse(OrderReservation reservation) {
        return new Reservation(reservation.orderId(), reservation.sku(), reservation.quantity(), reservation.expiresAt());
    }
}

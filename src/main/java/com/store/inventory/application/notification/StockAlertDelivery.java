package com.store.inventory.application.notification;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.notification.AlertRetryPolicy;
import com.store.inventory.domain.notification.StockAlert;
import com.store.inventory.domain.notification.StockAlertState;
import com.store.inventory.domain.repository.StockAlertRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StockAlertDelivery implements StockAlertDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(StockAlertDelivery.class);
    private final StockAlertRepository alerts;
    private final StockAlertListener listener;
    private final Clock clock;
    private final RetryScheduler scheduler;
    private final DoubleSupplier random;
    private final AlertRetryPolicy policy = new AlertRetryPolicy();
    private final ConcurrentMap<UUID, PendingTask> pending = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock databaseAccess = new ReentrantReadWriteLock();
    private volatile boolean closed;

    public StockAlertDelivery(StockAlertRepository alerts, Clock clock, StockAlertListener listener,
            RetryScheduler scheduler, DoubleSupplier random) {
        this.alerts = Objects.requireNonNull(alerts);
        this.clock = Objects.requireNonNull(clock);
        this.listener = Objects.requireNonNull(listener);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public void dispatch(UUID id) {
        if (closed) {
            return;
        }
        try {
            var alert = access(() -> alerts.findById(id), Optional.<StockAlert>empty());
            alert.filter(value -> value.state() == StockAlertState.PENDING)
                    .ifPresent(value -> attempt(value.id(), value.attempts()));
        } catch (RuntimeException failure) {
            LOG.error("Could not dispatch stock alert {}", id, failure);
        }
    }

    private void attempt(UUID id, int expectedAttempts) {
        try {
            var claimed = access(() -> alerts.claim(id, expectedAttempts), Optional.<StockAlert>empty());
            if (claimed.isEmpty() || closed) {
                return;
            }
            var alert = claimed.get();
            // No inventory transaction or database-access lock is held during this call.
            try {
                listener.onLowStock(alert.sku(), alert.availableUnits());
            } catch (RuntimeException failure) {
                var delay = policy.afterFailure(alert.attempts(), random);
                var next = delay.map(value -> clock.instant().plus(value)).orElse(null);
                var stored = access(() -> alerts.failed(id, alert.attempts(),
                        failure.getClass().getName() + ": " + failure.getMessage(), next), Optional.<StockAlert>empty());
                if (stored.isPresent()) {
                    if (stored.get().state() == StockAlertState.DEAD_LETTER) {
                        LOG.error("Stock alert {} for {} moved to DLQ after {} attempts",
                                id, alert.sku(), alert.attempts(), failure);
                    } else {
                        LOG.warn("Stock alert {} for {} failed on attempt {}", id, alert.sku(), alert.attempts(), failure);
                        schedule(stored.get(), delay.orElseThrow());
                    }
                }
                return;
            }
            access(() -> alerts.delivered(id, alert.attempts()), false);
        } catch (RuntimeException failure) {
            LOG.error("Could not process stock alert {}", id, failure);
        }
    }

    private void schedule(StockAlert alert, Duration delay) {
        databaseAccess.readLock().lock();
        try {
            if (closed || pending.containsKey(alert.id())) {
                return;
            }
            var task = new PendingTask(alert.sku(), alert.cycle());
            if (pending.putIfAbsent(alert.id(), task) != null) {
                return;
            }
            try {
                task.attach(scheduler.schedule(delay, () -> {
                    if (pending.remove(alert.id(), task)) {
                        attempt(alert.id(), alert.attempts());
                    }
                }));
            } catch (RuntimeException failure) {
                pending.remove(alert.id(), task);
                LOG.error("Could not schedule retry for alert {}; it remains queued in H2", alert.id(), failure);
            }
        } finally {
            databaseAccess.readLock().unlock();
        }
    }

    @Override
    public void cancelBefore(String sku, long cycle) {
        pending.forEach((id, task) -> {
            if (task.sku.equals(sku) && task.cycle < cycle && pending.remove(id, task)) {
                task.cancel();
            }
        });
    }

    @Override
    public boolean reprocess(UUID id) {
        if (closed) {
            return false;
        }
        boolean queued = access(() -> alerts.requeueDeadLetter(id), false);
        if (queued) {
            dispatch(id);
        }
        return queued;
    }

    @Override
    public void resumePending() {
        var awaiting = access(alerts::awaitingDelivery, List.<StockAlert>of());
        for (var alert : awaiting) {
            if (alert.state() == StockAlertState.PENDING) {
                attempt(alert.id(), alert.attempts());
            } else {
                var remaining = Duration.between(clock.instant(), alert.nextAttemptAt());
                schedule(alert, remaining.isNegative() ? Duration.ZERO : remaining);
            }
        }
    }

    private <T> T access(Supplier<T> operation, T whenClosed) {
        databaseAccess.readLock().lock();
        try {
            return closed ? whenClosed : operation.get();
        } finally {
            databaseAccess.readLock().unlock();
        }
    }

    @Override
    public void close() {
        databaseAccess.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                pending.values().forEach(PendingTask::cancel);
                pending.clear();
                scheduler.close();
            }
        } finally {
            databaseAccess.writeLock().unlock();
        }
    }

    private static final class PendingTask {
        private final String sku;
        private final long cycle;
        private volatile RetryScheduler.Cancellable handle;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private PendingTask(String sku, long cycle) {
            this.sku = sku;
            this.cycle = cycle;
        }

        private void cancel() {
            cancelled.set(true);
            var current = handle;
            if (current != null) {
                current.cancel();
            }
        }

        private void attach(RetryScheduler.Cancellable scheduled) {
            handle = scheduled;
            if (cancelled.get()) {
                scheduled.cancel();
            }
        }
    }
}

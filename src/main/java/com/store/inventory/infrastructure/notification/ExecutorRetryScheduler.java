package com.store.inventory.infrastructure.notification;

import com.store.inventory.application.notification.RetryScheduler;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExecutorRetryScheduler implements RetryScheduler {

    private final ScheduledThreadPoolExecutor executor;

    public ExecutorRetryScheduler() {
        var sequence = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(2, task -> {
            var thread = new Thread(task, "inventory-alert-retry-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        var future = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

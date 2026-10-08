package com.store.inventory.application.notification;

import java.time.Duration;

public interface RetryScheduler extends AutoCloseable {

    Cancellable schedule(Duration delay, Runnable task);

    @Override
    void close();

    interface Cancellable {
        void cancel();
    }
}

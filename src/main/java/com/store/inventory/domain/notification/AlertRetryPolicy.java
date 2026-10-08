package com.store.inventory.domain.notification;

import java.time.Duration;
import java.util.Optional;
import java.util.function.DoubleSupplier;

public final class AlertRetryPolicy {

    public Optional<Duration> afterFailure(int attempts, DoubleSupplier random) {
        if (attempts < 1 || attempts > 6) {
            throw new IllegalArgumentException("Attempts must be between one and six");
        }
        if (attempts == 6) {
            return Optional.empty();
        }
        double sample = random.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample > 1) {
            throw new IllegalArgumentException("Jitter sample must be between zero and one");
        }
        long baseNanos = Duration.ofSeconds(1L << attempts).toNanos();
        return Optional.of(Duration.ofNanos(Math.round(baseNanos * (0.8 + 0.4 * sample))));
    }
}

package com.store.inventory.domain.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StockAlert(UUID id, String sku, int availableUnits, long cycle, Instant createdAt,
        StockAlertState state, int attempts, Instant nextAttemptAt, String lastError, String traceId) {

    public StockAlert {
        Objects.requireNonNull(id, "Alert ID is required");
        if (sku == null || sku.isBlank() || availableUnits < 0 || availableUnits > 5 || cycle <= 0) {
            throw new IllegalArgumentException("Invalid low stock alert");
        }
        Objects.requireNonNull(createdAt, "Creation time is required");
        Objects.requireNonNull(state, "Alert state is required");
        Objects.requireNonNull(traceId, "Trace ID is required");
        if (attempts < 0 || attempts > 6) {
            throw new IllegalArgumentException("Alert attempts must be between zero and six");
        }
    }
}

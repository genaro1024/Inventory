package com.store.inventory.domain.repository;

import com.store.inventory.domain.notification.AlertEvaluation;
import com.store.inventory.domain.notification.StockAlert;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockAlertRepository {

    /** Called inside the product operation transaction. */
    AlertEvaluation evaluate(String sku, int availableUnits, boolean replenished, Instant now);

    Optional<StockAlert> findById(UUID id);

    List<StockAlert> findBySku(String sku);

    List<StockAlert> deadLetters();

    List<StockAlert> awaitingDelivery();

    /** Atomically claims a pending attempt, checking its replenishment cycle. */
    Optional<StockAlert> claim(UUID id, int expectedAttempts);

    boolean delivered(UUID id, int attempt);

    Optional<StockAlert> failed(UUID id, int attempt, String error, Instant nextAttemptAt);

    /** Requeues only a dead letter that still belongs to the current stock cycle. */
    boolean requeueDeadLetter(UUID id);
}

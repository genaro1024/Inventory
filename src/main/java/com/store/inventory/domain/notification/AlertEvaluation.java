package com.store.inventory.domain.notification;

import java.util.Optional;

public record AlertEvaluation(long cycle, Optional<StockAlert> alert) {

    public static AlertEvaluation none() {
        return new AlertEvaluation(0, Optional.empty());
    }
}

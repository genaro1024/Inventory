package com.store.inventory.domain.notification;

public final class LowStockPolicy {

    private LowStockPolicy() {
    }

    public static boolean shouldAlert(int availableUnits, long replenishmentCycle, boolean alreadyCreated) {
        if (availableUnits < 0) {
            throw new IllegalArgumentException("Available units must not be negative");
        }
        return replenishmentCycle > 0 && availableUnits <= 5 && !alreadyCreated;
    }
}

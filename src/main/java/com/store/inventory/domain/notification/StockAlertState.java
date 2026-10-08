package com.store.inventory.domain.notification;

public enum StockAlertState {
    PENDING,
    DELIVERING,
    RETRY_WAIT,
    DELIVERED,
    CANCELLED,
    DEAD_LETTER
}

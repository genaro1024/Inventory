package com.store.inventory.domain;

public final class StockCapacityExceededException extends IllegalArgumentException {

    public StockCapacityExceededException(ArithmeticException cause) {
        super("Stock exceeds the supported maximum", cause);
    }
}

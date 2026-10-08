package com.store.inventory.domain;

/**
 * Domain failure that can be translated to the public contract by an adapter.
 */
public final class OrderLimitViolationException extends IllegalArgumentException {

    private final String sku;
    private final int requested;
    private final int limit;

    public OrderLimitViolationException(String sku, int requested, int limit) {
        super("Order limit for " + sku + " is " + limit + "; requested " + requested);
        this.sku = sku;
        this.requested = requested;
        this.limit = limit;
    }

    public String sku() {
        return sku;
    }

    public int requested() {
        return requested;
    }

    public int limit() {
        return limit;
    }
}

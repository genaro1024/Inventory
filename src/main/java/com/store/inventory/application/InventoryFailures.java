package com.store.inventory.application;

public final class InventoryFailures {

    private InventoryFailures() {
    }

    public static final class InvalidInput extends IllegalArgumentException {
        public InvalidInput(String detail) {
            super(detail);
        }
    }

    public static final class ProductAlreadyExists extends IllegalArgumentException {
        public ProductAlreadyExists() {
            super("El producto ya existe");
        }
    }

    public static final class UnknownProduct extends IllegalArgumentException {
        public UnknownProduct(String sku) {
            super("Product is not registered: " + sku);
        }
    }

    public static final class OrderConflict extends IllegalArgumentException {
        public OrderConflict() {
            super("Order ID already belongs to a different product or quantity");
        }
    }

    public static final class NoActiveReservation extends IllegalStateException {
        private final boolean expired;

        public NoActiveReservation(String orderId, boolean expired) {
            super("Order " + orderId + " has no active reservation; expired=" + expired);
            this.expired = expired;
        }

        public boolean expired() {
            return expired;
        }
    }
}

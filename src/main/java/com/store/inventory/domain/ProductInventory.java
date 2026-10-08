package com.store.inventory.domain;

import java.util.Objects;

/**
 * Warehouse units that have not been sold, including units held by active reservations.
 */
public record ProductInventory(Product product, int onHand) {

    public ProductInventory {
        Objects.requireNonNull(product, "Product is required");
        if (onHand < 0) {
            throw new IllegalArgumentException("On-hand stock must not be negative");
        }
    }

    public String sku() {
        return product.sku();
    }

    public ProductInventory replenish(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        try {
            return new ProductInventory(product, Math.addExact(onHand, quantity));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Stock exceeds the supported maximum", overflow);
        }
    }

    public ProductInventory sell(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (quantity > onHand) {
            throw new IllegalStateException("Sold units exceed on-hand stock");
        }
        return new ProductInventory(product, onHand - quantity);
    }
}

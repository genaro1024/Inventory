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
}

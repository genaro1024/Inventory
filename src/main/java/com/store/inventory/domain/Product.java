package com.store.inventory.domain;

public record Product(String sku, Category category) {

    public Product {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("Category is required");
        }
    }
}

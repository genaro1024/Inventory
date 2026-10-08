package com.store.inventory.infrastructure.jpa;

import com.store.inventory.domain.Category;
import com.store.inventory.domain.Product;
import com.store.inventory.domain.ProductInventory;
import jakarta.persistence.Column;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_inventory", check = @CheckConstraint(
        name = "ck_inventory_stock", constraint = "on_hand >= 0"))
public class ProductInventoryEntity {

    @Id
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(name = "on_hand", nullable = false)
    private int onHand;
    @Column(name = "stock_cycle", nullable = false)
    private long stockCycle;
    @Column(name = "alert_created", nullable = false)
    private boolean alertCreated;

    protected ProductInventoryEntity() {
    }

    ProductInventoryEntity(ProductInventory inventory) {
        sku = inventory.sku();
        category = inventory.product().category();
        onHand = inventory.onHand();
    }

    ProductInventory toDomain() {
        return new ProductInventory(new Product(sku, category), onHand);
    }

    long stockCycle() {
        return stockCycle;
    }

    boolean alertCreated() {
        return alertCreated;
    }

    void startStockCycle() {
        stockCycle = Math.addExact(stockCycle, 1);
        alertCreated = false;
    }

    void markAlertCreated() {
        alertCreated = true;
    }
}

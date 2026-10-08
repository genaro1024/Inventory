package com.store.inventory.domain.repository;

import com.store.inventory.domain.ProductInventory;
import java.util.Optional;

public interface ProductInventoryRepository {

    Optional<ProductInventory> findBySku(String sku);

    /** Returns false if the SKU is already registered, without overwriting it. */
    boolean insert(ProductInventory inventory);

    /** Replaces an existing snapshot only if it still matches the expected value. */
    boolean replace(ProductInventory expected, ProductInventory replacement);
}

package com.store.inventory.application;

import com.store.inventory.domain.ProductInventory;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs one product operation atomically. Repositories must share its transaction context.
 * The callback may be retried after a storage conflict and must not deliver notifications.
 */
public interface InventoryOperationExecutor {

    <T> T execute(String sku, Function<Optional<ProductInventory>, T> operation);
}

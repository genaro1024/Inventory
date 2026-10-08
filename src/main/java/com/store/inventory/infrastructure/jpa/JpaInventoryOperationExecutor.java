package com.store.inventory.infrastructure.jpa;

import com.store.inventory.application.InventoryOperationExecutor;
import com.store.inventory.domain.ProductInventory;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class JpaInventoryOperationExecutor implements InventoryOperationExecutor {

    private static final int MAX_ATTEMPTS = 3;
    private final JpaTransactions transactions;

    JpaInventoryOperationExecutor(JpaTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public <T> T execute(String sku, Function<Optional<ProductInventory>, T> operation) {
        Objects.requireNonNull(sku, "SKU is required");
        Objects.requireNonNull(operation, "Operation is required");
        if (transactions.isParticipating()) {
            throw new IllegalStateException("Product operations must not be nested");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.write(manager -> {
                    var product = manager.find(ProductInventoryEntity.class, sku, LockModeType.PESSIMISTIC_WRITE);
                    return operation.apply(Optional.ofNullable(product).map(ProductInventoryEntity::toDomain));
                });
            } catch (RetryOperationException conflict) {
                if (attempt == MAX_ATTEMPTS) {
                    throw conflict;
                }
            }
        }
    }
}

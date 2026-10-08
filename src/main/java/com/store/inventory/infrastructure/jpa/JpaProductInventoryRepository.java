package com.store.inventory.infrastructure.jpa;

import com.store.inventory.domain.ProductInventory;
import com.store.inventory.domain.repository.ProductInventoryRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import java.util.Optional;

public final class JpaProductInventoryRepository implements ProductInventoryRepository {

    private final JpaTransactions transactions;

    public JpaProductInventoryRepository(EntityManagerFactory factory) {
        transactions = new JpaTransactions(factory);
    }

    @Override
    public Optional<ProductInventory> findBySku(String sku) {
        Objects.requireNonNull(sku, "SKU is required");
        return transactions.read(manager -> Optional.ofNullable(manager.find(ProductInventoryEntity.class, sku))
                .map(ProductInventoryEntity::toDomain));
    }

    @Override
    public boolean insert(ProductInventory inventory) {
        Objects.requireNonNull(inventory, "Inventory is required");
        return transactions.insert(new ProductInventoryEntity(inventory));
    }

    @Override
    public boolean replace(ProductInventory expected, ProductInventory replacement) {
        Objects.requireNonNull(expected, "Expected inventory is required");
        Objects.requireNonNull(replacement, "Replacement inventory is required");
        if (!expected.product().equals(replacement.product())) {
            throw new IllegalArgumentException("An update must preserve the registered product");
        }
        return transactions.write(manager -> manager.createQuery("""
                update ProductInventoryEntity p set p.onHand = :replacement
                where p.sku = :sku and p.category = :category and p.onHand = :expected
                """).setParameter("replacement", replacement.onHand())
                .setParameter("sku", expected.sku()).setParameter("category", expected.product().category())
                .setParameter("expected", expected.onHand()).executeUpdate() == 1);
    }
}

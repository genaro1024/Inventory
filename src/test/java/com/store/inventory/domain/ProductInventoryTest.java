package com.store.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductInventoryTest {

    @Test
    void allowsARegisteredProductWithoutInitialStock() {
        var inventory = new ProductInventory(new Product("SKU-1", Category.STANDARD), 0);

        assertThat(inventory.sku()).isEqualTo("SKU-1");
        assertThat(inventory.onHand()).isZero();
    }

    @Test
    void rejectsNegativeStockAndMissingProduct() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ProductInventory(new Product("SKU-1", Category.STANDARD), -1));
        assertThatNullPointerException().isThrownBy(() -> new ProductInventory(null, 0));
    }

    @Test
    void replenishesWithoutChangingTheOriginalSnapshot() {
        var original = new ProductInventory(new Product("SKU-1", Category.STANDARD), 10);
        var replenished = original.replenish(5);

        assertThat(replenished.onHand()).isEqualTo(15);
        assertThat(replenished.product()).isEqualTo(original.product());
        assertThat(original.onHand()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsInvalidReplenishment(int quantity) {
        var inventory = new ProductInventory(new Product("SKU-1", Category.STANDARD), 10);

        assertThatIllegalArgumentException().isThrownBy(() -> inventory.replenish(quantity));
        assertThat(inventory.onHand()).isEqualTo(10);
    }

    @Test
    void rejectsOverflowInsteadOfWrappingStockToANegativeNumber() {
        var inventory = new ProductInventory(new Product("SKU-1", Category.STANDARD), Integer.MAX_VALUE);

        assertThatIllegalArgumentException().isThrownBy(() -> inventory.replenish(1));
        assertThat(inventory.onHand()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void sellingUnitsPreservesTheProductAndDoesNotMutateTheOriginal() {
        var original = new ProductInventory(new Product("SKU-1", Category.STANDARD), 10);

        assertThat(original.sell(3)).isEqualTo(new ProductInventory(original.product(), 7));
        assertThat(original.onHand()).isEqualTo(10);
        assertThat(original.sell(10).onHand()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidSaleQuantity(int quantity) {
        var inventory = new ProductInventory(new Product("SKU-1", Category.STANDARD), 10);

        assertThatIllegalArgumentException().isThrownBy(() -> inventory.sell(quantity));
    }

    @Test
    void cannotSellMoreUnitsThanTheWarehouseContains() {
        var inventory = new ProductInventory(new Product("SKU-1", Category.STANDARD), 2);

        org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(() -> inventory.sell(3));
        assertThat(inventory.onHand()).isEqualTo(2);
    }
}

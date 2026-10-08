package com.store.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

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
}

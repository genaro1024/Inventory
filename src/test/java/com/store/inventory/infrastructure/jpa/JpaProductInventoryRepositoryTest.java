package com.store.inventory.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.store.inventory.domain.Category;
import com.store.inventory.domain.Product;
import com.store.inventory.domain.ProductInventory;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class JpaProductInventoryRepositoryTest extends JpaRepositoryTestSupport {

    private JpaProductInventoryRepository repository;

    @BeforeEach
    void initializeRepository() {
        repository = database.inventories();
    }
    private final Product product = new Product("SKU-1", Category.STANDARD);

    @Test
    void startsEmptyAndStoresProductAndStockTogether() {
        assertThat(repository.findBySku("SKU-1")).isEmpty();
        var inventory = new ProductInventory(product, 10);

        assertThat(repository.insert(inventory)).isTrue();
        assertThat(repository.findBySku("SKU-1")).contains(inventory);
        assertThat(repository.findBySku("sku-1")).isEmpty();
    }

    @Test
    void duplicateInsertPreservesExistingProductAndStock() {
        var original = new ProductInventory(product, 10);
        repository.insert(original);

        assertThat(repository.insert(new ProductInventory(
                new Product("SKU-1", Category.FLASH_SALE), 99))).isFalse();
        assertThat(repository.findBySku("SKU-1")).contains(original);
    }

    @Test
    void staleUpdateCannotOverwriteMoreRecentStock() {
        var original = new ProductInventory(product, 10);
        var updated = new ProductInventory(product, 15);
        repository.insert(original);

        assertThat(repository.replace(original, updated)).isTrue();
        assertThat(repository.replace(original, new ProductInventory(product, 20))).isFalse();
        assertThat(repository.findBySku("SKU-1")).contains(updated);
        assertThat(original.onHand()).isEqualTo(10);
    }

    @Test
    void updateDoesNotCreateAnUnknownProduct() {
        assertThat(repository.replace(new ProductInventory(product, 0), new ProductInventory(product, 10)))
                .isFalse();
        assertThat(repository.findBySku("SKU-1")).isEmpty();
    }

    @Test
    void updateCannotChangeProductIdentityOrCategory() {
        var original = new ProductInventory(product, 10);
        repository.insert(original);

        assertThatIllegalArgumentException().isThrownBy(() -> repository.replace(original,
                new ProductInventory(new Product("SKU-2", Category.STANDARD), 10)));
        assertThatIllegalArgumentException().isThrownBy(() -> repository.replace(original,
                new ProductInventory(new Product("SKU-1", Category.FLASH_SALE), 10)));
        assertThat(repository.findBySku("SKU-1")).contains(original);
    }

    @Test
    void instancesDoNotShareState() {
        repository.insert(new ProductInventory(product, 10));

        try (var other = new H2InventoryDatabase()) {
            assertThat(other.inventories().findBySku("SKU-1")).isEmpty();
        }
    }

    @Test
    void concurrentRegistrationHasOnlyOneWinner() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = List.<Callable<Boolean>>of(
                    () -> repository.insert(new ProductInventory(product, 10)),
                    () -> repository.insert(new ProductInventory(product, 20)));
            var results = executor.invokeAll(tasks);

            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
            assertThat(repository.findBySku("SKU-1").orElseThrow().onHand()).isIn(10, 20);
        }
    }
}

package com.store.inventory.infrastructure.demo;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.util.List;

/** Explicit demonstration data loaded once into an empty service through its public contract. */
public final class DemoInventorySeed {

    public SeedSummary load(InventoryService service) {
        prepare(service, "DEMO-STANDARD", ProductCategory.STANDARD, 20, 3, 2);
        prepare(service, "DEMO-PREORDER", ProductCategory.PRE_ORDER, 30, 5, 3);
        prepare(service, "DEMO-FLASH", ProductCategory.FLASH_SALE, 8, 2, 2);
        service.registerProduct("DEMO-LOW", ProductCategory.STANDARD);
        service.addStock("DEMO-LOW", 4);
        service.registerProduct("DEMO-EMPTY", ProductCategory.STANDARD);
        return new SeedSummary(
                List.of("DEMO-STANDARD", "DEMO-PREORDER", "DEMO-FLASH", "DEMO-LOW", "DEMO-EMPTY"),
                List.of("DEMO-STANDARD-ACTIVE", "DEMO-PREORDER-ACTIVE", "DEMO-FLASH-ACTIVE"),
                List.of("DEMO-STANDARD-PAID", "DEMO-PREORDER-PAID", "DEMO-FLASH-PAID"));
    }

    private void prepare(InventoryService service, String sku, ProductCategory category,
            int stock, int activeQuantity, int paidQuantity) {
        service.registerProduct(sku, category);
        service.addStock(sku, stock);
        service.reserve(sku + "-ACTIVE", sku, activeQuantity);
        service.reserve(sku + "-PAID", sku, paidQuantity);
        service.confirm(sku + "-PAID");
    }

    public record SeedSummary(List<String> products, List<String> activeOrders, List<String> paidOrders) {
    }
}

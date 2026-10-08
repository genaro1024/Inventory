package com.store.inventory.web;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Productos")
@RequestMapping(path = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductController {

    private final InventoryService service;

    public ProductController(InventoryService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "registerProduct", summary = "Registrar un producto", description = "Crea su ficha sin stock. Un SKU existente se rechaza.")
    public ApiResponse<ProductData> register(@Valid @RequestBody RegisterProductRequest request) {
        service.registerProduct(request.sku(), request.category());
        return ApiResponse.success("El producto fue registrado.", new ProductData(request.sku(), request.category()));
    }

    @PostMapping(path = "/{sku}/stock", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "addStock", summary = "Reabastecer un producto", description = "Agrega unidades. Repetir esta operación agrega unidades otra vez.")
    public ApiResponse<Void> replenish(@PathVariable("sku") String sku, @Valid @RequestBody AddStockRequest request) {
        service.addStock(sku, request.quantity());
        return ApiResponse.success("El inventario fue actualizado.", null);
    }

    @GetMapping("/{sku}/availability")
    @Operation(operationId = "available", summary = "Consultar disponibilidad", description = "Excluye reservas activas. Un SKU desconocido tiene cero disponibles.")
    public ApiResponse<AvailabilityData> availability(@PathVariable("sku") String sku) {
        return ApiResponse.success("Disponibilidad consultada.", new AvailabilityData(sku, service.available(sku)));
    }

    public record RegisterProductRequest(@NotBlank @Size(max = 255) String sku, @NotNull ProductCategory category) {
    }

    public record AddStockRequest(@NotNull @Positive Integer quantity) {
    }

    public record ProductData(String sku, ProductCategory category) {
    }

    public record AvailabilityData(String sku, int availableUnits) {
    }
}

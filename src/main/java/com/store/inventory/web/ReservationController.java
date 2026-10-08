package com.store.inventory.web;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.Reservation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/reservations", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReservationController {

    private final InventoryService service;

    public ReservationController(InventoryService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Reservation> reserve(@Valid @RequestBody ReserveRequest request) {
        return ApiResponse.success("Tu reserva fue procesada.",
                service.reserve(request.orderId(), request.sku(), request.quantity()));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<Void> confirm(@PathVariable("orderId") String orderId) {
        service.confirm(orderId);
        return ApiResponse.success("Tu pedido fue confirmado.", null);
    }

    public record ReserveRequest(@NotBlank @Size(max = 255) String orderId, @NotBlank @Size(max = 255) String sku,
            @NotNull @Positive Integer quantity) {
    }
}

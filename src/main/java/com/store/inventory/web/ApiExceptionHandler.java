package com.store.inventory.web;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.application.InventoryFailures;
import com.store.inventory.domain.StockCapacityExceededException;
import com.store.inventory.observability.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InventoryFailures.InvalidInput.class)
    ResponseEntity<Object> invalidInput(InventoryFailures.InvalidInput failure) {
        return rejected(400, ApiErrorMessages.forStatus(400), failure);
    }

    @ExceptionHandler(InventoryFailures.ProductAlreadyExists.class)
    ResponseEntity<Object> duplicateProduct(InventoryFailures.ProductAlreadyExists failure) {
        return rejected(409, "El producto ya existe.", failure);
    }

    @ExceptionHandler(InventoryFailures.UnknownProduct.class)
    ResponseEntity<Object> unknownProduct(InventoryFailures.UnknownProduct failure) {
        return rejected(404, "El producto no está registrado.", failure);
    }

    @ExceptionHandler(InventoryFailures.OrderConflict.class)
    ResponseEntity<Object> changedOrder(InventoryFailures.OrderConflict failure) {
        return rejected(409, "Este pedido ya fue registrado con otros datos.", failure);
    }

    @ExceptionHandler(InventoryFailures.NoActiveReservation.class)
    ResponseEntity<Object> inactiveOrder(InventoryFailures.NoActiveReservation failure) {
        return rejected(409, failure.expired() ? "La reserva de este pedido ya venció. Crea un nuevo pedido."
                : "El pedido no tiene una reserva activa.", failure);
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<Object> insufficientStock(InsufficientStockException failure) {
        return rejected(409, "No hay suficientes unidades disponibles para tu pedido.", failure);
    }

    @ExceptionHandler(OrderLimitExceededException.class)
    ResponseEntity<Object> orderLimit(OrderLimitExceededException failure) {
        return rejected(409, "Superaste el límite de unidades permitido por pedido.", failure);
    }

    @ExceptionHandler(StockCapacityExceededException.class)
    ResponseEntity<Object> capacity(StockCapacityExceededException failure) {
        return rejected(409, "No podemos agregar esa cantidad al inventario.", failure);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception failure) {
        LOG.error("Unexpected API failure", failure);
        return response(500, ApiErrorMessages.forStatus(500), new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            LOG.error("Unexpected MVC failure", failure);
        } else {
            LOG.debug("MVC request rejected: {}", TraceContext.logIdentifier(failure.getMessage()));
        }
        return response(status.value(), ApiErrorMessages.forStatus(status.value()), headers);
    }

    private ResponseEntity<Object> rejected(int status, String message, Exception failure) {
        LOG.debug("Inventory request rejected: {}", TraceContext.logIdentifier(failure.getMessage()));
        return response(status, message, new HttpHeaders());
    }

    private ResponseEntity<Object> response(int status, String message, HttpHeaders headers) {
        var safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(ApiResponse.error(message), safeHeaders, status);
    }
}

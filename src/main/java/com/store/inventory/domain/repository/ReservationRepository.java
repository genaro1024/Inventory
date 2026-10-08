package com.store.inventory.domain.repository;

import com.store.inventory.domain.OrderReservation;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository {

    Optional<OrderReservation> findByOrderId(String orderId);

    /** Returns an immutable list, including confirmed and expired order records. */
    List<OrderReservation> findBySku(String sku);

    /** Returns false if the order ID already exists, regardless of its SKU or state. */
    boolean insert(OrderReservation reservation);

    /** Updates state without changing order identity, only if the expected value still exists. */
    boolean replace(OrderReservation expected, OrderReservation replacement);
}

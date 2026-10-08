package com.store.inventory.domain.repository;

import com.store.inventory.domain.OrderReservation;
import com.store.inventory.domain.ProductInventory;
import java.time.Instant;

public interface ReservationSettlementRepository {

    /**
     * Confirms the reservation and removes its warehouse units in one transaction.
     * Returns false, without changing either record, if an expected snapshot is stale.
     */
    boolean confirm(OrderReservation expectedReservation, ProductInventory expectedInventory, Instant now);
}

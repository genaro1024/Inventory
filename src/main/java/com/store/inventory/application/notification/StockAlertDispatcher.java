package com.store.inventory.application.notification;

import java.util.UUID;

public interface StockAlertDispatcher extends AutoCloseable {

    void dispatch(UUID id);

    void cancelBefore(String sku, long cycle);

    boolean reprocess(UUID id);

    void resumePending();

    @Override
    void close();
}

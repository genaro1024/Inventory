package com.store.inventory.infrastructure.notification;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.observability.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local demonstration adapter; replace this bean with an external channel integration. */
public final class LoggingStockAlertListener implements StockAlertListener {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingStockAlertListener.class);

    @Override
    public void onLowStock(String sku, int availableUnits) {
        LOG.info("Low stock received by logging adapter: sku={} available={}", TraceContext.logIdentifier(sku), availableUnits);
    }
}

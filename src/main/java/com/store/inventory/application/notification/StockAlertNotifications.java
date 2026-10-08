package com.store.inventory.application.notification;

import com.store.inventory.domain.notification.AlertEvaluation;
import com.store.inventory.domain.repository.StockAlertRepository;
import java.time.Instant;
import java.util.Objects;

public final class StockAlertNotifications implements AutoCloseable {

    private final StockAlertRepository alerts;
    private final StockAlertDispatcher dispatcher;

    public StockAlertNotifications(StockAlertRepository alerts, StockAlertDispatcher dispatcher) {
        this.alerts = Objects.requireNonNull(alerts);
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    public AlertEvaluation evaluate(String sku, int available, boolean replenished, Instant now) {
        return alerts.evaluate(sku, available, replenished, now);
    }

    public void afterCommit(String sku, boolean replenished, AlertEvaluation evaluation) {
        if (replenished) {
            dispatcher.cancelBefore(sku, evaluation.cycle());
        }
        evaluation.alert().ifPresent(alert -> dispatcher.dispatch(alert.id()));
    }

    @Override
    public void close() {
        dispatcher.close();
    }
}

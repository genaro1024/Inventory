package com.store.inventory.configuration;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.InventoryApplicationService;
import com.store.inventory.infrastructure.jpa.JpaInventoryPersistence;
import com.store.inventory.infrastructure.notification.LoggingStockAlertListener;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InventoryServiceConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock inventoryClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(StockAlertListener.class)
    StockAlertListener stockAlertListener() {
        return new LoggingStockAlertListener();
    }

    @Bean
    InventoryService inventoryService(JpaInventoryPersistence persistence, Clock clock, StockAlertListener listener) {
        return new InventoryApplicationService(persistence.inventories(), persistence.reservations(), persistence.settlements(),
                persistence.operations(), clock, persistence.notifications(clock, listener));
    }
}

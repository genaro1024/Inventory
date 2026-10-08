package com.store.inventory.configuration;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.infrastructure.demo.DemoClock;
import com.store.inventory.infrastructure.demo.DemoInventorySeed;
import com.store.inventory.infrastructure.notification.LoggingStockAlertListener;
import com.store.inventory.observability.TraceContext;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DemoConfiguration.class);

    @Bean
    @Primary
    DemoClock demoClock(@Value("${demo.initial-time:2026-10-08T12:00:00Z}") String initialTime) {
        return new DemoClock(Instant.parse(initialTime));
    }

    @Bean
    @Primary
    StockAlertListener demoStockAlertListener() {
        return new LoggingStockAlertListener();
    }

    @Bean
    ApplicationRunner loadDemoSeed(InventoryService service, DemoClock clock,
            @Value("${demo.advance-by:PT0S}") String advanceBy) {
        return args -> {
            var advance = Duration.parse(advanceBy);
            if (advance.isNegative()) {
                throw new IllegalArgumentException("Demo advance must be nonnegative");
            }
            try (var trace = TraceContext.ensure()) {
                var summary = new DemoInventorySeed().load(service);
                clock.advance(advance);
                LOG.info("Demo seed loaded: products={} activeOrders={} paidOrders={} time={}",
                        summary.products().size(), summary.activeOrders().size(), summary.paidOrders().size(), clock.instant());
            }
        };
    }
}

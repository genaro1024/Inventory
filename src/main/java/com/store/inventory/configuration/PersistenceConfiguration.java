package com.store.inventory.configuration;

import com.store.inventory.domain.repository.ProductInventoryRepository;
import com.store.inventory.domain.repository.ReservationRepository;
import com.store.inventory.domain.repository.ReservationSettlementRepository;
import com.store.inventory.infrastructure.jpa.JpaInventoryPersistence;
import com.store.inventory.application.InventoryOperationExecutor;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    @Bean
    JpaInventoryPersistence inventoryPersistence(EntityManagerFactory factory) {
        return new JpaInventoryPersistence(factory);
    }

    @Bean
    ProductInventoryRepository productInventoryRepository(JpaInventoryPersistence persistence) {
        return persistence.inventories();
    }

    @Bean
    ReservationRepository reservationRepository(JpaInventoryPersistence persistence) {
        return persistence.reservations();
    }

    @Bean
    ReservationSettlementRepository reservationSettlementRepository(JpaInventoryPersistence persistence) {
        return persistence.settlements();
    }

    @Bean
    InventoryOperationExecutor inventoryOperationExecutor(JpaInventoryPersistence persistence) {
        return persistence.operations();
    }
}

package com.store.inventory.configuration;

import com.store.inventory.domain.repository.ProductInventoryRepository;
import com.store.inventory.domain.repository.ReservationRepository;
import com.store.inventory.infrastructure.jpa.JpaProductInventoryRepository;
import com.store.inventory.infrastructure.jpa.JpaReservationRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    @Bean
    ProductInventoryRepository productInventoryRepository(EntityManagerFactory factory) {
        return new JpaProductInventoryRepository(factory);
    }

    @Bean
    ReservationRepository reservationRepository(EntityManagerFactory factory) {
        return new JpaReservationRepository(factory);
    }
}

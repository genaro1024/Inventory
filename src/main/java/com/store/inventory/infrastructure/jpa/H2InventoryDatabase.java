package com.store.inventory.infrastructure.jpa;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.cfg.Configuration;

/** Standalone persistence bootstrap used by the public factory, without a Spring context. */
public final class H2InventoryDatabase implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final EntityManagerFactory factory;
    private final JpaInventoryPersistence persistence;
    private final AtomicBoolean closed = new AtomicBoolean();

    public H2InventoryDatabase() {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:inventory-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        dataSource = new HikariDataSource(config);
        try {
            factory = buildFactory();
            persistence = new JpaInventoryPersistence(factory);
        } catch (RuntimeException failure) {
            releaseDataSource();
            throw failure;
        }
    }

    private EntityManagerFactory buildFactory() {
        var configuration = new Configuration()
                .addAnnotatedClass(ProductInventoryEntity.class)
                .addAnnotatedClass(ReservationEntity.class)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.show_sql", "false");
        configuration.getProperties().put("hibernate.connection.datasource", dataSource);
        return configuration.buildSessionFactory();
    }

    public EntityManagerFactory entityManagerFactory() {
        return factory;
    }

    public JpaProductInventoryRepository inventories() {
        return persistence.inventories();
    }

    public JpaReservationRepository reservations() {
        return persistence.reservations();
    }

    public JpaReservationSettlementRepository settlements() {
        return persistence.settlements();
    }

    public JpaInventoryOperationExecutor operations() {
        return persistence.operations();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            factory.close();
        } finally {
            releaseDataSource();
        }
    }

    private void releaseDataSource() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            // Keep H2 alive across pool connection recycling, but destroy it when its owner closes.
            statement.execute("SET DB_CLOSE_DELAY 0");
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not release the H2 database", failure);
        } finally {
            dataSource.close();
        }
    }
}

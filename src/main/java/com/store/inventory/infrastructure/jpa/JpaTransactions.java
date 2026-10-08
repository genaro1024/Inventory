package com.store.inventory.infrastructure.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

final class JpaTransactions {

    private final EntityManagerFactory factory;

    JpaTransactions(EntityManagerFactory factory) {
        this.factory = Objects.requireNonNull(factory, "Entity manager factory is required");
    }

    <T> T read(Function<EntityManager, T> operation) {
        try (var manager = factory.createEntityManager()) {
            return operation.apply(manager);
        }
    }

    <T> T write(Function<EntityManager, T> operation) {
        try (var manager = factory.createEntityManager()) {
            var transaction = manager.getTransaction();
            transaction.begin();
            try {
                var result = operation.apply(manager);
                transaction.commit();
                return result;
            } catch (RuntimeException failure) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw failure;
            }
        }
    }

    boolean insert(Object entity) {
        try {
            return write(manager -> {
                manager.persist(entity);
                return true;
            });
        } catch (RuntimeException failure) {
            // SQLSTATE 23505 means a duplicate key in H2 and PostgreSQL.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    return false;
                }
            }
            throw failure;
        }
    }
}

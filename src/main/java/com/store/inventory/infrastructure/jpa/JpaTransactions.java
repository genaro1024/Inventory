package com.store.inventory.infrastructure.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

final class JpaTransactions {

    private final EntityManagerFactory factory;
    private final ThreadLocal<EntityManager> current = new ThreadLocal<>();

    JpaTransactions(EntityManagerFactory factory) {
        this.factory = Objects.requireNonNull(factory, "Entity manager factory is required");
    }

    <T> T read(Function<EntityManager, T> operation) {
        var manager = current.get();
        if (manager != null) {
            return operation.apply(manager);
        }
        try (var standalone = factory.createEntityManager()) {
            return operation.apply(standalone);
        }
    }

    <T> T write(Function<EntityManager, T> operation) {
        var joined = current.get();
        if (joined != null) {
            try {
                var result = operation.apply(joined);
                joined.flush();
                // Bulk JPQL updates bypass the persistence context; later reads must see them.
                joined.clear();
                return result;
            } catch (RuntimeException | Error failure) {
                joined.getTransaction().setRollbackOnly();
                throw failure;
            }
        }
        try (var manager = factory.createEntityManager()) {
            var transaction = manager.getTransaction();
            transaction.begin();
            current.set(manager);
            try {
                var result = operation.apply(manager);
                transaction.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw failure;
            } finally {
                current.remove();
            }
        }
    }

    boolean insert(Object entity) {
        boolean joined = isParticipating();
        try {
            return write(manager -> {
                manager.persist(entity);
                manager.flush();
                return true;
            });
        } catch (RuntimeException failure) {
            // SQLSTATE 23505 means a duplicate key in H2 and PostgreSQL.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    if (joined) {
                        throw new RetryOperationException(failure);
                    }
                    return false;
                }
            }
            throw failure;
        }
    }

    boolean isParticipating() {
        return current.get() != null;
    }
}

package com.store.inventory.infrastructure.jpa;

import com.store.inventory.domain.notification.AlertEvaluation;
import com.store.inventory.domain.notification.LowStockPolicy;
import com.store.inventory.domain.notification.StockAlert;
import com.store.inventory.domain.notification.StockAlertState;
import com.store.inventory.domain.repository.StockAlertRepository;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JpaStockAlertRepository implements StockAlertRepository {

    private final JpaTransactions transactions;

    JpaStockAlertRepository(JpaTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public AlertEvaluation evaluate(String sku, int availableUnits, boolean replenished, Instant now) {
        if (!transactions.isParticipating()) {
            throw new IllegalStateException("Alert evaluation requires a product transaction");
        }
        return transactions.write(manager -> {
            var product = manager.find(ProductInventoryEntity.class, sku);
            if (product == null) {
                return AlertEvaluation.none();
            }
            if (replenished) {
                product.startStockCycle();
                manager.createQuery("""
                        update StockAlertEntity a set a.state = :cancelled, a.nextAttemptAt = null
                        where a.sku = :sku and a.state in :pending
                        """).setParameter("cancelled", StockAlertState.CANCELLED).setParameter("sku", sku)
                        .setParameter("pending", List.of(StockAlertState.PENDING,
                                StockAlertState.RETRY_WAIT, StockAlertState.DELIVERING)).executeUpdate();
            }
            if (!LowStockPolicy.shouldAlert(availableUnits, product.stockCycle(), product.alertCreated())) {
                return new AlertEvaluation(product.stockCycle(), Optional.empty());
            }
            var alert = new StockAlertEntity(sku, availableUnits, product.stockCycle(), now);
            manager.persist(alert);
            product.markAlertCreated();
            return new AlertEvaluation(product.stockCycle(), Optional.of(alert.toDomain()));
        });
    }

    @Override
    public Optional<StockAlert> findById(UUID id) {
        return transactions.read(manager -> Optional.ofNullable(manager.find(StockAlertEntity.class, id))
                .map(StockAlertEntity::toDomain));
    }

    @Override
    public List<StockAlert> findBySku(String sku) {
        return transactions.read(manager -> manager.createQuery(
                "select a from StockAlertEntity a where a.sku = :sku order by a.cycle", StockAlertEntity.class)
                .setParameter("sku", sku).getResultList().stream().map(StockAlertEntity::toDomain).toList());
    }

    @Override
    public List<StockAlert> deadLetters() {
        return findByStates(List.of(StockAlertState.DEAD_LETTER));
    }

    @Override
    public List<StockAlert> awaitingDelivery() {
        return findByStates(List.of(StockAlertState.PENDING, StockAlertState.RETRY_WAIT));
    }

    private List<StockAlert> findByStates(List<StockAlertState> states) {
        return transactions.read(manager -> manager.createQuery(
                "select a from StockAlertEntity a where a.state in :states order by a.createdAt", StockAlertEntity.class)
                .setParameter("states", states).getResultList().stream().map(StockAlertEntity::toDomain).toList());
    }

    @Override
    public Optional<StockAlert> claim(UUID id, int expectedAttempts) {
        if (transactions.isParticipating()) {
            throw new IllegalStateException("Delivery must start outside the inventory transaction");
        }
        return transactions.write(manager -> {
            var entity = manager.find(StockAlertEntity.class, id);
            if (entity == null) {
                return Optional.empty();
            }
            var original = entity.toDomain();
            var product = manager.find(ProductInventoryEntity.class, original.sku(), LockModeType.PESSIMISTIC_WRITE);
            manager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            var current = entity.toDomain();
            if ((current.state() != StockAlertState.PENDING && current.state() != StockAlertState.RETRY_WAIT)
                    || current.attempts() != expectedAttempts) {
                return Optional.empty();
            }
            if (product == null || product.stockCycle() != current.cycle()) {
                entity.cancel();
                return Optional.empty();
            }
            entity.claim();
            return Optional.of(entity.toDomain());
        });
    }

    @Override
    public boolean delivered(UUID id, int attempt) {
        return transactions.write(manager -> manager.createQuery("""
                update StockAlertEntity a set a.state = :delivered, a.nextAttemptAt = null
                where a.id = :id and a.state = :delivering and a.attempts = :attempt
                """).setParameter("delivered", StockAlertState.DELIVERED)
                .setParameter("delivering", StockAlertState.DELIVERING)
                .setParameter("id", id).setParameter("attempt", attempt).executeUpdate() == 1);
    }

    @Override
    public Optional<StockAlert> failed(UUID id, int attempt, String error, Instant nextAttemptAt) {
        return transactions.write(manager -> {
            var entity = manager.find(StockAlertEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (entity == null) {
                return Optional.empty();
            }
            var alert = entity.toDomain();
            if (alert.state() != StockAlertState.DELIVERING || alert.attempts() != attempt) {
                return Optional.empty();
            }
            entity.failed(error, nextAttemptAt);
            return Optional.of(entity.toDomain());
        });
    }

    @Override
    public boolean requeueDeadLetter(UUID id) {
        return transactions.write(manager -> {
            var entity = manager.find(StockAlertEntity.class, id);
            if (entity == null) {
                return false;
            }
            var original = entity.toDomain();
            var product = manager.find(ProductInventoryEntity.class, original.sku(), LockModeType.PESSIMISTIC_WRITE);
            manager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            var current = entity.toDomain();
            if (current.state() != StockAlertState.DEAD_LETTER || product == null
                    || product.stockCycle() != current.cycle()) {
                return false;
            }
            entity.requeue();
            return true;
        });
    }
}

package com.store.inventory.infrastructure.jpa;

import com.store.inventory.domain.notification.StockAlert;
import com.store.inventory.domain.notification.StockAlertState;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import com.store.inventory.observability.TraceContext;

@Entity
@Table(name = "stock_alerts", indexes = {
        @Index(name = "idx_alert_sku", columnList = "sku"),
        @Index(name = "idx_alert_delivery", columnList = "state,next_attempt_at")},
        check = @CheckConstraint(name = "ck_alert_data",
                constraint = "available_units between 0 and 5 and attempts between 0 and 6 and stock_cycle > 0"))
public class StockAlertEntity {

    @Id
    private UUID id;
    @Column(nullable = false)
    private String sku;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sku", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_alert_product"))
    private ProductInventoryEntity product;
    @Column(name = "available_units", nullable = false)
    private int availableUnits;
    @Column(name = "stock_cycle", nullable = false)
    private long cycle;
    @Column(name = "created_at", nullable = false, secondPrecision = 9)
    private Instant createdAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockAlertState state;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", secondPrecision = 9)
    private Instant nextAttemptAt;
    @Lob
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    protected StockAlertEntity() {
    }

    StockAlertEntity(String sku, int availableUnits, long cycle, Instant now) {
        id = UUID.randomUUID();
        this.sku = sku;
        this.availableUnits = availableUnits;
        this.cycle = cycle;
        createdAt = now;
        state = StockAlertState.PENDING;
        traceId = TraceContext.currentOrCreate();
    }

    StockAlert toDomain() {
        return new StockAlert(id, sku, availableUnits, cycle, createdAt, state, attempts, nextAttemptAt, lastError, traceId);
    }

    void cancel() {
        state = StockAlertState.CANCELLED;
        nextAttemptAt = null;
    }

    void claim() {
        state = StockAlertState.DELIVERING;
        attempts++;
        nextAttemptAt = null;
    }

    void delivered() {
        state = StockAlertState.DELIVERED;
        nextAttemptAt = null;
    }

    void failed(String error, Instant nextAttemptAt) {
        lastError = error;
        this.nextAttemptAt = nextAttemptAt;
        state = nextAttemptAt == null ? StockAlertState.DEAD_LETTER : StockAlertState.RETRY_WAIT;
    }

    void requeue() {
        state = StockAlertState.PENDING;
        attempts = 0;
        nextAttemptAt = null;
    }
}

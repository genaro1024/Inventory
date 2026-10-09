package com.store.inventory.web;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational probes use the same safe response envelope as the business API. */
@RestController
@RequestMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
public class HealthController {
    private static final Logger LOG = LoggerFactory.getLogger(HealthController.class);
    private final ApplicationAvailability availability;
    private final DataSource dataSource;

    public HealthController(ApplicationAvailability availability, DataSource dataSource) {
        this.availability = availability;
        this.dataSource = dataSource;
    }

    @GetMapping("/liveness")
    public ResponseEntity<ApiResponse<Void>> liveness() {
        return response(availability.getLivenessState() == LivenessState.CORRECT);
    }

    @GetMapping("/readiness")
    public ResponseEntity<ApiResponse<Void>> readiness() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return response(false);
        }
        try (var connection = dataSource.getConnection()) {
            return response(connection.isValid(1));
        } catch (SQLException failure) {
            LOG.debug("Database readiness check failed", failure);
            return response(false);
        }
    }

    private ResponseEntity<ApiResponse<Void>> response(boolean healthy) {
        return healthy
                ? ResponseEntity.ok(ApiResponse.success("Servicio disponible.", null))
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error("Servicio temporalmente no disponible."));
    }
}

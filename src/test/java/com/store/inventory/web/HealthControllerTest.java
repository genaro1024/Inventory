package com.store.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class HealthControllerTest {
    private final ApplicationAvailability availability = mock(ApplicationAvailability.class);
    private final DataSource dataSource = mock(DataSource.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(availability, dataSource))
                .addFilters(new TraceIdFilter()).build();
    }

    @Test
    void databaseFailureRemovesReadinessWithoutRestartingTheProcess() throws Exception {
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(dataSource.getConnection()).thenThrow(new SQLException("PRIVATE_PASSWORD"));
        assertProbe("readiness", 503);
        assertProbe("liveness", 200);
    }

    @Test
    void readinessChecksAndClosesItsConnection() throws Exception {
        var connection = mock(Connection.class);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);
        assertProbe("readiness", 200);
        verify(connection).close();
        when(connection.isValid(1)).thenReturn(false);
        assertProbe("readiness", 503);
    }

    @Test
    void startupAndShutdownRefuseTrafficWithoutQueryingTheDatabase() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        when(availability.getLivenessState()).thenReturn(LivenessState.BROKEN);
        assertProbe("readiness", 503);
        assertProbe("liveness", 503);
        verifyNoInteractions(dataSource);
    }

    private void assertProbe(String probe, int status) throws Exception {
        var response = mvc.perform(get("/health/" + probe).header("X-Trace-Id", "probe-test"))
                .andReturn().getResponse();
        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("success").asBoolean()).isEqualTo(status == 200);
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("traceId").asText()).isEqualTo("probe-test");
        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("probe-test");
        assertThat(response.getContentAsString()).doesNotContain("PRIVATE_PASSWORD", "SQLException");
    }
}

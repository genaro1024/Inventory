package com.store.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.store.inventory.api.InventoryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:api-failure;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class InventoryApiFailureTest {

    @Value("${local.server.port}")
    private int port;
    @MockitoBean
    private InventoryService service;

    @Test
    void unexpectedFailuresReturnAGenericMessageAndLogTheCauseWithTheSameTraceId() throws Exception {
        doThrow(new IllegalStateException("DATABASE_SECRET diagnostic", new RuntimeException("Technical cause")))
                .when(service).confirm("BOOM");
        var logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/reservations/BOOM/confirm"))
                    .header(TraceIdFilter.HEADER, "failure-trace-500").POST(HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var body = JsonMapper.builder().build().readTree(response.body());

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(body.size()).isEqualTo(4);
            assertThat(body.path("success").asBoolean()).isFalse();
            assertThat(body.path("data").isNull()).isTrue();
            assertThat(body.path("traceId").asText()).isEqualTo("failure-trace-500");
            assertThat(response.body()).doesNotContain("DATABASE_SECRET", "IllegalStateException", "Technical cause", "stackTrace");
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getMDCPropertyMap()).containsEntry("traceId", "failure-trace-500");
            assertThat(appender.list.getFirst().getThrowableProxy().getMessage()).contains("DATABASE_SECRET");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

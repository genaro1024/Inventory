package com.store.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.api.InventoryService;
import com.store.inventory.domain.ReservationState;
import com.store.inventory.domain.repository.ReservationRepository;
import com.store.inventory.infrastructure.demo.DemoClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo", "demo.advance-by=PT0S",
        "spring.datasource.url=jdbc:h2:mem:demo-docs-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
class DocumentationAndDemoTest {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private InventoryService service;
    @Autowired
    private ReservationRepository reservations;
    @Autowired
    private DemoClock clock;

    @Test
    void demoProfileLoadsTheSeedAndTheControlledClockReleasesOnlyExpiredUnits() {
        assertThat(reservations.findByOrderId("DEMO-STANDARD-PAID").orElseThrow().state()).isEqualTo(ReservationState.CONFIRMED);
        assertThat(service.available("DEMO-STANDARD")).isEqualTo(15);
        assertThat(service.available("DEMO-PREORDER")).isEqualTo(22);
        assertThat(service.available("DEMO-FLASH")).isEqualTo(4);
        clock.advance(Duration.ofMinutes(16));
        assertThat(service.available("DEMO-STANDARD")).isEqualTo(18);
        assertThat(service.available("DEMO-FLASH")).isEqualTo(6);
        assertThat(service.available("DEMO-PREORDER")).isEqualTo(22);
    }

    @Test
    void swaggerUiAndTheGeneratedContractDescribeOnlyTheFiveBusinessPaths() throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var ui = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui.html"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(ui.statusCode()).isEqualTo(200);
            assertThat(ui.body()).contains("swagger-ui");
            var document = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(document.statusCode()).isEqualTo(200);
            var json = JsonMapper.builder().build();
            var spec = json.readTree(document.body());
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/generated-openapi.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
            assertThat(spec.path("paths").size()).isEqualTo(5);
            for (var route : new String[]{"/products", "/products/{sku}/stock", "/products/{sku}/availability",
                    "/reservations", "/reservations/{orderId}/confirm"}) {
                assertThat(spec.path("paths").has(route)).as(route).isTrue();
            }
            assertThat(spec.path("paths").path("/products").path("post").path("responses").has("201")).isTrue();
            var error = spec.path("components").path("schemas").path("InventoryErrorResponse");
            assertThat(error.path("properties").size()).isEqualTo(4);
            assertThat(error.path("required").size()).isEqualTo(4);
            assertThat(error.path("additionalProperties").asBoolean()).isFalse();
            assertThat(spec.path("components").path("schemas").path("ApiResponseVoid")
                    .path("properties").path("data").path("nullable").asBoolean()).isTrue();
            for (var schema : spec.path("components").path("schemas").properties()) {
                if (schema.getKey().startsWith("ApiResponse")) {
                    assertThat(schema.getValue().path("properties").size()).as(schema.getKey()).isEqualTo(4);
                    assertThat(schema.getValue().path("required").size()).isEqualTo(4);
                }
            }
            var committed = json.readTree(Files.readString(Path.of("docs/openapi.json")));
            assertThat(spec.path("paths")).as("Exported operations match the runtime contract")
                    .isEqualTo(committed.path("paths"));
            assertThat(spec.path("components").path("schemas")).as("Exported schemas match the runtime contract")
                    .isEqualTo(committed.path("components").path("schemas"));
        }
    }
}

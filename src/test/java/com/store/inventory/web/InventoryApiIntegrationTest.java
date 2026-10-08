package com.store.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.support.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:api-integration;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@Import(InventoryApiIntegrationTest.TimeConfiguration.class)
class InventoryApiIntegrationTest {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private MutableClock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void allFiveEndpointsUseTheSameEnvelopeAndRespectIdempotency() throws Exception {
        var sku = "API-" + UUID.randomUUID();
        var order = "ORDER-" + UUID.randomUUID();
        var registered = post("/products", "{\"sku\":\"" + sku + "\",\"category\":\"STANDARD\"}");
        assertSuccess(registered, 201);
        assertSuccess(post("/products/" + sku + "/stock", "{\"quantity\":10}"), 200);
        var reserved = post("/reservations", reservation(order, sku, 3));
        var response = assertSuccess(reserved, 200);
        assertThat(response.path("data").path("orderId").asText()).isEqualTo(order);
        assertThat(json.readTree(post("/reservations", reservation(order, sku, 3)).body()).path("data"))
                .isEqualTo(response.path("data"));

        var confirmed = assertSuccess(post("/reservations/" + order + "/confirm", null), 200);
        assertThat(confirmed.path("data").isNull()).isTrue();
        assertSuccess(post("/reservations/" + order + "/confirm", null), 200);
        var available = assertSuccess(send("GET", "/products/" + sku + "/availability", null, "application/json", null), 200);
        assertThat(available.path("data").path("availableUnits").asInt()).isEqualTo(7);
    }

    @Test
    void unknownSkuAvailabilityIsZeroWhileReplenishingItIsNotFound() throws Exception {
        var sku = "UNKNOWN-" + UUID.randomUUID();
        var available = assertSuccess(send("GET", "/products/" + sku + "/availability", null, "application/json", null), 200);
        assertThat(available.path("data").path("availableUnits").asInt()).isZero();
        assertError(post("/products/" + sku + "/stock", "{\"quantity\":1}"), 404);
    }

    @Test
    void duplicateProductsAndConflictingOrdersAreBusinessConflicts() throws Exception {
        var sku = register("STANDARD", 10);
        assertError(post("/products", "{\"sku\":\"" + sku + "\",\"category\":\"STANDARD\"}"), 409);
        var order = "ORDER-" + UUID.randomUUID();
        post("/reservations", reservation(order, sku, 3));
        assertError(post("/reservations", reservation(order, sku, 4)), 409);
        assertError(post("/reservations", reservation("OTHER-" + UUID.randomUUID(), sku, 8)), 409);
    }

    @Test
    void flashSaleLimitsAndExpiredOrdersReturnSafeConflictMessages() throws Exception {
        var sku = register("FLASH_SALE", 10);
        assertError(post("/reservations", reservation("LIMIT-" + UUID.randomUUID(), sku, 3)), 409);
        var order = "ORDER-" + UUID.randomUUID();
        post("/reservations", reservation(order, sku, 2));
        clock.advance(Duration.ofMinutes(5));

        var expired = assertError(post("/reservations/" + order + "/confirm", null), 409);
        assertThat(expired.path("message").asText()).contains("venció");
        assertError(post("/reservations", reservation(order, sku, 2)), 409);
        var available = assertSuccess(send("GET", "/products/" + sku + "/availability", null, "application/json", null), 200);
        assertThat(available.path("data").path("availableUnits").asInt()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"sku\":\" \" ,\"category\":\"STANDARD\"}",
            "{\"sku\":\"VALID\",\"category\":null}", "{\"sku\":\"VALID\",\"category\":\"NOT_A_CATEGORY\"}",
            "{\"sku\":\"VALID\",\"category\":\"STANDARD\",\"extra\":1}", "{broken-json"
    })
    void invalidProductPayloadsNeverExposeParserOrValidationDetails(String body) throws Exception {
        var response = assertError(post("/products", body), 400);
        assertThat(response.path("message").asText()).doesNotContain("Jackson", "NotBlank", "ProductCategory", "exception");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":0}", "{\"quantity\":-1}", "{\"quantity\":1.5}",
            "{\"quantity\":\"2\"}", "{\"quantity\":2147483648}", "{\"quantity\":null}"})
    void stockQuantityMustBeAPositiveJsonInteger(String body) throws Exception {
        var sku = register("STANDARD", 10);
        assertError(post("/products/" + sku + "/stock", body), 400);
        var response = assertSuccess(send("GET", "/products/" + sku + "/availability", null, "application/json", null), 200);
        assertThat(response.path("data").path("availableUnits").asInt()).isEqualTo(10);
    }

    @Test
    void statusErrorsAndHtmlAcceptStillUseOnlyTheFourJsonFields() throws Exception {
        assertError(send("GET", "/missing-route", null, "text/html", null), 404);
        assertError(send("GET", "/products", null, "application/json", null), 405);
        assertError(send("POST", "/products", "body", "application/json", "text/plain"), 415);
        assertError(send("GET", "/products/UNKNOWN/availability", null, "text/html", null), 406);
        assertError(send("GET", "/error?message=DATABASE_SECRET", null, "text/html", null), 404);
    }

    @Test
    void traceIdsAreReturnedInTheHeaderAndBodyAndInvalidOnesAreReplaced() throws Exception {
        var uri = URI.create("http://localhost:" + port + "/products/UNKNOWN/availability");
        var accepted = client.send(HttpRequest.newBuilder(uri).header(TraceIdFilter.HEADER, "frontend-trace-123")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(assertSuccess(accepted, 200).path("traceId").asText()).isEqualTo("frontend-trace-123");
        var rejected = client.send(HttpRequest.newBuilder(uri).header(TraceIdFilter.HEADER, "x".repeat(100))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        var generated = assertSuccess(rejected, 200).path("traceId").asText();
        assertThat(generated).hasSize(36).isNotEqualTo("frontend-trace-123");
        var next = assertSuccess(send("GET", "/products/UNKNOWN/availability", null, "application/json", null), 200);
        assertThat(next.path("traceId").asText()).isNotEqualTo(generated).isNotEqualTo("frontend-trace-123");
    }

    @Test
    void identifiersExceedingTheStorageLimitAreRejectedBeforeDatabaseWrites() throws Exception {
        var tooLong = "x".repeat(256);
        assertError(post("/products", "{\"sku\":\"" + tooLong + "\",\"category\":\"STANDARD\"}"), 400);
        var sku = register("STANDARD", 10);
        assertError(post("/reservations", reservation(tooLong, sku, 1)), 400);
        assertError(post("/reservations", reservation("ORDER-1", tooLong, 1)), 400);
    }

    private String register(String category, int stock) throws Exception {
        var sku = "API-" + UUID.randomUUID();
        assertSuccess(post("/products", "{\"sku\":\"" + sku + "\",\"category\":\"" + category + "\"}"), 201);
        assertSuccess(post("/products/" + sku + "/stock", "{\"quantity\":" + stock + "}"), 200);
        return sku;
    }

    private static String reservation(String order, String sku, int quantity) {
        return "{\"orderId\":\"" + order + "\",\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}";
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body, "application/json", body == null ? null : "application/json");
    }

    private HttpResponse<String> send(String method, String path, String body, String accept, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Accept", accept);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode assertSuccess(HttpResponse<String> response, int status) {
        var body = envelope(response, status);
        assertThat(body.path("success").asBoolean()).isTrue();
        return body;
    }

    private JsonNode assertError(HttpResponse<String> response, int status) {
        var body = envelope(response, status);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        return body;
    }

    private JsonNode envelope(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        var body = json.readTree(response.body());
        assertThat(body.size()).isEqualTo(4);
        for (var field : new String[]{"success", "message", "data", "traceId"}) {
            assertThat(body.has(field)).as(field).isTrue();
        }
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("traceId").asText()).isEqualTo(response.headers().firstValue(TraceIdFilter.HEADER).orElseThrow());
        return body;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock apiClock() {
            return new MutableClock(Instant.parse("2026-10-08T12:00:00Z"));
        }
    }
}

package com.store.inventory.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    private static final List<String> ENVELOPE_FIELDS = List.of("success", "message", "data", "traceId");

    @Bean
    OpenAPI inventoryOpenApi() {
        return new OpenAPI().info(new Info().title("API de reservas de inventario").version("1.0.0")
                .description("Reservas temporales, confirmaciones idempotentes y disponibilidad. "
                        + "Todas las respuestas de negocio tienen success, message, data y traceId."));
    }

    private static ObjectSchema errorSchema() {
        var error = new ObjectSchema();
        error.addProperty("success", new BooleanSchema()._enum(List.of(false)));
        error.addProperty("message", new StringSchema().description("Mensaje apto para mostrar al cliente."));
        error.addProperty("data", new ObjectSchema().nullable(true).description("Siempre null en errores."));
        error.addProperty("traceId", traceSchema());
        error.setRequired(ENVELOPE_FIELDS);
        error.setAdditionalProperties(false);
        return error;
    }

    @Bean
    OpenApiCustomizer inventoryContractCustomizer() {
        return api -> {
            api.getComponents().getSchemas().forEach((name, schema) -> {
                if (name.startsWith("ApiResponse")) {
                    schema.setRequired(ENVELOPE_FIELDS);
                    schema.setAdditionalProperties(false);
                    if (schema.getProperties() == null || !schema.getProperties().containsKey("data")) {
                        schema.addProperty("data", new ObjectSchema().nullable(true).description("Resultado o null."));
                    }
                    if (name.equals("ApiResponseVoid")) {
                        schema.addProperty("data", new ObjectSchema().nullable(true).description("Siempre null."));
                    }
                    schema.addProperty("traceId", traceSchema());
                    schema.addProperty("message", new StringSchema().minLength(1).description("Mensaje para el cliente."));
                    ((Schema<?>) schema.getProperties().get("success")).setExample(true);
                }
            });
            for (var name : List.of("RegisterProductRequest", "ReserveRequest", "AddStockRequest")) {
                var schema = api.getComponents().getSchemas().get(name);
                schema.setAdditionalProperties(false);
                for (var field : List.of("sku", "orderId")) {
                    var identifier = (Schema<?>) schema.getProperties().get(field);
                    if (identifier != null) {
                        identifier.setMinLength(1);
                        identifier.setPattern("\\S");
                    }
                }
            }
            api.getComponents().getSchemas().get("ProductData").setRequired(List.of("sku", "category"));
            api.getComponents().getSchemas().get("AvailabilityData").setRequired(List.of("sku", "availableUnits"));
            api.getComponents().getSchemas().get("Reservation").setRequired(List.of("orderId", "sku", "quantity", "expiresAt"));
            ((Schema<?>) api.getComponents().getSchemas().get("AvailabilityData").getProperties().get("availableUnits"))
                    .setMinimum(BigDecimal.ZERO);
            api.getComponents().addSchemas("InventoryErrorResponse", errorSchema());
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                operation.addParametersItem(new Parameter().name("X-Trace-Id").in("header").required(false)
                        .description("Correlación opcional; si falta o es inválida, el servidor genera un UUID.")
                        .schema(traceSchema()));
                var codes = path.endsWith("/availability") ? List.of("400", "406", "500")
                        : path.equals("/products") ? List.of("400", "409", "406", "415", "500")
                        : path.endsWith("/stock") ? List.of("400", "404", "409", "406", "415", "500")
                        : path.endsWith("/confirm") ? List.of("400", "409", "406", "500")
                        : List.of("400", "409", "406", "415", "500");
                for (var code : codes) {
                    operation.getResponses().addApiResponse(code, new ApiResponse().description(errorDescription(code))
                            .content(new Content().addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType()
                                    .schema(new Schema<>().$ref("#/components/schemas/InventoryErrorResponse"))
                                    .example(errorExample(code)))));
                }
                operation.getResponses().values().forEach(response -> response.addHeaderObject("X-Trace-Id",
                        new Header().description("Mismo identificador que el traceId del cuerpo.").schema(traceSchema())));
            }));
        };
    }

    private static StringSchema traceSchema() {
        var schema = new StringSchema();
        schema.setMinLength(1);
        schema.setMaxLength(64);
        schema.setPattern("^[A-Za-z0-9._-]{1,64}$");
        schema.setExample("frontend-trace-123");
        return schema;
    }

    private static String errorDescription(String code) {
        return switch (code) {
            case "400" -> "Solicitud inválida.";
            case "404" -> "Producto no registrado.";
            case "409" -> "Conflicto con las reglas o el estado del inventario o pedido.";
            case "406" -> "La API responde en JSON.";
            case "415" -> "El cuerpo debe usar application/json.";
            default -> "Error interno; los detalles técnicos solo se registran en logs.";
        };
    }

    private static LinkedHashMap<String, Object> errorExample(String code) {
        var example = new LinkedHashMap<String, Object>();
        example.put("success", false);
        example.put("message", code.equals("500") ? "No pudimos completar tu solicitud. Inténtalo nuevamente."
                : code.equals("409") ? "No hay suficientes unidades disponibles para tu pedido."
                : code.equals("404") ? "El producto no está registrado."
                : "Revisa los datos de tu solicitud e inténtalo nuevamente.");
        example.put("data", null);
        example.put("traceId", "frontend-trace-123");
        return example;
    }
}

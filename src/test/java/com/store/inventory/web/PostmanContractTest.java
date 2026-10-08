package com.store.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PostmanContractTest {

    @Test
    void collectionTargetsTheDocumentedOperationsAndDeclaresEveryVariable() throws Exception {
        var mapper = JsonMapper.builder().build();
        var source = Files.readString(Path.of("postman/Inventory.postman_collection.json"));
        var collection = mapper.readTree(source);
        var spec = mapper.readTree(Files.readString(Path.of("docs/openapi.json")));
        var declared = new HashSet<String>();
        for (var variable : collection.path("variable")) {
            declared.add(variable.path("key").asText());
        }
        var variables = Pattern.compile("\\{\\{([^}]+)}}").matcher(source);
        while (variables.find()) {
            if (!variables.group(1).startsWith("$")) {
                assertThat(declared).as(variables.group(1)).contains(variables.group(1));
            }
        }
        var covered = new HashSet<String>();
        int requests = 0;
        for (var folder : collection.path("item")) {
            for (var item : folder.path("item")) {
                requests++;
                var request = item.path("request");
                String path = request.path("url").asText().replace("{{baseUrl}}", "")
                        .replaceAll("\\{\\{(?:standardSku|flashSku|preOrderSku|unknownSku)}}", "{sku}")
                        .replaceAll("\\{\\{(?:standardOrder|flashOrder|preOrder)}}", "{orderId}")
                        .replace("{{runId}}-UNKNOWN", "{orderId}");
                var method = request.path("method").asText().toLowerCase();
                if (path.equals("/missing-inventory-route")) {
                    continue;
                }
                assertThat(spec.path("paths").path(path).has(method)).as(method + " " + path).isTrue();
                covered.add(method + " " + path);
            }
        }
        assertThat(requests).isEqualTo(40);
        assertThat(covered).isEqualTo(Set.of("post /products", "post /products/{sku}/stock",
                "get /products/{sku}/availability", "post /reservations", "post /reservations/{orderId}/confirm"));
    }
}

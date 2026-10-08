package com.store.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ApiErrorControllerTest {

    @Test
    void servletErrorFallbackUsesTheSameJsonWithoutItsTechnicalAttributes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ApiErrorController()).addFilters(new TraceIdFilter()).build();
        var result = mvc.perform(get("/error").header(TraceIdFilter.HEADER, "servlet-failure-123")
                .accept("text/html")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new RuntimeException("PRIVATE_DIAGNOSTIC"))
                .requestAttr(RequestDispatcher.ERROR_MESSAGE, "PRIVATE_MESSAGE")
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/private/internal/path")).andReturn();
        var response = result.getResponse();
        var body = JsonMapper.builder().build().readTree(response.getContentAsString());

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.path("traceId").asText()).isEqualTo("servlet-failure-123");
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("PRIVATE", "/private", "RuntimeException");
    }
}

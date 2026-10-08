package com.store.inventory.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceContextTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void nestedScopesRestoreThePreviousContextAndDoNotLeak() {
        MDC.put(TraceContext.KEY, "previous");
        try (var outer = TraceContext.open("request-1")) {
            assertThat(MDC.get(TraceContext.KEY)).isEqualTo("request-1");
            try (var inner = TraceContext.open("alert-1")) {
                assertThat(MDC.get(TraceContext.KEY)).isEqualTo("alert-1");
            }
            assertThat(MDC.get(TraceContext.KEY)).isEqualTo("request-1");
        }
        assertThat(MDC.get(TraceContext.KEY)).isEqualTo("previous");
        MDC.remove(TraceContext.KEY);
        try (var scope = TraceContext.ensure()) {
            assertThat(MDC.get(TraceContext.KEY)).hasSize(36);
        }
        assertThat(MDC.get(TraceContext.KEY)).isNull();
    }

    @Test
    void controlCharactersAreEscapedForLogsWithoutChangingTheBusinessIdentifier() {
        var sku = "SKU\nforged\r\t";
        assertThat(TraceContext.logIdentifier(sku)).isEqualTo("SKU\\u000aforged\\u000d\\u0009");
        assertThat(sku).isEqualTo("SKU\nforged\r\t");
    }
}

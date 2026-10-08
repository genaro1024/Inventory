package com.store.inventory.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.store.inventory.observability.TraceContext;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(boolean success, String message, T data, String traceId) {

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, TraceContext.currentOrCreate());
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, message, null, TraceContext.currentOrCreate());
    }
}

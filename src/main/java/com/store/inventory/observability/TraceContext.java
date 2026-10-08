package com.store.inventory.observability;

import java.util.UUID;
import org.slf4j.MDC;

public final class TraceContext implements AutoCloseable {

    public static final String KEY = "traceId";
    private final String previous;

    private TraceContext(String traceId) {
        previous = MDC.get(KEY);
        MDC.put(KEY, traceId);
    }

    public static TraceContext open(String traceId) {
        return new TraceContext(traceId);
    }

    public static TraceContext ensure() {
        return open(currentOrCreate());
    }

    public static String currentOrCreate() {
        var current = MDC.get(KEY);
        return current == null ? UUID.randomUUID().toString() : current;
    }

    public static String logIdentifier(String value) {
        if (value == null) {
            return "null";
        }
        var safe = new StringBuilder();
        value.codePoints().forEach(character -> {
            if (Character.isISOControl(character)) {
                safe.append(String.format("\\u%04x", character));
            } else {
                safe.appendCodePoint(character);
            }
        });
        return safe.toString();
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove(KEY);
        } else {
            MDC.put(KEY, previous);
        }
    }
}

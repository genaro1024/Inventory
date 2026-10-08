package com.store.inventory.web;

import com.store.inventory.observability.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = "inventory.traceId";
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger LOG = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var existing = request.getAttribute(ATTRIBUTE);
        var supplied = request.getHeader(HEADER);
        String trace = existing instanceof String value ? value
                : supplied != null && ACCEPTED.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, trace);
        response.setHeader(HEADER, trace);
        long started = System.nanoTime();
        try (var context = TraceContext.open(trace)) {
            try {
                chain.doFilter(request, response);
            } finally {
                LOG.debug("HTTP {} {} -> {} ({} ms)", request.getMethod(),
                        TraceContext.logIdentifier(request.getRequestURI()), response.getStatus(),
                        (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }
}

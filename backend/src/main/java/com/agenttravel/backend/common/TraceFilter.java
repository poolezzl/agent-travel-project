package com.agenttravel.backend.common;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = safeHeader(request.getHeader("X-Trace-Id"));
        String requestId = safeHeader(request.getHeader("X-Request-Id"));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        RequestContext.set(requestId, traceId, null, null);
        response.setHeader("X-Trace-Id", traceId);
        response.setHeader("X-Request-Id", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private String safeHeader(String value) {
        return value == null || value.isBlank() || value.length() > 128 ? null : value;
    }
}

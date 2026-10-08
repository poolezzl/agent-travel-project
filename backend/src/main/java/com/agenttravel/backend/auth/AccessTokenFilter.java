package com.agenttravel.backend.auth;

import java.io.IOException;
import java.time.Instant;

import com.agenttravel.backend.common.ApiError;
import com.agenttravel.backend.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@Lazy
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
public class AccessTokenFilter extends OncePerRequestFilter {

    private final JwtTokenService tokens;
    private final AuthRepository repository;
    private final ObjectMapper objectMapper;

    public AccessTokenFilter(JwtTokenService tokens, AuthRepository repository, ObjectMapper objectMapper) {
        this.tokens = tokens;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                JwtTokenService.Claims claims = tokens.parseAccessToken(authorization.substring(7), Instant.now().getEpochSecond());
                AuthRepository.AccessIdentity identity = repository.findActiveAccessIdentity(claims.sessionId(),
                        claims.userId(), Instant.now());
                if (identity == null || !"ACTIVE".equals(identity.status())) {
                    throw new IllegalArgumentException("session revoked");
                }
                RequestContext.Context current = RequestContext.get();
                RequestContext.set(current.requestId(), current.traceId(), claims.userId(), identity.role());
            } catch (RuntimeException exception) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/problem+json;charset=UTF-8");
                RequestContext.Context context = RequestContext.get();
                ApiError error = new ApiError("https://agent-travel.local/problems/UNAUTHENTICATED",
                        "Unauthorized", HttpServletResponse.SC_UNAUTHORIZED, "访问令牌无效或已失效", "UNAUTHENTICATED",
                        context == null ? "unknown" : context.traceId(), Instant.now());
                response.getWriter().write(objectMapper.writeValueAsString(error));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}

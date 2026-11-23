package com.agenttravel.backend.community;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
class CommunityResponseFilter extends OncePerRequestFilter {
    private static final PathPattern COMMUNITY_PATH =
            PathPatternParser.defaultInstance.parse(CommunityController.ROOT + "/**");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        // 路径参数由路由解析，缓存策略须覆盖同一组地址。
        return !COMMUNITY_PATH.matches(PathContainer.parsePath(uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}

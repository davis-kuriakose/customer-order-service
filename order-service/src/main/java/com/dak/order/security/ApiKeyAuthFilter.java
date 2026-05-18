package com.dak.order.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

// Not a @Component — constructed explicitly in SecurityConfig so Spring Boot does not
// auto-register it as a standalone servlet filter. Without this, the filter would execute
// twice per request: once via the Security filter chain and once via the auto-registered
// servlet filter. OncePerRequestFilter prevents double execution but the double registration
// itself is noisy and wastes filter-chain traversal.
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final String expectedApiKey;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(String expectedApiKey, ObjectMapper objectMapper) {
        this.expectedApiKey = expectedApiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Only the two health/info actuator endpoints are exempt — matches SecurityConfig.permitAll() exactly.
        // Wildcard /actuator/** would silently expose any newly enabled endpoint (env, beans, metrics) without auth.
        return uri.equals("/actuator/health")
                || uri.equals("/actuator/info")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (!isValidKey(apiKey)) {
            log.warn("Rejected request — missing or invalid API key from {}", request.getRemoteAddr());
            writeUnauthorized(response);
            return;
        }
        // Set an authenticated principal so Spring Security's AuthorizationFilter accepts the request.
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("api-key-user", null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isValidKey(String apiKey) {
        if (apiKey == null) return false;
        // Constant-time comparison prevents timing-attack extraction of the key.
        return MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                expectedApiKey.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Missing or invalid API key");
        pd.setTitle("Unauthorized");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), pd);
    }
}

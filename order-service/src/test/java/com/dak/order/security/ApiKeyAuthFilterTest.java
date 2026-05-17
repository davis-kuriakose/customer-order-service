package com.dak.order.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter("test-api-key", objectMapper);

    @Test
    void validApiKey_allowsRequestThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "test-api-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("filter chain was invoked").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingApiKey_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("filter chain must not be invoked").isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/problem+json");
    }

    @Test
    void invalidApiKey_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("filter chain must not be invoked").isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void actuatorPath_bypassesKeyCheck() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/actuator/health");
        // No API key — but shouldNotFilter returns true so filter is skipped entirely
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("filter chain was invoked for actuator").isNotNull();
    }

    @Test
    void swaggerPath_bypassesKeyCheck() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("filter chain was invoked for swagger").isNotNull();
    }
}

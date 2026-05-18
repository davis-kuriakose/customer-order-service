package com.dak.catalog.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final boolean securityEnabled;

    public SecurityConfig(
            @Value("${app.api-key}") String apiKey,
            @Value("${app.security.enabled:false}") boolean securityEnabled,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.securityEnabled = securityEnabled;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                    .contentTypeOptions(Customizer.withDefaults())
                    .frameOptions(fo -> fo.deny())
                    .cacheControl(Customizer.withDefaults()));

        if (securityEnabled) {
            ApiKeyAuthFilter filter = new ApiKeyAuthFilter(apiKey, objectMapper);
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated());
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }
}

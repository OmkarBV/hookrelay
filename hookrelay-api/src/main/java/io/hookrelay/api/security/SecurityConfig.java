package io.hookrelay.api.security;

import io.hookrelay.api.security.apikey.ApiKeyAuthenticationFilter;
import io.hookrelay.api.security.apikey.ApiKeyService;
import io.hookrelay.api.security.jwt.JwtAuthenticationFilter;
import io.hookrelay.api.security.jwt.JwtService;
import io.hookrelay.api.security.tenant.TenantScopingFilter;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two independent SecurityFilterChains, matching the two distinct auth paths:
 * ingestion (API key) and admin (JWT). Each chain is stateless and adds its
 * authentication filter followed immediately by {@link TenantScopingFilter},
 * so tenant isolation is wired identically regardless of which credential
 * type authenticated the request.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain ingestionSecurityFilterChain(
            HttpSecurity http, ApiKeyService apiKeyService, EntityManagerFactory entityManagerFactory)
            throws Exception {
        http.securityMatcher("/api/v1/events/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantScopingFilter(entityManagerFactory), ApiKeyAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http, JwtService jwtService, EntityManagerFactory entityManagerFactory) throws Exception {
        http.securityMatcher("/api/v1/admin/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/auth/login", "/api/v1/admin/bootstrap").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantScopingFilter(entityManagerFactory), JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}

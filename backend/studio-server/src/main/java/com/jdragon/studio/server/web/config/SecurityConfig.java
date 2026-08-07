package com.jdragon.studio.server.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.server.web.filter.JwtAuthenticationFilter;
import com.jdragon.studio.server.web.filter.StudioCookieCsrfFilter;
import com.jdragon.studio.server.web.filter.StudioRequestContextFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   StudioCookieCsrfFilter studioCookieCsrfFilter,
                                                   StudioRequestContextFilter studioRequestContextFilter,
                                                   ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        objectMapper, Result.error(StudioErrorCode.UNAUTHORIZED, "Authentication required")))
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                                        objectMapper, Result.error(StudioErrorCode.FORBIDDEN, "Permission denied"))))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC)
                        .permitAll()
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/api/v1/runtime-clusters/internal/**",
                                "/openapi/data-services/**",
                                "/openapi/data-ingestion-services/**",
                                "/openapi/protocol-conversions/**",
                                "/openapi/ws/**",
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/swagger-resources/**",
                                "/webjars/**",
                                "/doc.html",
                                "/favicon.ico")
                        .permitAll()
                        .anyRequest()
                        .authenticated());
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(studioCookieCsrfFilter, JwtAuthenticationFilter.class);
        http.addFilterAfter(studioRequestContextFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    private void writeJson(HttpServletResponse response,
                           int status,
                           ObjectMapper objectMapper,
                           Result<Void> body) {
        try {
            if (response.isCommitted()) {
                return;
            }
            response.setStatus(status);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write security response", ex);
        }
    }
}


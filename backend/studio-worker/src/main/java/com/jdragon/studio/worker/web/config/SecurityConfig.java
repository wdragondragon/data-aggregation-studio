package com.jdragon.studio.worker.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.worker.web.filter.InternalApiTokenFilter;
import com.jdragon.studio.worker.web.filter.FlinkRuntimeCapabilityFilter;
import com.jdragon.studio.worker.web.filter.RuntimeInvocationConcurrencyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   InternalApiTokenFilter internalApiTokenFilter,
                                                   FlinkRuntimeCapabilityFilter flinkRuntimeCapabilityFilter,
                                                   RuntimeInvocationConcurrencyFilter concurrencyFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/actuator/info/**", "/internal/**",
                                "/api/flink/runtime/resolve", "/api/flink/runtime/audit")
                        .permitAll()
                        .anyRequest()
                        .denyAll());
        http.addFilterBefore(internalApiTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(flinkRuntimeCapabilityFilter, InternalApiTokenFilter.class);
        http.addFilterAfter(concurrencyFilter, InternalApiTokenFilter.class);
        return http.build();
    }

    @Bean
    public InternalApiTokenFilter internalApiTokenFilter(ObjectMapper objectMapper,
                                                         StudioPlatformProperties properties) {
        return new InternalApiTokenFilter(objectMapper, properties);
    }

    @Bean
    public FilterRegistrationBean<InternalApiTokenFilter> internalApiTokenFilterRegistration(
            InternalApiTokenFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FlinkRuntimeCapabilityFilter flinkRuntimeCapabilityFilter(ObjectMapper objectMapper) {
        return new FlinkRuntimeCapabilityFilter(objectMapper);
    }

    @Bean
    public FilterRegistrationBean<FlinkRuntimeCapabilityFilter> flinkRuntimeCapabilityFilterRegistration(
            FlinkRuntimeCapabilityFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public RuntimeInvocationConcurrencyFilter runtimeInvocationConcurrencyFilter(
            ObjectMapper objectMapper,
            StudioPlatformProperties properties) {
        return new RuntimeInvocationConcurrencyFilter(objectMapper, properties);
    }

    @Bean
    public FilterRegistrationBean<RuntimeInvocationConcurrencyFilter> runtimeInvocationConcurrencyFilterRegistration(
            RuntimeInvocationConcurrencyFilter filter) {
        return disabledRegistration(filter);
    }

    private <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabledRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<T>();
        registration.setFilter(filter);
        registration.setEnabled(false);
        return registration;
    }
}

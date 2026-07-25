package com.jdragon.studio.desktopruntime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.worker.web.filter.FlinkRuntimeCapabilityFilter;
import com.jdragon.studio.worker.web.filter.InternalApiTokenFilter;
import com.jdragon.studio.worker.web.filter.RuntimeInvocationConcurrencyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Adds the Worker-only internal surface without weakening the Server control-plane chain. */
@Configuration(proxyBeanMethods = false)
public class DesktopRuntimeWorkerSecurityConfig {

    @Bean
    public InternalApiTokenFilter desktopInternalApiTokenFilter(ObjectMapper objectMapper,
                                                                StudioPlatformProperties properties) {
        return new InternalApiTokenFilter(objectMapper, properties);
    }

    @Bean
    public FilterRegistrationBean<InternalApiTokenFilter> desktopInternalApiTokenFilterRegistration(
            InternalApiTokenFilter filter) {
        FilterRegistrationBean<InternalApiTokenFilter> registration = new FilterRegistrationBean<InternalApiTokenFilter>();
        registration.setFilter(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public RuntimeInvocationConcurrencyFilter desktopRuntimeInvocationConcurrencyFilter(
            ObjectMapper objectMapper,
            StudioPlatformProperties properties) {
        return new RuntimeInvocationConcurrencyFilter(objectMapper, properties);
    }

    @Bean
    public FilterRegistrationBean<RuntimeInvocationConcurrencyFilter> desktopRuntimeInvocationConcurrencyFilterRegistration(
            RuntimeInvocationConcurrencyFilter filter) {
        FilterRegistrationBean<RuntimeInvocationConcurrencyFilter> registration =
                new FilterRegistrationBean<RuntimeInvocationConcurrencyFilter>();
        registration.setFilter(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FlinkRuntimeCapabilityFilter desktopFlinkRuntimeCapabilityFilter(ObjectMapper objectMapper) {
        return new FlinkRuntimeCapabilityFilter(objectMapper);
    }

    @Bean
    public FilterRegistrationBean<FlinkRuntimeCapabilityFilter> desktopFlinkRuntimeCapabilityFilterRegistration(
            FlinkRuntimeCapabilityFilter filter) {
        FilterRegistrationBean<FlinkRuntimeCapabilityFilter> registration =
                new FilterRegistrationBean<FlinkRuntimeCapabilityFilter>();
        registration.setFilter(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain desktopWorkerInternalSecurityFilterChain(
            HttpSecurity http,
            InternalApiTokenFilter internalApiTokenFilter,
            FlinkRuntimeCapabilityFilter flinkRuntimeCapabilityFilter,
            RuntimeInvocationConcurrencyFilter concurrencyFilter) throws Exception {
        http.securityMatcher("/internal/**", "/api/flink/runtime/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        http.addFilterBefore(internalApiTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(flinkRuntimeCapabilityFilter, InternalApiTokenFilter.class);
        http.addFilterAfter(concurrencyFilter, InternalApiTokenFilter.class);
        return http.build();
    }
}

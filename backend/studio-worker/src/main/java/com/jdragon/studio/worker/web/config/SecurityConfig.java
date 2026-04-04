package com.jdragon.studio.worker.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.worker.web.filter.InternalApiTokenFilter;
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
                                                   InternalApiTokenFilter internalApiTokenFilter) throws Exception {
        http.csrf().disable()
                .cors(Customizer.withDefaults())
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests()
                .antMatchers("/actuator/health/**", "/actuator/info/**", "/internal/**")
                .permitAll()
                .anyRequest()
                .denyAll();
        http.addFilterBefore(internalApiTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public InternalApiTokenFilter internalApiTokenFilter(ObjectMapper objectMapper,
                                                         StudioPlatformProperties properties) {
        return new InternalApiTokenFilter(objectMapper, properties);
    }
}

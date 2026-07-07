package com.jdragon.studio.flink.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class FlinkWebMvcConfig implements WebMvcConfigurer {

    private final long asyncRequestTimeoutMillis;

    public FlinkWebMvcConfig(@Value("${studio.web.async-request-timeout:300000}") long asyncRequestTimeoutMillis) {
        this.asyncRequestTimeoutMillis = asyncRequestTimeoutMillis;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(asyncRequestTimeoutMillis);
    }
}

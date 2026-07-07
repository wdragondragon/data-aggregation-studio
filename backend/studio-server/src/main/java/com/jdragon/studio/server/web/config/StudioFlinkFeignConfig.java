package com.jdragon.studio.server.web.config;

import com.jdragon.studio.commons.constant.StudioConstants;
import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class StudioFlinkFeignConfig {

    private static final String AUTHORIZATION_HEADER = "Authorization";

    @Bean
    public RequestInterceptor studioFlinkRequestHeaderInterceptor() {
        return template -> {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes)) {
                return;
            }
            HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
            forwardHeader(template, request, AUTHORIZATION_HEADER);
            forwardHeader(template, request, StudioConstants.REQUEST_TENANT_HEADER);
            forwardHeader(template, request, StudioConstants.REQUEST_PROJECT_HEADER);
        };
    }

    private void forwardHeader(feign.RequestTemplate template, HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        if (StringUtils.hasText(value)) {
            template.header(headerName, value);
        }
    }
}

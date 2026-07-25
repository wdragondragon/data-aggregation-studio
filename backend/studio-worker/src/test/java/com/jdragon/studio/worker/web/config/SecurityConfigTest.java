package com.jdragon.studio.worker.web.config;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.worker.web.filter.FlinkRuntimeCapabilityFilter;
import com.jdragon.studio.worker.web.filter.InternalApiTokenFilter;
import com.jdragon.studio.worker.web.filter.RuntimeInvocationConcurrencyFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigTest.TestEndpoint.class,
        properties = {
                "studio.internal-api-token=worker-security-test-token",
                "studio.runtime-invocation-max-concurrency=2"
        })
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldAuthenticateBeforeConcurrencyAndAvoidDuplicateServletRegistration() throws Exception {
        List<Filter> internalFilters = filterChainProxy.getFilters("/internal/runtime/test");
        List<Filter> flinkFilters = filterChainProxy.getFilters("/api/flink/runtime/resolve");

        assertThat(indexOf(internalFilters, InternalApiTokenFilter.class))
                .isLessThan(indexOf(internalFilters, RuntimeInvocationConcurrencyFilter.class));
        assertThat(indexOf(flinkFilters, FlinkRuntimeCapabilityFilter.class))
                .isLessThan(indexOf(flinkFilters, RuntimeInvocationConcurrencyFilter.class));

        Map<String, FilterRegistrationBean> registrations =
                applicationContext.getBeansOfType(FilterRegistrationBean.class);
        assertThat(registrations.values())
                .filteredOn(registration -> registration.getFilter() instanceof InternalApiTokenFilter
                        || registration.getFilter() instanceof FlinkRuntimeCapabilityFilter
                        || registration.getFilter() instanceof RuntimeInvocationConcurrencyFilter)
                .hasSize(3)
                .allSatisfy(registration -> assertThat(registration.isEnabled()).isFalse());

        mockMvc.perform(get("/internal/runtime/test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER,
                        RuntimeInternalHeaders.INTERNAL_AUTHENTICATION));
        mockMvc.perform(get("/internal/runtime/test")
                        .header("X-Studio-Internal-Token", "worker-security-test-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                        RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED));
        mockMvc.perform(get("/api/flink/runtime/resolve"))
                .andExpect(status().isUnauthorized());
    }

    private int indexOf(List<Filter> filters, Class<? extends Filter> type) {
        for (int index = 0; index < filters.size(); index++) {
            if (type.isInstance(filters.get(index))) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    @RestController
    static class TestEndpoint {
        @GetMapping("/internal/runtime/test")
        String internal() {
            return "ok";
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(StudioPlatformProperties.class)
    @Import({SecurityConfig.class, TestEndpoint.class})
    static class TestApplication {
    }
}

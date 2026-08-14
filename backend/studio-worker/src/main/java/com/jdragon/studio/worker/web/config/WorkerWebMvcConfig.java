package com.jdragon.studio.worker.web.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WorkerWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DownloadAsyncTimeoutInterceptor())
                .addPathPatterns(
                        "/internal/runtime/datasource/file-download",
                        "/internal/runtime/datasource/file-archive");
    }

    private static final class DownloadAsyncTimeoutInterceptor implements AsyncHandlerInterceptor {
        @Override
        public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                                   HttpServletResponse response,
                                                   Object handler) {
            if (request.isAsyncStarted()) {
                request.getAsyncContext().setTimeout(0L);
            }
        }
    }
}

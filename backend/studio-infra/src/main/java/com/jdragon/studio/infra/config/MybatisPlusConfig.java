package com.jdragon.studio.infra.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                StudioRequestContext requestContext = StudioRequestContextHolder.getContext();
                String tenantId = requestContext == null || requestContext.getTenantId() == null
                        ? StudioConstants.DEFAULT_TENANT_ID
                        : requestContext.getTenantId();
                strictInsertFill(metaObject, "tenantId", String.class, tenantId);
                if (metaObject.hasSetter("projectId") && requestContext != null && requestContext.getProjectId() != null) {
                    strictInsertFill(metaObject, "projectId", Long.class, requestContext.getProjectId());
                }
                strictInsertFill(metaObject, "deleted", Integer.class, 0);
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}


package com.jdragon.studio.desktopruntime.bootstrap;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.jdragon.studio.infra",
        "com.jdragon.studio.server.web",
        "com.jdragon.studio.worker.runtime",
        "com.jdragon.studio.desktopruntime"
})
@MapperScan("com.jdragon.studio.infra.mapper")
@EnableConfigurationProperties(StudioPlatformProperties.class)
@EnableScheduling
public class StudioDesktopRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudioDesktopRuntimeApplication.class, args);
    }
}

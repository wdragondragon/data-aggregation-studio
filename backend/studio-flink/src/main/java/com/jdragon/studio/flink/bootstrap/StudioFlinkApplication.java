package com.jdragon.studio.flink.bootstrap;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {
        "com.jdragon.studio.flink",
        "com.jdragon.studio.infra"
})
@MapperScan("com.jdragon.studio.infra.mapper")
@EnableConfigurationProperties(StudioPlatformProperties.class)
@EnableDiscoveryClient
public class StudioFlinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudioFlinkApplication.class, args);
    }
}

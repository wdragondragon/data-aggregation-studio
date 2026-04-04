package com.jdragon.studio.server.bootstrap;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.StudioSchemaUpgradeService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication(scanBasePackages = {
        "com.jdragon.studio.infra"
})
@MapperScan("com.jdragon.studio.infra.mapper")
@EnableConfigurationProperties(StudioPlatformProperties.class)
public class StudioSchemaUpgradeApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(StudioSchemaUpgradeApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(args);
        int exitCode = 0;
        try {
            context.getBean(StudioSchemaUpgradeService.class).upgrade();
            System.out.println("Studio schema upgrade completed.");
        } catch (Exception e) {
            exitCode = 1;
            e.printStackTrace(System.err);
        } finally {
            final int finalExitCode = exitCode;
            org.springframework.boot.SpringApplication.exit(context, () -> finalExitCode);
            System.exit(finalExitCode);
        }
    }
}

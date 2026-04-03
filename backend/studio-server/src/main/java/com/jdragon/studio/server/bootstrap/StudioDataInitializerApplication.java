package com.jdragon.studio.server.bootstrap;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.StudioInitializationService;
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
public class StudioDataInitializerApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(StudioDataInitializerApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(args);
        int exitCode = 0;
        try {
            boolean reset = Boolean.parseBoolean(context.getEnvironment().getProperty("studio.init.reset", "false"));
            context.getBean(StudioInitializationService.class).initialize(reset);
            System.out.println("Studio data initialization completed. reset=" + reset);
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

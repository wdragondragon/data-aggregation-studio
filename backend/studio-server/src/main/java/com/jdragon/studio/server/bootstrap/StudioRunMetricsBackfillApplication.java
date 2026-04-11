package com.jdragon.studio.server.bootstrap;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.RunMetricBackfillService;
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
public class StudioRunMetricsBackfillApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(StudioRunMetricsBackfillApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(args);
        int exitCode = 0;
        try {
            RunMetricBackfillService.RunMetricBackfillResult result = context.getBean(RunMetricBackfillService.class)
                    .backfillSuccessMetrics();
            System.out.println("Run metric backfill completed. scanned=" + result.getScannedCount() + ", updated=" + result.getUpdatedCount());
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

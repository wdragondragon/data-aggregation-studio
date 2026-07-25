package com.jdragon.studio.server.bootstrap;

import com.jdragon.studio.infra.service.LegacyRuntimeClusterBackfillService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.Arrays;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(LegacyRuntimeClusterBackfillService.class)
public class StudioRuntimeClusterBackfillApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                StudioRuntimeClusterBackfillApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(args);
        int exitCode = 0;
        try {
            boolean dryRun = Arrays.stream(args).anyMatch("--dry-run"::equalsIgnoreCase);
            LegacyRuntimeClusterBackfillService.BackfillReport report = context
                    .getBean(LegacyRuntimeClusterBackfillService.class)
                    .backfill(dryRun);
            System.out.println(report);
        } catch (Exception ex) {
            exitCode = 1;
            ex.printStackTrace(System.err);
        } finally {
            int finalExitCode = exitCode;
            org.springframework.boot.SpringApplication.exit(context, () -> finalExitCode);
            System.exit(finalExitCode);
        }
    }
}

package com.jdragon.studio.server.bootstrap;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.schema.StudioSchemaSnapshotService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootApplication(scanBasePackages = {
        "com.jdragon.studio.infra"
})
@MapperScan("com.jdragon.studio.infra.mapper")
@EnableConfigurationProperties(StudioPlatformProperties.class)
public class StudioSchemaDescribeApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(StudioSchemaDescribeApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(args);
        int exitCode = 0;
        try {
            ApplicationArguments applicationArguments = context.getBean(ApplicationArguments.class);
            String outputDir = optionValue(applicationArguments, "studio.schema.snapshot.output-dir");
            if (outputDir == null || outputDir.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing required argument: --studio.schema.snapshot.output-dir");
            }
            Path snapshotFile = context.getBean(StudioSchemaSnapshotService.class).writeSnapshot(Paths.get(outputDir));
            System.out.println("Studio schema snapshot written to " + snapshotFile.toAbsolutePath());
        } catch (Exception e) {
            exitCode = 1;
            e.printStackTrace(System.err);
        } finally {
            final int finalExitCode = exitCode;
            org.springframework.boot.SpringApplication.exit(context, () -> finalExitCode);
            System.exit(finalExitCode);
        }
    }

    private static String optionValue(ApplicationArguments args, String optionName) {
        if (!args.containsOption(optionName)) {
            return null;
        }
        List<String> values = args.getOptionValues(optionName);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}

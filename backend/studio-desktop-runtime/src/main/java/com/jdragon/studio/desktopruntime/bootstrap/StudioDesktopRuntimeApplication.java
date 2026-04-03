package com.jdragon.studio.desktopruntime.bootstrap;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        prepareDesktopDatabasePath();
        SpringApplication.run(StudioDesktopRuntimeApplication.class, args);
    }

    static void prepareDesktopDatabasePath() {
        if (hasText(System.getProperty("spring.datasource.url")) || hasText(System.getenv("SPRING_DATASOURCE_URL"))) {
            return;
        }
        String configuredPath = firstNonBlank(System.getProperty("studio.desktop.db.path"), System.getenv("STUDIO_DESKTOP_DB_PATH"));
        Path databasePath = hasText(configuredPath)
                ? Paths.get(configuredPath)
                : resolveDefaultDatabasePath();
        databasePath = databasePath.toAbsolutePath().normalize();
        Path parent = databasePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to create desktop runtime directory: " + parent, exception);
            }
        }
        String normalizedPath = databasePath.toString().replace('\\', '/');
        System.setProperty("studio.desktop.db.path", normalizedPath);
        System.setProperty("spring.datasource.url", "jdbc:sqlite:" + normalizedPath);
    }

    private static Path resolveDefaultDatabasePath() {
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path studioRoot = findStudioRoot(userDir);
        if (studioRoot != null) {
            return studioRoot.resolve("runtime").resolve("studio-desktop.db");
        }
        return userDir.resolve("runtime").resolve("studio-desktop.db");
    }

    private static Path findStudioRoot(Path start) {
        Path current = start;
        for (int i = 0; current != null && i < 8; i++) {
            if (isStudioRoot(current)) {
                return current;
            }
            Path nestedStudio = current.resolve("data-aggregation-studio");
            if (isStudioRoot(nestedStudio)) {
                return nestedStudio;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isStudioRoot(Path candidate) {
        return candidate != null
                && Files.isDirectory(candidate.resolve("backend"))
                && Files.isDirectory(candidate.resolve("frontend"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }
}

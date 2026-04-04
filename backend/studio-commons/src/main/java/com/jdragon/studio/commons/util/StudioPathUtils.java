package com.jdragon.studio.commons.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class StudioPathUtils {

    private StudioPathUtils() {
    }

    public static Path resolveStudioPath(String configuredPath) {
        Path candidate = Paths.get(configuredPath == null || configuredPath.trim().isEmpty()
                ? "./runtime"
                : configuredPath.trim());
        if (candidate.isAbsolute()) {
            return candidate.normalize();
        }
        Path studioRoot = findStudioRoot(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());
        return studioRoot.resolve(candidate).normalize();
    }

    private static Path findStudioRoot(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            if (current.getFileName() != null && "data-aggregation-studio".equalsIgnoreCase(current.getFileName().toString())) {
                return current;
            }
            Path nested = current.resolve("data-aggregation-studio");
            if (Files.isDirectory(nested)) {
                return nested;
            }
        }
        return start;
    }
}

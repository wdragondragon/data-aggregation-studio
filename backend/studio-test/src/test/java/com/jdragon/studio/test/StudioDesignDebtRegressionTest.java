package com.jdragon.studio.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StudioDesignDebtRegressionTest {

    private static final int MAX_BACKEND_CATCH_IGNORED = 23;
    private static final int MAX_BACKEND_RETURN_NULL = 218;
    private static final int MAX_LEGACY_TABLE_WRAPPER_REFERENCES = 0;
    private static final int MAX_LARGE_WEB_VUE_FILES = 13;
    private static final int BACKEND_LARGE_FILE_LINE_THRESHOLD = 800;
    private static final int WEB_LARGE_FILE_LINE_THRESHOLD = 1000;
    private static final Set<String> REVIEWED_LARGE_BACKEND_JAVA_FILES = Set.of(
            "backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java");

    @Test
    void backendIgnoredCatchesShouldNotIncrease() throws Exception {
        int count = countInBackendMain(Pattern.compile("catch\\s*\\([^)]*\\signored\\s*\\)"));

        assertThat(count)
                .as("backend main catch ignored occurrences")
                .isLessThanOrEqualTo(MAX_BACKEND_CATCH_IGNORED);
    }

    @Test
    void backendReturnNullShouldNotIncrease() throws Exception {
        int count = countInBackendMain(Pattern.compile("\\breturn\\s+null\\s*;"));

        assertThat(count)
                .as("backend main return null occurrences")
                .isLessThanOrEqualTo(MAX_BACKEND_RETURN_NULL);
    }

    @Test
    void frontendLegacyTableWrappersShouldNotIncrease() throws Exception {
        int count = countInFrontendVue(Pattern.compile(
                "table-scroll-shell|task-table-wrap|workflow-table-wrap|run-table-wrap|system-table-wrap|quality-table-wrap"));

        assertThat(count)
                .as("legacy table wrapper references")
                .isLessThanOrEqualTo(MAX_LEGACY_TABLE_WRAPPER_REFERENCES);
    }

    @Test
    void giantSourceFilesShouldNotIncrease() throws Exception {
        Path root = resolveProjectRoot();

        List<String> backendLargeFiles = listLargeFiles(root, root.resolve("backend/studio-infra/src/main/java"),
                ".java", BACKEND_LARGE_FILE_LINE_THRESHOLD);
        long frontendLargeFiles = countLargeFiles(root.resolve("frontend/apps/web/src"), ".vue", WEB_LARGE_FILE_LINE_THRESHOLD);

        assertThat(backendLargeFiles)
                .as("backend Java files over " + BACKEND_LARGE_FILE_LINE_THRESHOLD + " lines")
                .containsExactlyInAnyOrderElementsOf(REVIEWED_LARGE_BACKEND_JAVA_FILES);
        assertThat(frontendLargeFiles)
                .as("web Vue files over " + WEB_LARGE_FILE_LINE_THRESHOLD + " lines")
                .isLessThanOrEqualTo(MAX_LARGE_WEB_VUE_FILES);
    }

    private int countInBackendMain(Pattern pattern) throws IOException {
        Path root = resolveProjectRoot();
        return countInFiles(root.resolve("backend"), ".java", pattern, true);
    }

    private int countInFrontendVue(Pattern pattern) throws IOException {
        Path root = resolveProjectRoot();
        int appCount = countInFiles(root.resolve("frontend/apps/web/src"), ".vue", pattern, false);
        int uiCount = countInFiles(root.resolve("frontend/packages/ui/src"), ".vue", pattern, false);
        return appCount + uiCount;
    }

    private int countInFiles(Path start, String suffix, Pattern pattern, boolean backendMainOnly) throws IOException {
        if (!Files.exists(start)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> paths = Files.walk(start)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path) || !path.toString().endsWith(suffix)) {
                    continue;
                }
                if (backendMainOnly && isExcludedBackendPath(path)) {
                    continue;
                }
                count += countMatches(Files.readString(path, StandardCharsets.UTF_8), pattern);
            }
        }
        return count;
    }

    private long countLargeFiles(Path start, String suffix, int threshold) throws IOException {
        if (!Files.exists(start)) {
            return 0;
        }
        long count = 0;
        try (Stream<Path> paths = Files.walk(start)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path) || !path.toString().endsWith(suffix)) {
                    continue;
                }
                if (suffix.endsWith(".java") && isExcludedBackendPath(path)) {
                    continue;
                }
                if (Files.readAllLines(path, StandardCharsets.UTF_8).size() > threshold) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<String> listLargeFiles(Path root, Path start, String suffix, int threshold) throws IOException {
        List<String> result = new ArrayList<>();
        if (!Files.exists(start)) {
            return result;
        }
        try (Stream<Path> paths = Files.walk(start)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path) || !path.toString().endsWith(suffix)) {
                    continue;
                }
                if (suffix.endsWith(".java") && isExcludedBackendPath(path)) {
                    continue;
                }
                if (Files.readAllLines(path, StandardCharsets.UTF_8).size() > threshold) {
                    result.add(root.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        return result;
    }

    private int countMatches(String content, Pattern pattern) {
        int count = 0;
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private boolean isExcludedBackendPath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/src/test/") || normalized.contains("/target/");
    }

    private Path resolveProjectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("backend")) && Files.exists(current.resolve("frontend"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate Studio project root");
    }
}

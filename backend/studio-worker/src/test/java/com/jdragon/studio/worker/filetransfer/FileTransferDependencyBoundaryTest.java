package com.jdragon.studio.worker.filetransfer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileTransferDependencyBoundaryTest {
    @Test
    void workerEmbedsCoreButNeverDependsOnTheStandaloneTransferPlugin() throws IOException {
        Path workerPom = modulePath("studio-worker").resolve("pom.xml");
        String pom = Files.readString(workerPom);
        assertThat(pom).contains("<artifactId>file-transfer-contract</artifactId>")
                .contains("<artifactId>file-transfer-core</artifactId>")
                .doesNotContain("<artifactId>file-transfer-plugin</artifactId>");

        try (Stream<Path> sourceFiles = Files.walk(modulePath("studio-worker")
                .resolve("src/main"))) {
            String source = sourceFiles.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::read)
                    .reduce("", String::concat);
            assertThat(source).doesNotContain("TransferConfigurationMapper")
                    .doesNotContain("aggregation.transfer.plugin");
        }
    }

    @Test
    void serverDoesNotDependOnEngineOrStandalonePlugin() throws IOException {
        String pom = Files.readString(modulePath("studio-server").resolve("pom.xml"));
        assertThat(pom).doesNotContain("<artifactId>file-transfer-core</artifactId>")
                .doesNotContain("<artifactId>file-transfer-plugin</artifactId>");
    }

    private Path modulePath(String module) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (module.equals(current.getFileName().toString())) {
            return current;
        }
        Path backend = current.getFileName().toString().startsWith("studio-")
                ? current.getParent() : current;
        return backend.resolve(module);
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect " + path, exception);
        }
    }
}

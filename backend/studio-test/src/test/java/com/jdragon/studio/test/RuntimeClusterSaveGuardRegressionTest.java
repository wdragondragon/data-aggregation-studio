package com.jdragon.studio.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeClusterSaveGuardRegressionTest {

    @Test
    void allRuntimePlacedResourcesShouldUseTheSaveTimeGuard() throws Exception {
        Map<String, String> expectedCallByFile = new LinkedHashMap<String, String>();
        expectedCallByFile.put("CollectionTaskService.java", "validateDatasourceSelectionForResourceSave(");
        expectedCallByFile.put("QualityTaskService.java", "validateDatasourceSelectionForResourceSave(");
        expectedCallByFile.put("DataDevelopmentService.java", "validateDatasourceSelectionForResourceSave(");
        expectedCallByFile.put("DataServiceService.java", "validateDatasourceSelectionForResourceSave(");
        expectedCallByFile.put("DataIngestionService.java", "validateDatasourceSelectionForResourceSave(");
        expectedCallByFile.put("ProtocolConversionService.java", "validateDatasourceSelectionForResourceSave(");
        expectedCallByFile.put("WorkflowService.java", "resolveForResourceSave(");

        Path serviceRoot = resolveProjectRoot().resolve(
                "backend/studio-infra/src/main/java/com/jdragon/studio/infra/service");
        for (Map.Entry<String, String> entry : expectedCallByFile.entrySet()) {
            String source = Files.readString(serviceRoot.resolve(entry.getKey()), StandardCharsets.UTF_8);
            assertThat(source)
                    .as(entry.getKey() + " save-time runtime cluster guard")
                    .contains(entry.getValue());
        }
    }

    @Test
    void datasourceSaveShouldPassExistingDatasourceIdentityToBindingGuard() throws Exception {
        Path sourcePath = resolveProjectRoot().resolve(
                "backend/studio-infra/src/main/java/com/jdragon/studio/infra/service/DataSourceService.java");
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

        assertThat(source).contains("currentProjectId, entity.getId(), request.getApplicableClusterIds()");
    }

    private Path resolveProjectRoot() throws IOException {
        Path current = Paths.get("").toAbsolutePath().normalize();
        for (int i = 0; i < 8 && current != null; i++) {
            if (Files.exists(current.resolve("backend/studio-infra/pom.xml"))
                    && Files.exists(current.resolve("frontend/apps/web/package.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("Unable to locate Studio project root");
    }
}

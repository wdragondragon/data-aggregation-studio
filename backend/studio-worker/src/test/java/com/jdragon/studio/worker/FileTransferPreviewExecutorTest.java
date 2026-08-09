package com.jdragon.studio.worker;

import com.jdragon.aggregation.datasource.file.transfer.TransferFileEntry;
import com.jdragon.aggregation.datasource.file.transfer.TransferFilePage;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferSelectionPreviewView;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import com.jdragon.studio.worker.filetransfer.FileTransferPreviewExecutor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileTransferPreviewExecutorTest {

    @Test
    void appliesDynamicSelectionFiltersAndTargetMappingOnWorker() throws Exception {
        AggregationSourceCapabilityProvider provider = mock(AggregationSourceCapabilityProvider.class);
        TransferFileSystem fileSystem = mock(TransferFileSystem.class);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(101L);
        datasource.setTypeCode("local");
        when(provider.openTransferFileSystem(datasource)).thenReturn(fileSystem);
        when(fileSystem.stat("/in/20260807")).thenReturn(
                new TransferFileEntry("/in/20260807", "20260807", true, 0L, 1L, null));
        when(fileSystem.listPage("/in/20260807", null, 1000)).thenReturn(new TransferFilePage(List.of(
                new TransferFileEntry("/in/20260807/a.csv", "a.csv", false, 10L, 2L, "a"),
                new TransferFileEntry("/in/20260807/b.txt", "b.txt", false, 20L, 3L, "b"),
                new TransferFileEntry("/in/20260807/c.csv", "c.csv", false, 30L, 4L, "c")),
                null, false));
        Map<String, Object> selection = new LinkedHashMap<String, Object>();
        selection.put("rootPath", "/in/${param:batch}");
        selection.put("recursive", Boolean.TRUE);
        selection.put("includeGlobs", List.of("*.csv"));
        selection.put("minSize", 15L);
        Map<String, Object> mapping = new LinkedHashMap<String, Object>();
        mapping.put("targetRootPath", "/out/${param:batch}");
        mapping.put("preserveRelativePath", Boolean.TRUE);
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("selection", selection);
        spec.put("mapping", mapping);
        spec.put("timeZone", "Asia/Shanghai");

        FileTransferSelectionPreviewView result = new FileTransferPreviewExecutor(provider)
                .preview(datasource, spec, Map.of("batch", "20260807"), 10);

        assertThat(result.getTotalFiles()).isEqualTo(1L);
        assertThat(result.getTotalBytes()).isEqualTo(30L);
        assertThat(result.getHasMore()).isFalse();
        assertThat(result.getResolvedSelection()).containsEntry("rootPath", "/in/20260807");
        assertThat(result.getResolvedMapping()).containsEntry("targetRootPath", "/out/20260807");
        assertThat(result.getSample()).singleElement().satisfies(item -> {
            assertThat(item.getSourcePath()).isEqualTo("/in/20260807/c.csv");
            assertThat(item.getTargetPath()).isEqualTo("/out/20260807/c.csv");
            assertThat(item.getSize()).isEqualTo(30L);
        });
    }
}

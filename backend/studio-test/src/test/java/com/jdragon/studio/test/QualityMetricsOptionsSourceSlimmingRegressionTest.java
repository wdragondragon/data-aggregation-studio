package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.QualityMetricOptionsView;
import com.jdragon.studio.dto.model.RunMetricFilterOptionView;
import com.jdragon.studio.infra.mapper.QualityMetricSnapshotMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.QualityIssueService;
import com.jdragon.studio.infra.service.QualityMetricsService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QualityMetricsOptionsSourceSlimmingRegressionTest {

    @Test
    void qualityMetricOptionsShouldOnlyLoadDatasourceFilterOptions() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        QualityMetricsService service = qualityMetricsService(dataSourceService, dataModelService);
        RunMetricFilterOptionView datasource = option(11L, "长期回归-客户订单数据源", "长期回归-客户订单数据源 / mysql8", "mysql8");
        when(dataSourceService.listMetricFilterOptions()).thenReturn(Collections.singletonList(datasource));

        QualityMetricOptionsView options = service.options();

        assertThat(options.getDatasources()).containsExactly(datasource);
        assertThat(options.getModels()).isEmpty();
        verify(dataSourceService).listMetricFilterOptions();
        verifyNoInteractions(dataModelService);
    }

    @Test
    void qualityMetricModelOptionsShouldUsePagedLightModelFilterOptions() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        QualityMetricsService service = qualityMetricsService(dataSourceService, dataModelService);
        RunMetricFilterOptionView model = option(21L, "客户经营画像表", "客户经营画像表 / lt_reg_customer_profile", null);
        when(dataModelService.listMetricFilterOptionPage(11L, "客户", 1, 50))
                .thenReturn(PageView.of(1, 50, 1L, Collections.singletonList(model)));

        PageView<RunMetricFilterOptionView> page = service.modelOptions(11L, "客户", 1, 50);

        assertThat(page.getItems()).containsExactly(model);
        assertThat(page.getTotal()).isEqualTo(1L);
        verify(dataModelService).listMetricFilterOptionPage(11L, "客户", 1, 50);
        verifyNoInteractions(dataSourceService);
    }

    private QualityMetricsService qualityMetricsService(DataSourceService dataSourceService,
                                                        DataModelService dataModelService) {
        return new QualityMetricsService(
                dataSourceService,
                dataModelService,
                mock(QualityTaskService.class),
                mock(QualityIssueService.class),
                mock(RunRecordMapper.class),
                mock(QualityMetricSnapshotMapper.class),
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class));
    }

    private RunMetricFilterOptionView option(Long id, String name, String label, String typeCode) {
        RunMetricFilterOptionView option = new RunMetricFilterOptionView();
        option.setId(id);
        option.setName(name);
        option.setLabel(label);
        option.setTypeCode(typeCode);
        return option;
    }
}

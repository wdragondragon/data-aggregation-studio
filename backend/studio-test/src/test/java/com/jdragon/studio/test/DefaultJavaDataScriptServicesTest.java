package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.infra.script.java.DefaultJavaDataScriptServices;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultJavaDataScriptServicesTest {

    @Test
    void listModelsShouldMaskHttpReaderCredentialsBeforeExposingThemToScripts() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DataModelDefinition rawModel = new DataModelDefinition();
        DataModelDefinition maskedModel = new DataModelDefinition();
        List<DataModelDefinition> rawModels = Collections.singletonList(rawModel);
        List<DataModelDefinition> maskedModels = Collections.singletonList(maskedModel);
        when(dataModelService.listByDatasource(11L)).thenReturn(rawModels);
        when(dataModelService.maskSensitiveReaderOptions(rawModels)).thenReturn(maskedModels);

        DefaultJavaDataScriptServices services = new DefaultJavaDataScriptServices(
                dataSourceService, dataModelService, sqlExecutor);

        assertThat(services.listModels(11L)).isSameAs(maskedModels);
        verify(dataModelService).maskSensitiveReaderOptions(rawModels);
    }
}

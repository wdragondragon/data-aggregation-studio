package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.QualityRuleView;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.QualityTaskPreviewView;
import com.jdragon.studio.dto.model.QualityTaskValidationView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import org.springframework.stereotype.Service;

import java.util.Collections;

/** Builds and remotely validates quality SQL without loading a local datasource plugin. */
@Service
public class QualityTaskExecutionPlanService {

    private final DataSourceService dataSourceService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;
    private final QualitySqlTemplateService qualitySqlTemplateService;
    private final ObjectMapper objectMapper;
    private final RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter;

    public QualityTaskExecutionPlanService(DataSourceService dataSourceService,
                                           DatasourceTypeCapabilityService datasourceTypeCapabilityService,
                                           QualitySqlTemplateService qualitySqlTemplateService,
                                           ObjectMapper objectMapper,
                                           RuntimeDatasourceProbeRouter runtimeDatasourceProbeRouter) {
        this.dataSourceService = dataSourceService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
        this.qualitySqlTemplateService = qualitySqlTemplateService;
        this.objectMapper = objectMapper;
        this.runtimeDatasourceProbeRouter = runtimeDatasourceProbeRouter;
    }

    public QualityTaskPreviewView preview(QualityTaskDefinitionView definition) {
        String resolvedSql = buildResolvedSql(definition);
        QualityTaskPreviewView preview = new QualityTaskPreviewView();
        preview.setResolvedSql(resolvedSql);
        if (resolvedSql.contains("${")) {
            preview.getWarnings().add("当前 SQL 仍包含未替换的占位符，请检查自定义参数赋值。");
        }
        return preview;
    }

    public QualityTaskValidationView validate(QualityTaskDefinitionView definition) {
        QualityTaskValidationView view = new QualityTaskValidationView();
        String resolvedSql = buildResolvedSql(definition);
        view.setResolvedSql(resolvedSql);
        if (resolvedSql.contains("${")) {
            view.getWarnings().add("当前 SQL 仍包含未替换的占位符，请检查自定义参数赋值。");
        }
        try {
            DataSourceDefinition datasource = requireSqlDatasource(definition.getDatasourceId());
            SqlExecutionResultView sqlResult = runtimeDatasourceProbeRouter.query(
                    datasource,
                    definition.getRuntimeClusterId(),
                    resolvedSql,
                    Collections.emptyList(),
                    Integer.valueOf(20));
            view.setValid(Boolean.TRUE);
            view.setMessage(sqlResult.getMessage());
            view.setColumns(sqlResult.getColumns());
            view.setRows(sqlResult.getRows());
            view.setSummary(sqlResult.getSummary());
            view.setOutputParams(qualitySqlTemplateService.resolveOutputParamsFromResult(
                    sqlResult.getColumns(), sqlResult.getRows()));
        } catch (Exception ex) {
            view.setValid(Boolean.FALSE);
            view.setMessage(ex.getMessage());
        }
        return view;
    }

    public String buildResolvedSql(QualityTaskDefinitionView definition) {
        QualityRuleView rule = resolveRule(definition);
        return qualitySqlTemplateService.resolveSql(
                rule.getLogicSql(),
                qualitySqlTemplateService.buildRuntimeBindings(
                        definition.getModelPhysicalLocator(),
                        definition.getColumnName(),
                        definition.getParameterBindings()),
                definition.getWhereClause());
    }

    private QualityRuleView resolveRule(QualityTaskDefinitionView definition) {
        if (definition == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality task definition is required");
        }
        if (definition.getRuleSnapshot() == null || definition.getRuleSnapshot().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Quality task rule snapshot is missing");
        }
        return objectMapper.convertValue(definition.getRuleSnapshot(), QualityRuleView.class);
    }

    private DataSourceDefinition requireSqlDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        if (!datasourceTypeCapabilityService.isSqlExecutable(datasource.getTypeCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Selected datasource does not support SQL quality checks");
        }
        return datasource;
    }
}

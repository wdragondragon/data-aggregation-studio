package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataModelService {

    private final DataModelMapper dataModelMapper;
    private final DataSourceService dataSourceService;
    private final AggregationSourceCapabilityProvider modelDiscoveryProvider;

    public DataModelService(DataModelMapper dataModelMapper,
                            DataSourceService dataSourceService,
                            AggregationSourceCapabilityProvider modelDiscoveryProvider) {
        this.dataModelMapper = dataModelMapper;
        this.dataSourceService = dataSourceService;
        this.modelDiscoveryProvider = modelDiscoveryProvider;
    }

    public List<DataModelDefinition> listByDatasource(Long datasourceId) {
        List<DataModelEntity> entities = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getDatasourceId, datasourceId)
                .orderByAsc(DataModelEntity::getName));
        List<DataModelDefinition> result = new ArrayList<DataModelDefinition>();
        for (DataModelEntity entity : entities) {
            result.add(toDefinition(entity));
        }
        return result;
    }

    @Transactional
    public List<DataModelDefinition> syncFromDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.get(datasourceId);
        List<DataModelDefinition> discovered = modelDiscoveryProvider.discoverModels(datasource).getModels();
        for (DataModelDefinition definition : discovered) {
            DataModelEntity existing = dataModelMapper.selectOne(new LambdaQueryWrapper<DataModelEntity>()
                    .eq(DataModelEntity::getDatasourceId, datasourceId)
                    .eq(DataModelEntity::getPhysicalLocator, definition.getPhysicalLocator())
                    .last("limit 1"));
            DataModelEntity entity = existing == null ? new DataModelEntity() : existing;
            entity.setDatasourceId(datasourceId);
            entity.setName(definition.getName());
            entity.setModelKind(definition.getModelKind() == null ? ModelKind.DATASET.name() : definition.getModelKind().name());
            entity.setPhysicalLocator(definition.getPhysicalLocator());
            entity.setTechnicalMetadata(definition.getTechnicalMetadata());
            entity.setBusinessMetadata(definition.getBusinessMetadata());
            if (entity.getId() == null) {
                dataModelMapper.insert(entity);
            } else {
                dataModelMapper.updateById(entity);
            }
        }
        return listByDatasource(datasourceId);
    }

    public List<Map<String, Object>> preview(Long modelId, int limit) {
        DataModelEntity model = dataModelMapper.selectById(modelId);
        if (model == null) {
            return new ArrayList<Map<String, Object>>();
        }
        DataSourceDefinition datasource = dataSourceService.get(model.getDatasourceId());
        return modelDiscoveryProvider.preview(datasource, toDefinition(model), limit);
    }

    private DataModelDefinition toDefinition(DataModelEntity entity) {
        DataModelDefinition definition = new DataModelDefinition();
        definition.setId(entity.getId());
        definition.setDatasourceId(entity.getDatasourceId());
        definition.setName(entity.getName());
        definition.setPhysicalLocator(entity.getPhysicalLocator());
        definition.setSchemaVersionId(entity.getSchemaVersionId());
        definition.setTechnicalMetadata(entity.getTechnicalMetadata() == null ? new LinkedHashMap<String, Object>() : entity.getTechnicalMetadata());
        definition.setBusinessMetadata(entity.getBusinessMetadata() == null ? new LinkedHashMap<String, Object>() : entity.getBusinessMetadata());
        if (entity.getModelKind() != null) {
            definition.setModelKind(ModelKind.valueOf(entity.getModelKind()));
        }
        return definition;
    }
}


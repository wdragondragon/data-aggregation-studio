package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataSourceService {

    private final DatasourceMapper datasourceMapper;
    private final EncryptionService encryptionService;
    private final AggregationSourceCapabilityProvider capabilityProvider;

    public DataSourceService(DatasourceMapper datasourceMapper,
                             EncryptionService encryptionService,
                             AggregationSourceCapabilityProvider capabilityProvider) {
        this.datasourceMapper = datasourceMapper;
        this.encryptionService = encryptionService;
        this.capabilityProvider = capabilityProvider;
    }

    public List<DataSourceDefinition> list() {
        List<DatasourceEntity> entities = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .orderByAsc(DatasourceEntity::getName));
        List<DataSourceDefinition> result = new ArrayList<DataSourceDefinition>();
        for (DatasourceEntity entity : entities) {
            result.add(toDefinition(entity, true));
        }
        return result;
    }

    public DataSourceDefinition get(Long id) {
        DatasourceEntity entity = datasourceMapper.selectById(id);
        return entity == null ? null : toDefinition(entity, true);
    }

    @Transactional
    public DataSourceDefinition save(DataSourceSaveRequest request) {
        DatasourceEntity entity = request.getId() == null ? new DatasourceEntity() : datasourceMapper.selectById(request.getId());
        if (entity == null) {
            entity = new DatasourceEntity();
        }
        entity.setName(request.getName());
        entity.setTypeCode(request.getTypeCode());
        entity.setSchemaVersionId(request.getSchemaVersionId());
        entity.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        entity.setExecutable(Boolean.TRUE.equals(request.getExecutable()) ? 1 : 0);
        entity.setTechnicalMetadata(encryptSensitive(request.getTechnicalMetadata()));
        entity.setBusinessMetadata(request.getBusinessMetadata());
        if (entity.getId() == null) {
            datasourceMapper.insert(entity);
        } else {
            datasourceMapper.updateById(entity);
        }
        return toDefinition(entity, true);
    }

    public ConnectionTestResult testConnection(Long id) {
        DataSourceDefinition definition = get(id);
        return capabilityProvider.testConnection(definition);
    }

    public ModelDiscoveryResult discoverModels(Long id) {
        DataSourceDefinition definition = get(id);
        return capabilityProvider.discoverModels(definition);
    }

    private DataSourceDefinition toDefinition(DatasourceEntity entity, boolean maskSensitive) {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(entity.getId());
        definition.setName(entity.getName());
        definition.setTypeCode(entity.getTypeCode());
        definition.setSchemaVersionId(entity.getSchemaVersionId());
        definition.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        definition.setExecutable(entity.getExecutable() != null && entity.getExecutable() == 1);
        definition.setTechnicalMetadata(maskSensitive ? maskSensitive(entity.getTechnicalMetadata()) : entity.getTechnicalMetadata());
        definition.setBusinessMetadata(entity.getBusinessMetadata());
        return definition;
    }

    private Map<String, Object> encryptSensitive(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isSensitive(entry.getKey()) && !String.valueOf(value).startsWith("ENC(")) {
                output.put(entry.getKey(), "ENC(" + encryptionService.encrypt(String.valueOf(value)) + ")");
            } else {
                output.put(entry.getKey(), value);
            }
        }
        return output;
    }

    private Map<String, Object> maskSensitive(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (input == null) {
            return output;
        }
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && isSensitive(entry.getKey()) && String.valueOf(value).startsWith("ENC(") && String.valueOf(value).endsWith(")")) {
                String cipher = String.valueOf(value).substring(4, String.valueOf(value).length() - 1);
                output.put(entry.getKey(), encryptionService.mask(encryptionService.decrypt(cipher)));
            } else {
                output.put(entry.getKey(), value);
            }
        }
        return output;
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("accesskey");
    }
}


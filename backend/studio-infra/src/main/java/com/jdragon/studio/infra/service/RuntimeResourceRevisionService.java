package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Builds secret-free revisions for queued resources and their runtime dependencies. */
@Service
public class RuntimeResourceRevisionService {
    private final CollectionTaskDefinitionMapper collectionTaskMapper;
    private final QualityTaskDefinitionMapper qualityTaskMapper;
    private final DataDevelopmentScriptMapper scriptMapper;
    private final DatasourceMapper datasourceMapper;
    private final DatasourceClusterBindingMapper datasourceClusterBindingMapper;
    private final DataModelMapper modelMapper;

    public RuntimeResourceRevisionService(CollectionTaskDefinitionMapper collectionTaskMapper,
                                          QualityTaskDefinitionMapper qualityTaskMapper,
                                          DataDevelopmentScriptMapper scriptMapper,
                                          DatasourceMapper datasourceMapper,
                                          DatasourceClusterBindingMapper datasourceClusterBindingMapper,
                                          DataModelMapper modelMapper) {
        this.collectionTaskMapper = collectionTaskMapper;
        this.qualityTaskMapper = qualityTaskMapper;
        this.scriptMapper = scriptMapper;
        this.datasourceMapper = datasourceMapper;
        this.datasourceClusterBindingMapper = datasourceClusterBindingMapper;
        this.modelMapper = modelMapper;
    }

    public String collectionTaskRevision(Long taskId) {
        return collectionTaskRevision(taskId, null);
    }

    public String collectionTaskRevision(Long taskId, LocalDateTime expectedUpdatedAt) {
        CollectionTaskDefinitionEntity task = collectionTaskMapper.selectById(taskId);
        if (task == null) throw notFound("Collection task", taskId);
        assertExpectedRevision("Collection task", taskId, expectedUpdatedAt, task.getUpdatedAt());
        Set<Long> datasourceIds = new TreeSet<Long>();
        Set<Long> modelIds = new TreeSet<Long>();
        collectDependencyIds(task.getSourceBindingsJson(), datasourceIds, modelIds);
        collectDependencyIds(task.getTargetBindingJson(), datasourceIds, modelIds);
        return digest("collection", task.getId(), task.getTenantId(), task.getProjectId(),
                task.getRuntimeClusterId(), task.getUpdatedAt(),
                datasourceRevision(datasourceIds), modelRevision(modelIds));
    }

    public String qualityTaskRevision(Long taskId) {
        return qualityTaskRevision(taskId, null);
    }

    public String qualityTaskRevision(Long taskId, LocalDateTime expectedUpdatedAt) {
        QualityTaskDefinitionEntity task = qualityTaskMapper.selectById(taskId);
        if (task == null) throw notFound("Quality task", taskId);
        assertExpectedRevision("Quality task", taskId, expectedUpdatedAt, task.getUpdatedAt());
        Set<Long> datasourceIds = new TreeSet<Long>();
        Set<Long> modelIds = new TreeSet<Long>();
        add(datasourceIds, task.getDatasourceId());
        add(modelIds, task.getModelId());
        return digest("quality", task.getId(), task.getTenantId(), task.getProjectId(),
                task.getRuntimeClusterId(), task.getUpdatedAt(),
                datasourceRevision(datasourceIds), modelRevision(modelIds));
    }

    public String scriptRevision(Long scriptId) {
        return scriptRevision(scriptId, null);
    }

    public String scriptRevision(Long scriptId, LocalDateTime expectedUpdatedAt) {
        DataDevelopmentScriptEntity script = scriptMapper.selectById(scriptId);
        if (script == null) throw notFound("Data script", scriptId);
        assertExpectedRevision("Data script", scriptId, expectedUpdatedAt, script.getUpdatedAt());
        Set<Long> datasourceIds = new TreeSet<Long>();
        Set<Long> modelIds = new TreeSet<Long>();
        add(datasourceIds, script.getDatasourceId());
        collectDependencyIds(script.getExecutionConfigJson(), datasourceIds, modelIds);
        return digest("script", script.getId(), script.getTenantId(), script.getProjectId(),
                script.getRuntimeClusterId(), script.getUpdatedAt(),
                datasourceRevision(datasourceIds), modelRevision(modelIds));
    }

    private String datasourceRevision(Set<Long> datasourceIds) {
        StringBuilder value = new StringBuilder();
        for (Long datasourceId : datasourceIds) {
            DatasourceEntity datasource = datasourceMapper.selectById(datasourceId);
            value.append('|').append(datasourceId).append(':');
            if (datasource == null) {
                value.append("missing");
            } else {
                value.append(datasource.getConnectionFingerprint()).append(':')
                        .append(datasource.getUpdatedAt()).append(':')
                        .append(datasource.getEnabled()).append(':')
                        .append(datasource.getExecutable()).append(':')
                        .append(datasourceBindingRevision(datasource));
            }
        }
        return value.toString();
    }

    private String datasourceBindingRevision(DatasourceEntity datasource) {
        List<DatasourceClusterBindingEntity> bindings = datasourceClusterBindingMapper.selectList(
                new LambdaQueryWrapper<DatasourceClusterBindingEntity>()
                        .eq(DatasourceClusterBindingEntity::getTenantId, datasource.getTenantId())
                        .eq(DatasourceClusterBindingEntity::getDatasourceId, datasource.getId())
                        .orderByAsc(DatasourceClusterBindingEntity::getRuntimeClusterId)
                        .orderByAsc(DatasourceClusterBindingEntity::getId));
        StringBuilder value = new StringBuilder();
        for (DatasourceClusterBindingEntity binding : bindings) {
            value.append(binding.getRuntimeClusterId()).append('=')
                    .append(binding.getEnabled()).append('@')
                    .append(binding.getUpdatedAt()).append(';');
        }
        return value.toString();
    }

    private String modelRevision(Set<Long> modelIds) {
        StringBuilder value = new StringBuilder();
        for (Long modelId : modelIds) {
            DataModelEntity model = modelMapper.selectById(modelId);
            value.append('|').append(modelId).append(':');
            if (model == null) {
                value.append("missing");
            } else {
                value.append(model.getDatasourceId()).append(':')
                        .append(model.getUpdatedAt()).append(':')
                        .append(model.getPhysicalLocator()).append(':')
                        .append(datasourceRevision(singletonId(model.getDatasourceId())));
            }
        }
        return value.toString();
    }

    private void collectDependencyIds(Object value, Set<Long> datasourceIds, Set<Long> modelIds) {
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (key.endsWith("datasourceid") || key.endsWith("datasourceids")) {
                    addIds(datasourceIds, entry.getValue());
                }
                if (key.endsWith("modelid") || key.endsWith("modelids")) {
                    addIds(modelIds, entry.getValue());
                }
                collectDependencyIds(entry.getValue(), datasourceIds, modelIds);
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                collectDependencyIds(item, datasourceIds, modelIds);
            }
        }
    }

    private void addIds(Set<Long> target, Object value) {
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) addIds(target, item);
            return;
        }
        if (value instanceof Map) {
            addIds(target, ((Map<?, ?>) value).get("id"));
            return;
        }
        if (value instanceof Number) {
            add(target, Long.valueOf(((Number) value).longValue()));
        } else if (value instanceof String) {
            try {
                add(target, Long.valueOf(((String) value).trim()));
            } catch (NumberFormatException ignored) {
                // Ignore non-ID configuration text.
            }
        }
    }

    private void add(Set<Long> target, Long value) {
        if (value != null) target.add(value);
    }

    private Set<Long> singletonId(Long value) {
        Set<Long> result = new TreeSet<Long>();
        add(result, value);
        return result;
    }

    private String digest(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            byte[] bytes = digest.digest();
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate runtime resource revision", ex);
        }
    }

    private StudioException notFound(String type, Long id) {
        return new StudioException(StudioErrorCode.NOT_FOUND, type + " not found: " + id);
    }

    private void assertExpectedRevision(String type,
                                        Long id,
                                        LocalDateTime expectedUpdatedAt,
                                        LocalDateTime actualUpdatedAt) {
        if (expectedUpdatedAt != null && !Objects.equals(expectedUpdatedAt, actualUpdatedAt)) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    type + " changed while dispatching; retry the run: " + id);
        }
    }
}

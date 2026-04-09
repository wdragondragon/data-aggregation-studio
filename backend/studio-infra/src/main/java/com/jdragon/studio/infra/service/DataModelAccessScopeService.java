package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class DataModelAccessScopeService {

    private final DataModelMapper dataModelMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;

    public DataModelAccessScopeService(DataModelMapper dataModelMapper,
                                       StudioSecurityService securityService,
                                       ProjectResourceAccessService projectResourceAccessService) {
        this.dataModelMapper = dataModelMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    public LambdaQueryWrapper<DataModelEntity> buildAccessibleQuery() {
        LambdaQueryWrapper<DataModelEntity> queryWrapper = new LambdaQueryWrapper<DataModelEntity>()
                .eq(DataModelEntity::getTenantId, securityService.currentTenantId());
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return queryWrapper;
        }
        List<Long> sharedIds = projectResourceAccessService.sharedResourceIdList(StudioConstants.RESOURCE_TYPE_DATA_MODEL);
        if (sharedIds.isEmpty()) {
            queryWrapper.eq(DataModelEntity::getProjectId, currentProjectId);
            return queryWrapper;
        }
        queryWrapper.and(wrapper -> wrapper.eq(DataModelEntity::getProjectId, currentProjectId)
                .or()
                .in(DataModelEntity::getId, sharedIds));
        return queryWrapper;
    }

    public Set<Long> listAccessibleModelIds(Long datasourceId,
                                            String modelKind) {
        return listAccessibleModelIds(datasourceId, modelKind, null);
    }

    public Set<Long> listAccessibleModelIds(Long datasourceId,
                                            String modelKind,
                                            Set<Long> candidateModelIds) {
        if (candidateModelIds != null && candidateModelIds.isEmpty()) {
            return new LinkedHashSet<Long>();
        }
        LambdaQueryWrapper<DataModelEntity> queryWrapper = buildAccessibleQuery()
                .select(DataModelEntity::getId);
        if (datasourceId != null) {
            queryWrapper.eq(DataModelEntity::getDatasourceId, datasourceId);
        }
        if (modelKind != null && !modelKind.trim().isEmpty()) {
            queryWrapper.eq(DataModelEntity::getModelKind, modelKind.trim().toUpperCase());
        }
        if (candidateModelIds != null) {
            queryWrapper.in(DataModelEntity::getId, candidateModelIds);
        }
        Set<Long> modelIds = new LinkedHashSet<Long>();
        for (DataModelEntity entity : dataModelMapper.selectList(queryWrapper)) {
            if (entity.getId() != null) {
                modelIds.add(entity.getId());
            }
        }
        return modelIds;
    }
}

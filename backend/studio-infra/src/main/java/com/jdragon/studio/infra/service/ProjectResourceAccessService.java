package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
import com.jdragon.studio.infra.mapper.ResourceShareMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProjectResourceAccessService {

    private final StudioSecurityService securityService;
    private final ResourceShareMapper resourceShareMapper;

    public ProjectResourceAccessService(StudioSecurityService securityService,
                                        ResourceShareMapper resourceShareMapper) {
        this.securityService = securityService;
        this.resourceShareMapper = resourceShareMapper;
    }

    public Long currentProjectId() {
        return securityService.currentProjectId();
    }

    public Long requireCurrentProjectId() {
        Long projectId = currentProjectId();
        if (projectId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project context is required");
        }
        return projectId;
    }

    public boolean hasProjectContext() {
        return currentProjectId() != null;
    }

    public Set<Long> sharedResourceIds(String resourceType) {
        Long projectId = currentProjectId();
        if (projectId == null) {
            return new LinkedHashSet<Long>();
        }
        List<ResourceShareEntity> shares = resourceShareMapper.selectList(new LambdaQueryWrapper<ResourceShareEntity>()
                .eq(ResourceShareEntity::getTargetProjectId, projectId)
                .eq(ResourceShareEntity::getResourceType, resourceType)
                .eq(ResourceShareEntity::getEnabled, 1));
        Set<Long> resourceIds = new LinkedHashSet<Long>();
        for (ResourceShareEntity share : shares) {
            if (share.getResourceId() != null) {
                resourceIds.add(share.getResourceId());
            }
        }
        return resourceIds;
    }

    public List<Long> sharedResourceIdList(String resourceType) {
        return new ArrayList<Long>(sharedResourceIds(resourceType));
    }

    public boolean canRead(String resourceType, Long ownerProjectId, Long resourceId) {
        Long currentProjectId = currentProjectId();
        if (currentProjectId == null || ownerProjectId == null) {
            return true;
        }
        if (currentProjectId.longValue() == ownerProjectId.longValue()) {
            return true;
        }
        if (resourceId == null) {
            return false;
        }
        Long sharedCount = resourceShareMapper.selectCount(new LambdaQueryWrapper<ResourceShareEntity>()
                .eq(ResourceShareEntity::getTargetProjectId, currentProjectId)
                .eq(ResourceShareEntity::getResourceType, resourceType)
                .eq(ResourceShareEntity::getResourceId, resourceId)
                .eq(ResourceShareEntity::getEnabled, 1));
        return sharedCount != null && sharedCount.longValue() > 0L;
    }

    public void assertReadable(String resourceType, Long ownerProjectId, Long resourceId, String notFoundMessage) {
        if (!canRead(resourceType, ownerProjectId, resourceId)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, notFoundMessage);
        }
    }

    public void assertWritable(Long ownerProjectId) {
        Long currentProjectId = currentProjectId();
        if (currentProjectId == null || ownerProjectId == null) {
            return;
        }
        if (currentProjectId.longValue() != ownerProjectId.longValue()) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Resource belongs to another project");
        }
    }
}

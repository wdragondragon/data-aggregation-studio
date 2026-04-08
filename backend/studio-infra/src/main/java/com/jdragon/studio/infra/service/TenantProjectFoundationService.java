package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.TenantEntity;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TenantProjectFoundationService {

    private final TenantMapper tenantMapper;
    private final ProjectMapper projectMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final StudioUserMapper userMapper;

    public TenantProjectFoundationService(TenantMapper tenantMapper,
                                          ProjectMapper projectMapper,
                                          TenantMemberMapper tenantMemberMapper,
                                          ProjectMemberMapper projectMemberMapper,
                                          StudioUserMapper userMapper) {
        this.tenantMapper = tenantMapper;
        this.projectMapper = projectMapper;
        this.tenantMemberMapper = tenantMemberMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.userMapper = userMapper;
    }

    public void bootstrapFoundation(StudioUserEntity adminUser) {
        normalizeUserTenantIds();
        TenantEntity tenant = ensureDefaultTenant();
        ProjectEntity project = ensureDefaultProject(tenant.getTenantCode());
        ensureTenantAdminMembership(tenant.getTenantCode(), adminUser.getId());
        ensureProjectMembership(project.getId(), tenant.getTenantCode(), adminUser.getId(), StudioConstants.ROLE_PROJECT_ADMIN);
        List<StudioUserEntity> users = userMapper.selectList(new LambdaQueryWrapper<StudioUserEntity>()
                .orderByAsc(StudioUserEntity::getId));
        for (StudioUserEntity user : users) {
            String roleCode = sameId(user.getId(), adminUser.getId())
                    ? StudioConstants.ROLE_PROJECT_ADMIN
                    : StudioConstants.ROLE_PROJECT_MEMBER;
            ensureProjectMembership(project.getId(), tenant.getTenantCode(), user.getId(), roleCode);
        }
    }

    private void normalizeUserTenantIds() {
        List<StudioUserEntity> users = userMapper.selectList(new LambdaQueryWrapper<StudioUserEntity>());
        for (StudioUserEntity user : users) {
            if (user.getTenantId() != null && !user.getTenantId().trim().isEmpty()) {
                continue;
            }
            user.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
            userMapper.updateById(user);
        }
    }

    private TenantEntity ensureDefaultTenant() {
        TenantEntity tenant = tenantMapper.selectOne(new LambdaQueryWrapper<TenantEntity>()
                .eq(TenantEntity::getTenantCode, StudioConstants.DEFAULT_TENANT_ID)
                .last("limit 1"));
        if (tenant != null) {
            if (tenant.getTenantId() == null || tenant.getTenantId().trim().isEmpty()) {
                tenant.setTenantId(tenant.getTenantCode());
                tenantMapper.updateById(tenant);
            }
            return tenant;
        }
        tenant = new TenantEntity();
        tenant.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
        tenant.setTenantCode(StudioConstants.DEFAULT_TENANT_ID);
        tenant.setTenantName(StudioConstants.DEFAULT_TENANT_NAME);
        tenant.setDescription("Bootstrap tenant for existing online studio data");
        tenant.setEnabled(1);
        tenantMapper.insert(tenant);
        return tenant;
    }

    private ProjectEntity ensureDefaultProject(String tenantId) {
        ProjectEntity project = projectMapper.selectOne(new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getTenantId, tenantId)
                .eq(ProjectEntity::getProjectCode, StudioConstants.DEFAULT_PROJECT_CODE)
                .last("limit 1"));
        if (project != null) {
            return project;
        }
        project = new ProjectEntity();
        project.setTenantId(tenantId);
        project.setProjectCode(StudioConstants.DEFAULT_PROJECT_CODE);
        project.setProjectName(StudioConstants.DEFAULT_PROJECT_NAME);
        project.setDescription("Bootstrap project for existing online studio data");
        project.setEnabled(1);
        project.setDefaultProject(1);
        projectMapper.insert(project);
        return project;
    }

    private void ensureTenantAdminMembership(String tenantId, Long userId) {
        TenantMemberEntity member = tenantMemberMapper.selectOne(new LambdaQueryWrapper<TenantMemberEntity>()
                .eq(TenantMemberEntity::getTenantId, tenantId)
                .eq(TenantMemberEntity::getUserId, userId)
                .last("limit 1"));
        if (member == null) {
            member = new TenantMemberEntity();
            member.setTenantId(tenantId);
            member.setUserId(userId);
            member.setRoleCode(StudioConstants.ROLE_TENANT_ADMIN);
            member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
            tenantMemberMapper.insert(member);
            return;
        }
        boolean changed = false;
        if (!StudioConstants.ROLE_TENANT_ADMIN.equalsIgnoreCase(member.getRoleCode())) {
            member.setRoleCode(StudioConstants.ROLE_TENANT_ADMIN);
            changed = true;
        }
        if (!StudioConstants.MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getStatus())) {
            member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
            changed = true;
        }
        if (changed) {
            tenantMemberMapper.updateById(member);
        }
    }

    private void ensureProjectMembership(Long projectId,
                                         String tenantId,
                                         Long userId,
                                         String roleCode) {
        ProjectMemberEntity member = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getUserId, userId)
                .last("limit 1"));
        if (member == null) {
            member = new ProjectMemberEntity();
            member.setTenantId(tenantId);
            member.setProjectId(projectId);
            member.setUserId(userId);
            member.setRoleCode(roleCode);
            member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
            projectMemberMapper.insert(member);
            return;
        }
        boolean changed = false;
        if (member.getTenantId() == null || !tenantId.equals(member.getTenantId())) {
            member.setTenantId(tenantId);
            changed = true;
        }
        if (!roleCode.equalsIgnoreCase(member.getRoleCode())) {
            member.setRoleCode(roleCode);
            changed = true;
        }
        if (!StudioConstants.MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getStatus())) {
            member.setStatus(StudioConstants.MEMBER_STATUS_ACTIVE);
            changed = true;
        }
        if (changed) {
            projectMemberMapper.updateById(member);
        }
    }

    private boolean sameId(Long left, Long right) {
        if (left == null || right == null) {
            return false;
        }
        return left.longValue() == right.longValue();
    }
}

package com.jdragon.studio.infra.service.unstructured;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.UnstructuredAclEffect;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.dto.enums.UnstructuredAclPrincipalType;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.StudioUserOptionView;
import com.jdragon.studio.dto.model.UnstructuredAclEntryView;
import com.jdragon.studio.dto.model.UnstructuredPermissionView;
import com.jdragon.studio.dto.model.request.UnstructuredAclEntryRequest;
import com.jdragon.studio.dto.model.request.UnstructuredPathAclRequest;
import com.jdragon.studio.dto.model.request.UnstructuredSourceAclRequest;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UnstructuredPathAclEntity;
import com.jdragon.studio.infra.entity.UnstructuredSourceAclEntity;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UnstructuredPathAclMapper;
import com.jdragon.studio.infra.mapper.UnstructuredSourceAclMapper;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class UnstructuredAclService {
    private final DataSourceService dataSourceService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final StudioSecurityService securityService;
    private final UnstructuredSourceAclMapper sourceAclMapper;
    private final UnstructuredPathAclMapper pathAclMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final StudioUserMapper userMapper;
    private final DatasourceTypeCapabilityService capabilityService;

    public UnstructuredAclService(DataSourceService dataSourceService,
                                  ProjectResourceAccessService projectResourceAccessService,
                                  StudioSecurityService securityService,
                                  UnstructuredSourceAclMapper sourceAclMapper,
                                  UnstructuredPathAclMapper pathAclMapper,
                                  ProjectMemberMapper projectMemberMapper,
                                  StudioUserMapper userMapper,
                                  DatasourceTypeCapabilityService capabilityService) {
        this.dataSourceService = dataSourceService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.securityService = securityService;
        this.sourceAclMapper = sourceAclMapper;
        this.pathAclMapper = pathAclMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.userMapper = userMapper;
        this.capabilityService = capabilityService;
    }

    public List<UnstructuredAclEntryView> sourceAcl(Long datasourceId) {
        DataSourceDefinition datasource = requireDatasource(datasourceId);
        requireAclManager(datasource);
        return sourceAclViews(sourceAclMapper.selectList(sourceAclQuery(datasource, null)));
    }

    @Transactional
    public List<UnstructuredAclEntryView> replaceSourceAcl(Long datasourceId,
                                                           UnstructuredSourceAclRequest request) {
        DataSourceDefinition datasource = requireDatasource(datasourceId);
        requireAclManager(datasource);
        sourceAclMapper.delete(sourceAclQuery(datasource, null));
        List<UnstructuredAclEntryRequest> entries = request == null ? null : request.getEntries();
        for (UnstructuredAclEntryRequest entry : entries == null
                ? List.<UnstructuredAclEntryRequest>of() : entries) {
            sourceAclMapper.insert(toSourceEntity(datasource, entry));
        }
        return sourceAclViews(sourceAclMapper.selectList(sourceAclQuery(datasource, null)));
    }

    public List<UnstructuredAclEntryView> pathAcl(Long datasourceId, String path) {
        DataSourceDefinition datasource = requireDatasource(datasourceId);
        requireAclManager(datasource);
        return pathAclViews(pathAclMapper.selectList(pathAclQuery(datasource, normalizePath(path))));
    }

    @Transactional
    public List<UnstructuredAclEntryView> replacePathAcl(Long datasourceId,
                                                         UnstructuredPathAclRequest request) {
        DataSourceDefinition datasource = requireDatasource(datasourceId);
        requireAclManager(datasource);
        String path = normalizePath(request == null ? null : request.getPath());
        pathAclMapper.delete(pathAclQuery(datasource, path));
        List<UnstructuredAclEntryRequest> entries = request == null ? null : request.getEntries();
        for (UnstructuredAclEntryRequest entry : entries == null
                ? List.<UnstructuredAclEntryRequest>of() : entries) {
            pathAclMapper.insert(toPathEntity(datasource, path,
                    request == null || !Boolean.FALSE.equals(request.getDirectory()), entry));
        }
        return pathAclViews(pathAclMapper.selectList(pathAclQuery(datasource, path)));
    }

    @Transactional
    public void deleteAcl(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "ACL id is required");
        }
        UnstructuredSourceAclEntity source = sourceAclMapper.selectById(id);
        if (source != null) {
            requireAclManager(requireDatasource(source.getDatasourceId()));
            sourceAclMapper.deleteById(id);
            return;
        }
        UnstructuredPathAclEntity path = pathAclMapper.selectById(id);
        if (path != null) {
            requireAclManager(requireDatasource(path.getDatasourceId()));
            pathAclMapper.deleteById(id);
            return;
        }
        throw new StudioException(StudioErrorCode.NOT_FOUND, "ACL not found");
    }

    public List<StudioUserOptionView> userOptions() {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        List<ProjectMemberEntity> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMemberEntity>()
                        .eq(ProjectMemberEntity::getProjectId, projectId)
                        .eq(ProjectMemberEntity::getStatus, "ACTIVE")
                        .orderByAsc(ProjectMemberEntity::getUserId));
        List<StudioUserOptionView> result = new ArrayList<StudioUserOptionView>();
        for (ProjectMemberEntity member : members) {
            StudioUserEntity user = userMapper.selectById(member.getUserId());
            if (user == null || !Integer.valueOf(1).equals(user.getEnabled())) {
                continue;
            }
            StudioUserOptionView view = new StudioUserOptionView();
            view.setId(user.getId());
            view.setUsername(user.getUsername());
            view.setDisplayName(user.getDisplayName());
            result.add(view);
        }
        return result;
    }

    public UnstructuredPermissionView permissions(Long datasourceId, String path) {
        DataSourceDefinition datasource = requireDatasource(datasourceId);
        String normalizedPath = normalizePath(path);
        UnstructuredPermissionView view = new UnstructuredPermissionView();
        view.setDatasourceId(datasourceId);
        view.setPath(normalizedPath);
        view.setOwnerOrAdmin(isOwnerOrAdmin(datasource));
        for (UnstructuredAclPermission permission : UnstructuredAclPermission.values()) {
            if (hasPermission(datasource, normalizedPath, permission)) {
                view.getEffectivePermissions().add(permission.name());
            }
        }
        return view;
    }

    public boolean hasPermission(DataSourceDefinition datasource,
                                 String path,
                                 UnstructuredAclPermission permission) {
        if (isOwnerOrAdmin(datasource)) {
            return true;
        }
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        Long userId = securityService.currentUserId();
        if (userId == null || projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMemberEntity>()
                        .eq(ProjectMemberEntity::getProjectId, projectId)
                        .eq(ProjectMemberEntity::getUserId, userId)
                        .eq(ProjectMemberEntity::getStatus, "ACTIVE")) == 0L) {
            return false;
        }
        List<UnstructuredPathAclEntity> pathRules = new ArrayList<>(pathAclMapper.selectList(
                new LambdaQueryWrapper<UnstructuredPathAclEntity>()
                        .eq(UnstructuredPathAclEntity::getTenantId, datasource.getTenantId())
                        .eq(UnstructuredPathAclEntity::getProjectId, datasource.getProjectId())
                        .eq(UnstructuredPathAclEntity::getDatasourceId, datasource.getId())
                        .eq(UnstructuredPathAclEntity::getPermission, permission.name())));
        pathRules.sort(Comparator
                .comparingInt((UnstructuredPathAclEntity rule) ->
                        rule.getPath() == null ? 0 : rule.getPath().length())
                .reversed()
                .thenComparingInt(rule -> UnstructuredAclPrincipalType.USER.name()
                        .equalsIgnoreCase(rule.getPrincipalType()) ? 0 : 1)
                .thenComparingInt(rule -> UnstructuredAclEffect.DENY.name()
                        .equalsIgnoreCase(rule.getEffect()) ? 0 : 1));
        String normalizedPath = normalizePath(path);
        for (UnstructuredPathAclEntity rule : pathRules) {
            if (!matchesPath(rule, normalizedPath)) {
                continue;
            }
            Boolean decision = ruleDecision(rule.getPrincipalType(), rule.getUserId(),
                    rule.getEffect(), userId);
            if (decision != null) {
                return decision;
            }
        }
        List<UnstructuredSourceAclEntity> sourceRules = new ArrayList<>(
                sourceAclMapper.selectList(sourceAclQuery(datasource, permission.name())));
        sourceRules.sort(Comparator
                .comparingInt((UnstructuredSourceAclEntity rule) ->
                        UnstructuredAclPrincipalType.USER.name()
                                .equalsIgnoreCase(rule.getPrincipalType()) ? 0 : 1)
                .thenComparingInt(rule -> UnstructuredAclEffect.DENY.name()
                        .equalsIgnoreCase(rule.getEffect()) ? 0 : 1));
        for (UnstructuredSourceAclEntity rule : sourceRules) {
            Boolean decision = ruleDecision(rule.getPrincipalType(), rule.getUserId(),
                    rule.getEffect(), userId);
            if (decision != null) {
                return decision;
            }
        }
        return permission == UnstructuredAclPermission.BROWSE
                || permission == UnstructuredAclPermission.DOWNLOAD;
    }

    public boolean matchesPath(UnstructuredPathAclEntity rule, String candidate) {
        String normalizedRule = normalizePath(rule == null ? null : rule.getPath());
        String normalizedCandidate = normalizePath(candidate);
        if (normalizedRule.equals(normalizedCandidate)) {
            return true;
        }
        if (rule != null && Integer.valueOf(0).equals(rule.getDirectory())) {
            return false;
        }
        return "/".equals(normalizedRule) || normalizedCandidate.startsWith(normalizedRule + "/");
    }

    public boolean isOwnerOrAdmin(DataSourceDefinition datasource) {
        return securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN, StudioConstants.ROLE_ADMIN,
                StudioConstants.ROLE_PROJECT_ADMIN)
                || (datasource.getCreatedBy() != null
                && datasource.getCreatedBy().equals(securityService.currentUserId()));
    }

    public static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return "/";
        }
        String value = rawPath.trim().replace('\\', '/');
        if (value.indexOf('\0') >= 0) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Path contains a NUL character");
        }
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        List<String> segments = new ArrayList<String>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Path must not contain '..'");
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    private DataSourceDefinition requireDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.requireRunnableForExecution(datasourceId);
        if (capabilityService == null) {
            throw new IllegalStateException("Datasource runtime capability service is required");
        }
        capabilityService.ensureRuntimeCapability(datasource.getTypeCode(), "browse");
        return datasource;
    }

    private void requireAclManager(DataSourceDefinition datasource) {
        if (!isOwnerOrAdmin(datasource)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "Only the datasource creator or an administrator can manage ACLs");
        }
    }

    private Boolean ruleDecision(String principalType,
                                 Long ruleUserId,
                                 String effect,
                                 Long currentUserId) {
        boolean applies = UnstructuredAclPrincipalType.PROJECT.name().equalsIgnoreCase(principalType)
                || (UnstructuredAclPrincipalType.USER.name().equalsIgnoreCase(principalType)
                && ruleUserId != null && ruleUserId.equals(currentUserId));
        if (!applies || effect == null
                || UnstructuredAclEffect.INHERIT.name().equalsIgnoreCase(effect)) {
            return null;
        }
        return UnstructuredAclEffect.ALLOW.name().equalsIgnoreCase(effect);
    }

    private LambdaQueryWrapper<UnstructuredSourceAclEntity> sourceAclQuery(
            DataSourceDefinition datasource, String permission) {
        LambdaQueryWrapper<UnstructuredSourceAclEntity> query =
                new LambdaQueryWrapper<UnstructuredSourceAclEntity>()
                        .eq(UnstructuredSourceAclEntity::getTenantId, datasource.getTenantId())
                        .eq(UnstructuredSourceAclEntity::getProjectId, datasource.getProjectId())
                        .eq(UnstructuredSourceAclEntity::getDatasourceId, datasource.getId());
        if (permission != null) {
            query.eq(UnstructuredSourceAclEntity::getPermission, permission);
        }
        return query.orderByAsc(UnstructuredSourceAclEntity::getId);
    }

    private LambdaQueryWrapper<UnstructuredPathAclEntity> pathAclQuery(
            DataSourceDefinition datasource, String path) {
        return new LambdaQueryWrapper<UnstructuredPathAclEntity>()
                .eq(UnstructuredPathAclEntity::getTenantId, datasource.getTenantId())
                .eq(UnstructuredPathAclEntity::getProjectId, datasource.getProjectId())
                .eq(UnstructuredPathAclEntity::getDatasourceId, datasource.getId())
                .eq(UnstructuredPathAclEntity::getPath, path)
                .orderByAsc(UnstructuredPathAclEntity::getId);
    }

    private UnstructuredSourceAclEntity toSourceEntity(DataSourceDefinition datasource,
                                                        UnstructuredAclEntryRequest entry) {
        validateAclEntry(datasource, entry);
        UnstructuredSourceAclEntity entity = new UnstructuredSourceAclEntity();
        entity.setTenantId(datasource.getTenantId());
        entity.setProjectId(datasource.getProjectId());
        entity.setDatasourceId(datasource.getId());
        entity.setPrincipalType(entry.getPrincipalType().toUpperCase(Locale.ROOT));
        entity.setUserId("USER".equals(entity.getPrincipalType()) ? entry.getUserId() : null);
        entity.setPermission(entry.getPermission().toUpperCase(Locale.ROOT));
        entity.setEffect(entry.getEffect().toUpperCase(Locale.ROOT));
        entity.setCreatedBy(securityService.currentUserId());
        return entity;
    }

    private UnstructuredPathAclEntity toPathEntity(DataSourceDefinition datasource,
                                                    String path,
                                                    boolean directory,
                                                    UnstructuredAclEntryRequest entry) {
        validateAclEntry(datasource, entry);
        UnstructuredPathAclEntity entity = new UnstructuredPathAclEntity();
        entity.setTenantId(datasource.getTenantId());
        entity.setProjectId(datasource.getProjectId());
        entity.setDatasourceId(datasource.getId());
        entity.setPath(path);
        entity.setDirectory(directory ? 1 : 0);
        entity.setPrincipalType(entry.getPrincipalType().toUpperCase(Locale.ROOT));
        entity.setUserId("USER".equals(entity.getPrincipalType()) ? entry.getUserId() : null);
        entity.setPermission(entry.getPermission().toUpperCase(Locale.ROOT));
        entity.setEffect(entry.getEffect().toUpperCase(Locale.ROOT));
        entity.setCreatedBy(securityService.currentUserId());
        return entity;
    }

    private void validateAclEntry(DataSourceDefinition datasource,
                                  UnstructuredAclEntryRequest entry) {
        if (entry == null || entry.getPrincipalType() == null
                || entry.getPermission() == null || entry.getEffect() == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "ACL entry is incomplete");
        }
        String principal = entry.getPrincipalType().toUpperCase(Locale.ROOT);
        if (!Set.of("PROJECT", "USER").contains(principal)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Invalid ACL principal type");
        }
        if ("USER".equals(principal)
                && (entry.getUserId() == null || projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMemberEntity>()
                        .eq(ProjectMemberEntity::getProjectId, datasource.getProjectId())
                        .eq(ProjectMemberEntity::getUserId, entry.getUserId())
                        .eq(ProjectMemberEntity::getStatus, "ACTIVE")) == 0L)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "ACL user must be an active project member");
        }
        if (!Set.of("BROWSE", "DOWNLOAD", "EDIT", "DELETE")
                .contains(entry.getPermission().toUpperCase(Locale.ROOT))
                || !Set.of("ALLOW", "DENY", "INHERIT")
                .contains(entry.getEffect().toUpperCase(Locale.ROOT))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Invalid ACL permission or effect");
        }
    }

    private List<UnstructuredAclEntryView> sourceAclViews(
            List<UnstructuredSourceAclEntity> entities) {
        List<UnstructuredAclEntryView> result = new ArrayList<UnstructuredAclEntryView>();
        for (UnstructuredSourceAclEntity entity : entities) {
            result.add(toView(entity));
        }
        return result;
    }

    private List<UnstructuredAclEntryView> pathAclViews(
            List<UnstructuredPathAclEntity> entities) {
        List<UnstructuredAclEntryView> result = new ArrayList<UnstructuredAclEntryView>();
        for (UnstructuredPathAclEntity entity : entities) {
            result.add(toView(entity));
        }
        return result;
    }

    private UnstructuredAclEntryView toView(UnstructuredSourceAclEntity entity) {
        UnstructuredAclEntryView view = new UnstructuredAclEntryView();
        view.setId(entity.getId());
        view.setDatasourceId(entity.getDatasourceId());
        view.setPrincipalType(entity.getPrincipalType());
        view.setUserId(entity.getUserId());
        view.setPermission(entity.getPermission());
        view.setEffect(entity.getEffect());
        hydrateUser(view);
        return view;
    }

    private UnstructuredAclEntryView toView(UnstructuredPathAclEntity entity) {
        UnstructuredAclEntryView view = new UnstructuredAclEntryView();
        view.setId(entity.getId());
        view.setDatasourceId(entity.getDatasourceId());
        view.setPath(entity.getPath());
        view.setDirectory(!Integer.valueOf(0).equals(entity.getDirectory()));
        view.setPrincipalType(entity.getPrincipalType());
        view.setUserId(entity.getUserId());
        view.setPermission(entity.getPermission());
        view.setEffect(entity.getEffect());
        hydrateUser(view);
        return view;
    }

    private void hydrateUser(UnstructuredAclEntryView view) {
        if (view.getUserId() == null) {
            return;
        }
        StudioUserEntity user = userMapper.selectById(view.getUserId());
        if (user != null) {
            view.setUsername(user.getUsername());
            view.setDisplayName(user.getDisplayName());
        }
    }
}

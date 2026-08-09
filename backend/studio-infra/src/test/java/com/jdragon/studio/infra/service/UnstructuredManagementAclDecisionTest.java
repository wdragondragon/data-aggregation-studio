package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.entity.UnstructuredPathAclEntity;
import com.jdragon.studio.infra.entity.UnstructuredSourceAclEntity;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.UnstructuredPathAclMapper;
import com.jdragon.studio.infra.mapper.UnstructuredSourceAclMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnstructuredManagementAclDecisionTest {

    private final DataSourceService dataSourceService = mock(DataSourceService.class);
    private final ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
    private final StudioSecurityService securityService = mock(StudioSecurityService.class);
    private final UnstructuredSourceAclMapper sourceAclMapper = mock(UnstructuredSourceAclMapper.class);
    private final UnstructuredPathAclMapper pathAclMapper = mock(UnstructuredPathAclMapper.class);
    private final ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
    private final UnstructuredManagementService service = new UnstructuredManagementService(
            dataSourceService, null, projectAccess, securityService, null,
            sourceAclMapper, pathAclMapper, null, projectMemberMapper, null);
    private final DataSourceDefinition datasource = datasource();

    @BeforeEach
    void setUp() {
        when(projectAccess.requireCurrentProjectId()).thenReturn(10L);
        when(securityService.currentUserId()).thenReturn(20L);
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(false);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        when(pathAclMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(sourceAclMapper.selectList(any())).thenReturn(new ArrayList<>());
    }

    @Test
    void defaultsAllowBrowseAndDownloadButNotMutation() {
        assertTrue(service.hasPermission(datasource, "/", UnstructuredAclPermission.BROWSE));
        assertTrue(service.hasPermission(datasource, "/", UnstructuredAclPermission.DOWNLOAD));
        assertFalse(service.hasPermission(datasource, "/", UnstructuredAclPermission.EDIT));
        assertFalse(service.hasPermission(datasource, "/", UnstructuredAclPermission.DELETE));
    }

    @Test
    void userRuleWinsOverProjectRuleAtTheSamePath() {
        when(pathAclMapper.selectList(any())).thenReturn(List.of(
                pathRule("/reports", 1, "PROJECT", null, "DENY"),
                pathRule("/reports", 1, "USER", 20L, "ALLOW")));

        assertTrue(service.hasPermission(datasource, "/reports/result.csv", UnstructuredAclPermission.EDIT));
    }

    @Test
    void MoreSpecificPathWinsBeforePrincipalSpecificity() {
        when(pathAclMapper.selectList(any())).thenReturn(List.of(
                pathRule("/reports", 1, "USER", 20L, "ALLOW"),
                pathRule("/reports/private", 1, "PROJECT", null, "DENY")));

        assertFalse(service.hasPermission(datasource, "/reports/private/result.csv",
                UnstructuredAclPermission.DOWNLOAD));
    }

    @Test
    void sourceDenyOverridesDefaultBrowsePermission() {
        when(sourceAclMapper.selectList(any())).thenReturn(List.of(sourceRule("PROJECT", null, "DENY")));

        assertFalse(service.hasPermission(datasource, "/", UnstructuredAclPermission.BROWSE));
    }

    @Test
    void inactiveProjectMemberHasNoPermission() {
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);

        assertFalse(service.hasPermission(datasource, "/", UnstructuredAclPermission.BROWSE));
    }

    @Test
    void administratorAlwaysHasAllPermissions() {
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);

        assertTrue(service.hasPermission(datasource, "/restricted", UnstructuredAclPermission.DELETE));
    }

    private DataSourceDefinition datasource() {
        DataSourceDefinition value = new DataSourceDefinition();
        value.setId(1L);
        value.setTenantId("default");
        value.setProjectId(10L);
        value.setCreatedBy(99L);
        value.setTypeCode("ftp");
        return value;
    }

    private UnstructuredPathAclEntity pathRule(String path, int directory, String principal,
                                                Long userId, String effect) {
        UnstructuredPathAclEntity rule = new UnstructuredPathAclEntity();
        rule.setPath(path);
        rule.setDirectory(directory);
        rule.setPrincipalType(principal);
        rule.setUserId(userId);
        rule.setPermission("EDIT");
        rule.setEffect(effect);
        return rule;
    }

    private UnstructuredSourceAclEntity sourceRule(String principal, Long userId, String effect) {
        UnstructuredSourceAclEntity rule = new UnstructuredSourceAclEntity();
        rule.setPrincipalType(principal);
        rule.setUserId(userId);
        rule.setPermission("BROWSE");
        rule.setEffect(effect);
        return rule;
    }
}

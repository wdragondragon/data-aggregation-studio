package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertPermissionApiRegressionTest extends StudioApiRegressionTestSupport {

    private static final String MEMBER_PASSWORD = "AlertMember@20260714!";
    private static final String PROJECT_ADMIN_PASSWORD = "AlertProjectAdmin@20260714!";
    private static final String TENANT_ADMIN_PASSWORD = "AlertTenantAdmin@20260714!";

    @Autowired
    private EncryptionService encryptionService;

    @Test
    void projectMemberShouldViewAndHandleProjectAlerts() throws Exception {
        Session admin = adminSession();
        Long memberId = createUser(admin.authorization, "alert_permission_member", MEMBER_PASSWORD);
        addProjectMember(admin, admin.projectId, memberId, StudioConstants.ROLE_PROJECT_MEMBER);
        String memberAuthorization = login("alert_permission_member", MEMBER_PASSWORD, admin.projectId);
        insertRuleAndIncident(admin.projectId, 930001L, 930011L, "member-visible-rule");

        performGet("/api/v1/alerts/options", memberAuthorization, admin.projectId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canManage").value(false))
                .andExpect(jsonPath("$.data.canHandleIncidents").value(true))
                .andExpect(jsonPath("$.data.canViewTenantSummary").value(false));
        performPost("/api/v1/alerts/rules/query", memberAuthorization, admin.projectId, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(930001L));
        performPost("/api/v1/alerts/incidents/query", memberAuthorization, admin.projectId, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(930011L));
        performPost("/api/v1/alerts/channels/query", memberAuthorization, admin.projectId, Map.of())
                .andExpect(status().isOk());
        performPost("/api/v1/alerts/deliveries/query", memberAuthorization, admin.projectId, Map.of())
                .andExpect(status().isOk());

        performPost("/api/v1/alerts/incidents/930011/acknowledge", memberAuthorization, admin.projectId,
                Map.of("comment", "acknowledged in permission regression"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
        performPost("/api/v1/alerts/incidents/930011/close", memberAuthorization, admin.projectId,
                Map.of("comment", "closed in permission regression"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    @Test
    void projectMemberShouldNotMaintainAlertConfigurationOrQueryTenantSummary() throws Exception {
        Session admin = adminSession();
        Long memberId = createUser(admin.authorization, "alert_permission_denied_member", MEMBER_PASSWORD);
        addProjectMember(admin, admin.projectId, memberId, StudioConstants.ROLE_PROJECT_MEMBER);
        String memberAuthorization = login("alert_permission_denied_member", MEMBER_PASSWORD, admin.projectId);

        assertForbiddenPost("/api/v1/alerts/rules", memberAuthorization, admin.projectId, validRulePayload("denied-rule"));
        assertForbiddenPost("/api/v1/alerts/rules/999/enable", memberAuthorization, admin.projectId, null);
        assertForbiddenPost("/api/v1/alerts/rules/999/disable", memberAuthorization, admin.projectId, null);
        assertForbiddenPost("/api/v1/alerts/rules/999/test", memberAuthorization, admin.projectId, null);
        mockMvc.perform(delete("/api/v1/alerts/rules/999")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(admin.projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertForbiddenPost("/api/v1/alerts/channels", memberAuthorization, admin.projectId,
                Map.of("name", "denied-channel", "endpointUrl", "https://example.com/hook"));
        assertForbiddenPost("/api/v1/alerts/channels/999/enable", memberAuthorization, admin.projectId, null);
        assertForbiddenPost("/api/v1/alerts/channels/999/disable", memberAuthorization, admin.projectId, null);
        assertForbiddenPost("/api/v1/alerts/channels/999/test", memberAuthorization, admin.projectId, null);
        mockMvc.perform(delete("/api/v1/alerts/channels/999")
                        .header(HttpHeaders.AUTHORIZATION, memberAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(admin.projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertForbiddenPost("/api/v1/alerts/deliveries/999/retry", memberAuthorization, admin.projectId, null);
        assertForbiddenPost("/api/v1/alerts/tenant-summary/query", memberAuthorization, admin.projectId, Map.of());
    }

    @Test
    void projectAdminShouldMaintainRulesAndChannels() throws Exception {
        Session admin = adminSession();
        Long projectAdminId = createUser(admin.authorization, "alert_permission_project_admin", PROJECT_ADMIN_PASSWORD);
        addProjectMember(admin, admin.projectId, projectAdminId, StudioConstants.ROLE_PROJECT_ADMIN);
        String authorization = login("alert_permission_project_admin", PROJECT_ADMIN_PASSWORD, admin.projectId);

        MvcResult ruleResult = performPost("/api/v1/alerts/rules", authorization, admin.projectId,
                validRulePayload("project-admin-rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("project-admin-rule"))
                .andReturn();
        long ruleId = readBody(ruleResult).path("data").path("id").asLong();
        performPost("/api/v1/alerts/rules/" + ruleId + "/enable", authorization, admin.projectId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        performPost("/api/v1/alerts/rules/" + ruleId + "/test", authorization, admin.projectId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventType").value("TEST"));
        performPost("/api/v1/alerts/rules/" + ruleId + "/disable", authorization, admin.projectId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        mockMvc.perform(delete("/api/v1/alerts/rules/{id}", ruleId)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(admin.projectId)))
                .andExpect(status().isOk());

        insertWebhookChannel(admin.projectId, 930021L, "project-admin-channel");
        performPost("/api/v1/alerts/channels", authorization, admin.projectId,
                Map.of("id", 930021L, "name", "project-admin-channel-updated", "enabled", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("project-admin-channel-updated"));
        performPost("/api/v1/alerts/channels/930021/enable", authorization, admin.projectId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        performPost("/api/v1/alerts/channels/930021/test", authorization, admin.projectId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventType").value("TEST"));
        performPost("/api/v1/alerts/channels/930021/disable", authorization, admin.projectId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        mockMvc.perform(delete("/api/v1/alerts/channels/930021")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(admin.projectId)))
                .andExpect(status().isOk());
    }

    @Test
    void tenantAdminSummaryShouldRemainInsideCurrentTenant() throws Exception {
        Session admin = adminSession();
        Long secondProjectId = createProject(admin, "alert_summary_second", "Alert Summary Second Project");
        Long tenantAdminId = createUser(admin.authorization, "alert_permission_tenant_admin", TENANT_ADMIN_PASSWORD);
        addTenantMember(admin, tenantAdminId, StudioConstants.ROLE_TENANT_ADMIN);
        addProjectMember(admin, admin.projectId, tenantAdminId, StudioConstants.ROLE_PROJECT_MEMBER);
        String authorization = login("alert_permission_tenant_admin", TENANT_ADMIN_PASSWORD, admin.projectId);
        insertOtherTenantProject();

        performGet("/api/v1/alerts/options", authorization, admin.projectId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canViewTenantSummary").value(true));

        MvcResult result = performPost("/api/v1/alerts/tenant-summary/query", authorization, admin.projectId, Map.of())
                .andExpect(status().isOk())
                .andReturn();

        JsonNode items = readBody(result).path("data").path("items");
        assertThat(items.findValuesAsText("projectName"))
                .contains("Alert Summary Second Project")
                .doesNotContain("Other Tenant Alert Project");
        assertThat(items.findValuesAsText("projectId")).contains(String.valueOf(secondProjectId));
    }

    @Test
    void alertDetailsAndListsShouldNotLeakAcrossProjects() throws Exception {
        Session admin = adminSession();
        Long secondProjectId = createProject(admin, "alert_isolation_second", "Alert Isolation Second Project");
        Long memberId = createUser(admin.authorization, "alert_permission_multi_project", MEMBER_PASSWORD);
        addProjectMember(admin, admin.projectId, memberId, StudioConstants.ROLE_PROJECT_MEMBER);
        addProjectMember(admin, secondProjectId, memberId, StudioConstants.ROLE_PROJECT_MEMBER);
        insertRuleAndIncident(admin.projectId, 930031L, 930041L, "project-a-only-rule");
        String authorization = login("alert_permission_multi_project", MEMBER_PASSWORD, secondProjectId);

        performGet("/api/v1/alerts/rules/930031", authorization, secondProjectId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        performGet("/api/v1/alerts/incidents/930041", authorization, secondProjectId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        MvcResult rules = performPost("/api/v1/alerts/rules/query", authorization, secondProjectId, Map.of())
                .andExpect(status().isOk())
                .andReturn();
        MvcResult incidents = performPost("/api/v1/alerts/incidents/query", authorization, secondProjectId, Map.of())
                .andExpect(status().isOk())
                .andReturn();
        assertThat(readBody(rules).path("data").path("items").findValuesAsText("id")).doesNotContain("930031");
        assertThat(readBody(incidents).path("data").path("items").findValuesAsText("id")).doesNotContain("930041");
    }

    private Session adminSession() throws Exception {
        JsonNode login = loginAsAdmin();
        return new Session(adminAuthorizationHeader(login), currentProjectId(login));
    }

    private Long createUser(String authorization, String username, String password) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("username", username);
        payload.put("displayName", username);
        payload.put("passwordHash", password);
        payload.put("enabled", 1);
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private Long createProject(Session admin, String code, String name) throws Exception {
        MvcResult result = performPost("/api/v1/system/projects", admin.authorization, admin.projectId,
                Map.of("projectCode", code, "projectName", name, "enabled", 1))
                .andExpect(status().isOk())
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private void addProjectMember(Session admin, Long projectId, Long userId, String roleCode) throws Exception {
        performPost("/api/v1/system/project-members", admin.authorization, admin.projectId,
                Map.of("projectId", projectId, "userId", userId, "roleCode", roleCode,
                        "status", StudioConstants.MEMBER_STATUS_ACTIVE))
                .andExpect(status().isOk());
    }

    private void addTenantMember(Session admin, Long userId, String roleCode) throws Exception {
        performPost("/api/v1/system/tenant-members", admin.authorization, admin.projectId,
                Map.of("userId", userId, "roleCode", roleCode, "status", StudioConstants.MEMBER_STATUS_ACTIVE))
                .andExpect(status().isOk());
    }

    private String login(String username, String password, Long projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + readBody(result).path("data").path("token").asText();
    }

    private org.springframework.test.web.servlet.ResultActions performGet(String path, String authorization,
                                                                           Long projectId) throws Exception {
        return mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                .accept(MediaType.APPLICATION_JSON));
    }

    private org.springframework.test.web.servlet.ResultActions performPost(String path, String authorization,
                                                                            Long projectId, Object payload) throws Exception {
        var request = post(path)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                .contentType(MediaType.APPLICATION_JSON);
        if (payload != null) {
            request.content(objectMapper.writeValueAsString(payload));
        }
        return mockMvc.perform(request);
    }

    private void assertForbiddenPost(String path, String authorization, Long projectId, Object payload) throws Exception {
        performPost(path, authorization, projectId, payload)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private Map<String, Object> validRulePayload(String name) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name);
        payload.put("ruleType", "EXECUTION_FAILED");
        payload.put("subjectType", "COLLECTION_TASK");
        payload.put("severity", "WARNING");
        payload.put("enabled", false);
        payload.put("condition", Map.of());
        payload.put("inAppEnabled", true);
        payload.put("notifyProjectAdmins", true);
        return payload;
    }

    private void insertRuleAndIncident(Long projectId, Long ruleId, Long incidentId, String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("insert into studio_alert_rule " +
                        "(id, tenant_id, project_id, deleted, created_at, updated_at, name, rule_type, subject_type, " +
                        "severity, enabled, condition_json, silence_minutes, recovery_notification_enabled, " +
                        "in_app_enabled, recipient_user_ids_json, notify_resource_owner, notify_project_admins, " +
                        "webhook_channel_ids_json) values (?, ?, ?, 0, ?, ?, ?, 'EXECUTION_FAILED', " +
                        "'COLLECTION_TASK', 'WARNING', 1, '{}', 30, 1, 1, '[]', 0, 1, '[]')",
                ruleId, StudioConstants.DEFAULT_TENANT_ID, projectId, now, now, name);
        jdbcTemplate.update("insert into studio_alert_incident " +
                        "(id, tenant_id, project_id, deleted, created_at, updated_at, rule_id, rule_name_snapshot, " +
                        "rule_type, signature, subject_type, subject_key, severity, status, summary, " +
                        "current_evidence_json, occurrence_count, notification_count, reopen_count, condition_active, " +
                        "closed_while_active, first_triggered_at, last_triggered_at, version) " +
                        "values (?, ?, ?, 0, ?, ?, ?, ?, 'EXECUTION_FAILED', ?, 'COLLECTION_TASK', ?, " +
                        "'WARNING', 'OPEN', ?, '{}', 1, 1, 0, 1, 0, ?, ?, 0)",
                incidentId, StudioConstants.DEFAULT_TENANT_ID, projectId, now, now, ruleId, name,
                "permission-signature-" + incidentId, "COLLECTION_TASK:" + incidentId,
                "Permission regression incident", now, now);
    }

    private void insertWebhookChannel(Long projectId, Long channelId, String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("insert into studio_alert_channel " +
                        "(id, tenant_id, project_id, deleted, created_at, updated_at, name, channel_type, " +
                        "endpoint_ciphertext, headers_ciphertext, enabled) values (?, ?, ?, 0, ?, ?, ?, 'WEBHOOK', ?, ?, 0)",
                channelId, StudioConstants.DEFAULT_TENANT_ID, projectId, now, now, name,
                encryptionService.encrypt("https://example.com/hook"), encryptionService.encrypt("{}"));
    }

    private void insertOtherTenantProject() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("insert into studio_project " +
                        "(id, tenant_id, deleted, created_at, updated_at, project_code, project_name, enabled, default_project) " +
                        "values (930051, 'other-tenant', 0, ?, ?, 'other-tenant-alert', 'Other Tenant Alert Project', 1, 0)",
                now, now);
    }

    private static final class Session {
        private final String authorization;
        private final Long projectId;

        private Session(String authorization, Long projectId) {
            this.authorization = authorization;
            this.projectId = projectId;
        }
    }
}

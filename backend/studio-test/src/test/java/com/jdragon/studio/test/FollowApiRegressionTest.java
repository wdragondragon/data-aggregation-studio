package com.jdragon.studio.test;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.service.FollowSubscriptionService;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FollowApiRegressionTest extends StudioApiRegressionTestSupport {

    @Autowired
    private FollowSubscriptionService followSubscriptionService;

    @Test
    void followApisShouldValidateTargetExistenceAndReadableScope() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String authorization = "Bearer " + readBody(loginResult).path("data").path("token").asText();
        Long sourceProjectId = readBody(loginResult).path("data").path("currentProjectId").asLong();
        Long runtimeClusterId = createAndAuthorizeTestRuntimeCluster(authorization, sourceProjectId);

        Long receiverProjectId = createProject(authorization, sourceProjectId,
                "lt_reg_s18_follow_receiver", "长期回归-S18关注接收项目");
        authorizeTestRuntimeCluster(authorization, receiverProjectId, runtimeClusterId);
        Long receiverUserId = createUser(authorization,
                "lt_reg_s18_follow_member", "长期回归-S18关注普通成员", "LtReg@20260622!");
        addProjectMember(authorization, sourceProjectId, receiverProjectId, receiverUserId);
        String receiverAuthorization = loginAndGetAuthorization("lt_reg_s18_follow_member", "LtReg@20260622!", receiverProjectId);

        Long workflowId = createWorkflow(authorization, sourceProjectId, runtimeClusterId,
                "lt_reg_s18_follow_shared_workflow", "长期回归-S18共享关注流程");
        shareWorkflow(authorization, sourceProjectId, receiverProjectId, workflowId);

        mockMvc.perform(post("/api/v1/follows")
                        .header(HttpHeaders.AUTHORIZATION, receiverAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(receiverProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"WORKFLOW\",\"targetId\":\"" + workflowId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.following").value(true));

        Long staleTargetId = Long.valueOf(2099999999999999998L);
        insertStaleFollow(receiverProjectId, receiverUserId, staleTargetId);
        mockMvc.perform(delete("/api/v1/follows")
                        .param("targetType", "WORKFLOW")
                        .param("targetId", String.valueOf(staleTargetId))
                        .header(HttpHeaders.AUTHORIZATION, receiverAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(receiverProjectId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertEquals(Long.valueOf(0L), countActiveFollow(receiverProjectId, receiverUserId, staleTargetId));

        mockMvc.perform(post("/api/v1/follows")
                        .header(HttpHeaders.AUTHORIZATION, receiverAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(receiverProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"WORKFLOW\",\"targetId\":\"2099999999999999999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Long sourceWorkflowRunId = Long.valueOf(880000000000000001L);
        insertWorkflowRun(sourceProjectId, workflowId, sourceWorkflowRunId);

        mockMvc.perform(get("/api/v1/workflow-runs/{workflowRunId}", sourceWorkflowRunId)
                        .header(HttpHeaders.AUTHORIZATION, receiverAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(receiverProjectId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/follows/status")
                        .param("targetType", "WORKFLOW_RUN")
                        .param("targetId", String.valueOf(sourceWorkflowRunId))
                        .header(HttpHeaders.AUTHORIZATION, receiverAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(receiverProjectId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(post("/api/v1/follows")
                        .header(HttpHeaders.AUTHORIZATION, receiverAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(receiverProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"WORKFLOW_RUN\",\"targetId\":\"" + sourceWorkflowRunId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void sharedWorkflowFollowersShouldFanOutOnlyWhileShareEnabled() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String authorization = "Bearer " + readBody(loginResult).path("data").path("token").asText();
        Long sourceProjectId = readBody(loginResult).path("data").path("currentProjectId").asLong();
        Long runtimeClusterId = createAndAuthorizeTestRuntimeCluster(authorization, sourceProjectId);

        Long receiverProjectId = createProject(authorization, sourceProjectId,
                "lt_reg_s20_follow_receiver", "长期回归-S20关注通知接收项目");
        authorizeTestRuntimeCluster(authorization, receiverProjectId, runtimeClusterId);
        Long receiverUserId = createUser(authorization,
                "lt_reg_s20_follow_member", "长期回归-S20共享关注通知成员", "LtReg@20260622S20!");
        addProjectMember(authorization, sourceProjectId, receiverProjectId, receiverUserId);
        String receiverAuthorization = loginAndGetAuthorization("lt_reg_s20_follow_member", "LtReg@20260622S20!", receiverProjectId);

        Long workflowId = createWorkflow(authorization, sourceProjectId, runtimeClusterId,
                "lt_reg_s20_shared_follow_workflow", "长期回归-S20共享关注通知流程");
        Long shareId = shareWorkflow(authorization, sourceProjectId, receiverProjectId, workflowId);

        mockMvc.perform(post("/api/v1/follows")
                        .header(HttpHeaders.AUTHORIZATION, receiverAuthorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(receiverProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"WORKFLOW\",\"targetId\":\"" + workflowId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.following").value(true));

        Map<Long, Long> followerProjectIds = followSubscriptionService.followerUserProjectIds(StudioConstants.DEFAULT_TENANT_ID, sourceProjectId,
                StudioConstants.FOLLOW_TARGET_WORKFLOW, workflowId);
        Assertions.assertTrue(
                followerProjectIds.containsKey(receiverUserId),
                "Shared workflow follower should receive source workflow run notifications while the share is enabled");
        Assertions.assertEquals(receiverProjectId, followerProjectIds.get(receiverUserId),
                "Shared workflow follower notifications should retain the readable receiver project context");

        disableWorkflowShare(authorization, sourceProjectId, receiverProjectId, workflowId, shareId);

        Assertions.assertFalse(
                followSubscriptionService.followerUserIds(StudioConstants.DEFAULT_TENANT_ID, sourceProjectId,
                        StudioConstants.FOLLOW_TARGET_WORKFLOW, workflowId).contains(receiverUserId),
                "Shared workflow follower must stop receiving source workflow run notifications after the share is disabled");
    }

    private Long createProject(String authorization, Long currentProjectId, String projectCode, String projectName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/system/projects")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(currentProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectCode\":\"" + projectCode + "\",\"projectName\":\"" + projectName + "\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private Long createUser(String authorization, String username, String displayName, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"" + displayName
                                + "\",\"passwordHash\":\"" + password + "\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private void addProjectMember(String authorization, Long currentProjectId, Long projectId, Long userId) throws Exception {
        mockMvc.perform(post("/api/v1/system/project-members")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(currentProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"" + projectId + "\",\"userId\":\"" + userId
                                + "\",\"roleCode\":\"PROJECT_MEMBER\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private String loginAndGetAuthorization(String username, String password, Long projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return "Bearer " + readBody(result).path("data").path("token").asText();
    }

    private Long createWorkflow(String authorization,
                                Long projectId,
                                Long runtimeClusterId,
                                String code,
                                String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workflows")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name
                                + "\",\"runtimeClusterId\":\"" + runtimeClusterId
                                + "\",\"nodes\":[{\"nodeCode\":\"s18_http_probe\",\"nodeName\":\"长期回归-S18健康检查节点\",\"nodeType\":\"HTTP\",\"config\":{\"method\":\"GET\",\"url\":\"http://127.0.0.1:18080/actuator/health\"}}],\"edges\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private Long shareWorkflow(String authorization, Long sourceProjectId, Long receiverProjectId, Long workflowId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/system/resource-shares")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(sourceProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceProjectId\":\"" + sourceProjectId + "\",\"targetProjectId\":\"" + receiverProjectId
                                + "\",\"resourceType\":\"WORKFLOW\",\"resourceId\":\"" + workflowId + "\",\"enabled\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return readBody(result).path("data").path("id").asLong();
    }

    private void disableWorkflowShare(String authorization,
                                      Long sourceProjectId,
                                      Long receiverProjectId,
                                      Long workflowId,
                                      Long shareId) throws Exception {
        mockMvc.perform(post("/api/v1/system/resource-shares")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(StudioConstants.REQUEST_TENANT_HEADER, StudioConstants.DEFAULT_TENANT_ID)
                        .header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(sourceProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + shareId + "\",\"sourceProjectId\":\"" + sourceProjectId
                                + "\",\"targetProjectId\":\"" + receiverProjectId
                                + "\",\"resourceType\":\"WORKFLOW\",\"resourceId\":\"" + workflowId + "\",\"enabled\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private void insertWorkflowRun(Long projectId, Long workflowId, Long workflowRunId) {
        jdbcTemplate.update("insert into run_record(id, tenant_id, project_id, deleted, created_at, updated_at, execution_type, workflow_run_id, workflow_definition_id, node_code, status, started_at, ended_at, message, payload_json, result_json) "
                        + "values (?, ?, ?, 0, datetime('now'), datetime('now'), ?, ?, ?, ?, ?, datetime('now'), datetime('now'), ?, ?, ?)",
                Long.valueOf(880000000000000002L),
                StudioConstants.DEFAULT_TENANT_ID,
                projectId,
                "WORKFLOW_NODE",
                workflowRunId,
                workflowId,
                "s18_http_probe",
                "SUCCESS",
                "长期回归-S18源项目运行记录",
                "{}",
                "{}");
    }

    private void insertStaleFollow(Long projectId, Long userId, Long targetId) {
        jdbcTemplate.update("insert into studio_follow_subscription(id, deleted, created_at, updated_at, tenant_id, project_id, user_id, target_type, target_id, enabled) "
                        + "values (?, 0, datetime('now'), datetime('now'), ?, ?, ?, ?, ?, 1)",
                Long.valueOf(880000000000000003L),
                StudioConstants.DEFAULT_TENANT_ID,
                projectId,
                userId,
                StudioConstants.FOLLOW_TARGET_WORKFLOW,
                targetId);
    }

    private Long countActiveFollow(Long projectId, Long userId, Long targetId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from studio_follow_subscription where tenant_id = ? and project_id = ? and user_id = ? and target_type = ? and target_id = ? and enabled = 1",
                Long.class,
                StudioConstants.DEFAULT_TENANT_ID,
                projectId,
                userId,
                StudioConstants.FOLLOW_TARGET_WORKFLOW,
                targetId);
    }
}

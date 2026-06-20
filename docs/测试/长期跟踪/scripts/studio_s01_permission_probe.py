#!/usr/bin/env python
"""Studio S01 permission isolation probe.

The probe is intentionally data-preserving. It creates or updates long-term
fixtures with lt_reg_ prefixes and never deletes long-term test data.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime
from typing import Any, Callable

import requests


BASE_URL = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
RECEIVER_PROJECT_ID = os.getenv("STUDIO_RECEIVER_PROJECT_ID", "2068301893337849857")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")
S01_PASSWORD = os.getenv("STUDIO_S01_PASSWORD", "LtRegS01!2026")
NACOS_READINESS = os.getenv(
    "STUDIO_NACOS_READINESS",
    "http://127.0.0.1:8848/nacos/v1/console/health/readiness",
)
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")


class StudioClient:
    def __init__(self) -> None:
        self.session = requests.Session()

    def request(
        self,
        method: str,
        path: str,
        token: str | None = None,
        project_id: str | None = LONG_PROJECT_ID,
        params: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
        timeout: int = 25,
    ) -> dict[str, Any]:
        headers = {"X-Tenant-Id": TENANT_ID}
        if project_id is not None:
            headers["X-Project-Id"] = str(project_id)
        if token:
            headers["Authorization"] = f"Bearer {token}"
        response = self.session.request(
            method,
            BASE_URL + path,
            headers=headers,
            params=params,
            json=json_body,
            timeout=timeout,
        )
        try:
            body: Any = response.json()
        except Exception:
            body = response.text
        return {"status": response.status_code, "body": body}

    def login(self, username: str, password: str, project_id: str = LONG_PROJECT_ID) -> tuple[str | None, dict[str, Any]]:
        response = self.request(
            "POST",
            "/auth/login",
            project_id=project_id,
            json_body={"username": username, "password": password},
        )
        token = None
        if isinstance(response["body"], dict):
            token = (response["body"].get("data") or {}).get("token")
        return token, response


def is_ok(response: dict[str, Any]) -> bool:
    return (
        response.get("status") == 200
        and isinstance(response.get("body"), dict)
        and response["body"].get("success") is True
    )


def response_code(response: dict[str, Any]) -> str | None:
    body = response.get("body")
    return body.get("code") if isinstance(body, dict) else None


def data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def summarize(response: dict[str, Any] | None) -> dict[str, Any]:
    if response is None:
        return {"status": "SKIP"}
    body = response.get("body")
    if isinstance(body, dict):
        payload = body.get("data")
        result: dict[str, Any] = {
            "status": response.get("status"),
            "success": body.get("success"),
            "code": body.get("code"),
            "message": body.get("message"),
        }
        if isinstance(payload, list):
            result["dataCount"] = len(payload)
        elif isinstance(payload, dict):
            result["dataKeys"] = list(payload.keys())[:12]
        return result
    return {"status": response.get("status"), "body": str(body)[:300]}


def expect_ok(response: dict[str, Any]) -> bool:
    return is_ok(response)


def expect_401_403(response: dict[str, Any]) -> bool:
    return response.get("status") in (401, 403)


def expect_forbidden(response: dict[str, Any]) -> bool:
    return response.get("status") == 403 and response_code(response) == "FORBIDDEN"


def expect_not_found(response: dict[str, Any]) -> bool:
    return response.get("status") == 404 and response_code(response) == "NOT_FOUND"


def expect_forbidden_or_unauth(response: dict[str, Any]) -> bool:
    return response.get("status") in (401, 403) and response_code(response) in ("FORBIDDEN", "UNAUTHORIZED")


def ensure_user(
    client: StudioClient,
    admin_token: str,
    existing_users: dict[str, dict[str, Any]],
    username: str,
    display_name: str,
    enabled: int,
    project_id: str,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "username": username,
        "displayName": display_name,
        "enabled": enabled,
        "passwordHash": S01_PASSWORD,
    }
    if username in existing_users:
        payload["id"] = existing_users[username].get("id")
    user_response = client.request("POST", "/users", admin_token, LONG_PROJECT_ID, json_body=payload)
    user_data = data(user_response) if is_ok(user_response) else None
    membership_response = None
    if user_data:
        membership_response = client.request(
            "POST",
            "/system/project-members",
            admin_token,
            project_id,
            json_body={
                "projectId": int(project_id),
                "userId": int(user_data["id"]),
                "roleCode": "PROJECT_MEMBER",
                "status": "ACTIVE",
            },
        )
    return {
        "id": str(user_data.get("id")) if user_data else None,
        "save": summarize(user_response),
        "membership": summarize(membership_response),
    }


def ensure_workflow_fixture(client: StudioClient, admin_token: str) -> dict[str, Any]:
    workflow_code = "lt_reg_s01_shared_boundary_workflow"
    workflows = data(client.request("GET", "/workflows", admin_token, LONG_PROJECT_ID)) or []
    existing = next((item for item in workflows if item.get("code") == workflow_code), None)
    payload: dict[str, Any] = {
        "definitionId": existing.get("id") if existing else None,
        "code": workflow_code,
        "name": "长期回归-S01共享边界探针流程",
        "schedule": {"enabled": False, "timezone": "Asia/Shanghai", "cronExpression": None},
        "nodes": [
            {
                "nodeCode": "s01_http_health",
                "nodeName": "本地健康检查节点",
                "nodeType": "HTTP",
                "config": {"method": "GET", "url": "http://127.0.0.1:18080/actuator/health"},
                "fieldMappings": [],
            }
        ],
        "edges": [],
    }
    save_response = client.request("POST", "/workflows", admin_token, LONG_PROJECT_ID, json_body=payload)
    workflow = data(save_response) if is_ok(save_response) else existing
    workflow_id = str(workflow.get("id")) if workflow else None
    publish_response = (
        client.request("POST", f"/workflows/{workflow_id}/publish", admin_token, LONG_PROJECT_ID)
        if workflow_id
        else None
    )
    share_response = (
        client.request(
            "POST",
            "/system/resource-shares",
            admin_token,
            LONG_PROJECT_ID,
            json_body={
                "sourceProjectId": int(LONG_PROJECT_ID),
                "targetProjectId": int(RECEIVER_PROJECT_ID),
                "resourceType": "WORKFLOW",
                "resourceId": int(workflow_id),
                "enabled": 1,
            },
        )
        if workflow_id
        else None
    )
    return {
        "id": workflow_id,
        "payload": payload,
        "save": summarize(save_response),
        "publish": summarize(publish_response),
        "share": summarize(share_response),
    }


def add_result(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    expectation: Callable[[dict[str, Any]], bool],
    response: dict[str, Any],
    extra: dict[str, Any] | None = None,
    need_fix: bool | None = None,
) -> None:
    passed = expectation(response)
    results.append(
        {
            "case": case_id,
            "module": module,
            "actual": summarize(response),
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
            "extra": extra or {},
        }
    )


def read_health() -> dict[str, Any]:
    health: dict[str, Any] = {}
    for key, url in (("nacos", NACOS_READINESS), ("server", SERVER_HEALTH), ("worker", WORKER_HEALTH)):
        try:
            response = requests.get(url, timeout=10)
            try:
                value: Any = response.json()
            except Exception:
                value = response.text
            health[key] = value
        except Exception as exc:
            health[key] = {"error": str(exc)}
    return health


def main() -> int:
    client = StudioClient()
    admin_token, admin_login = client.login(ADMIN_USER, ADMIN_PASSWORD, LONG_PROJECT_ID)
    if not admin_token:
        print(json.dumps({"fatal": "admin login failed", "login": summarize(admin_login)}, ensure_ascii=False, indent=2))
        return 2

    worker_binding = client.request(
        "POST",
        "/system/project-workers",
        admin_token,
        RECEIVER_PROJECT_ID,
        json_body={
            "projectId": int(RECEIVER_PROJECT_ID),
            "workerGroupCode": "studio-online-worker-01",
            "workerCode": "studio-online-worker-01",
            "enabled": 1,
        },
    )
    users = data(client.request("GET", "/users", admin_token, LONG_PROJECT_ID)) or []
    existing_users = {item["username"]: item for item in users if isinstance(item, dict) and item.get("username")}
    fixture_users = {
        username: ensure_user(client, admin_token, existing_users, username, display_name, enabled, project_id)
        for username, display_name, enabled, project_id in (
            ("lt_reg_s01_long_project_member", "长期回归-S01客户运营普通成员", 1, LONG_PROJECT_ID),
            ("lt_reg_s01_receiver_member", "长期回归-S01资源接收项目成员", 1, RECEIVER_PROJECT_ID),
            ("lt_reg_s01_cross_project_observer", "长期回归-S01跨项目观察成员", 1, RECEIVER_PROJECT_ID),
            ("lt_reg_s01_disabled_guard", "长期回归-S01禁用登录用户", 0, LONG_PROJECT_ID),
        )
    }
    workflow_fixture = ensure_workflow_fixture(client, admin_token)
    workflow_id = workflow_fixture["id"]

    member_token, member_login = client.login("lt_reg_s01_long_project_member", S01_PASSWORD, LONG_PROJECT_ID)
    receiver_token, receiver_login = client.login("lt_reg_s01_receiver_member", S01_PASSWORD, RECEIVER_PROJECT_ID)
    cross_token, cross_login = client.login("lt_reg_s01_cross_project_observer", S01_PASSWORD, RECEIVER_PROJECT_ID)
    _, disabled_login = client.login("lt_reg_s01_disabled_guard", S01_PASSWORD, LONG_PROJECT_ID)

    results: list[dict[str, Any]] = []
    add_result(results, "S01-API-001", "未登录保护", expect_401_403, client.request("GET", "/roles", None, LONG_PROJECT_ID), {"url": "GET /roles"})
    add_result(results, "S01-API-002", "禁用用户登录", expect_401_403, disabled_login, {"username": "lt_reg_s01_disabled_guard"})
    add_result(results, "S01-API-003", "管理员角色列表", expect_ok, client.request("GET", "/roles", admin_token, LONG_PROJECT_ID))
    add_result(results, "S01-API-004", "管理员权限列表", expect_ok, client.request("GET", "/permissions", admin_token, LONG_PROJECT_ID))

    if member_token:
        add_result(results, "S01-API-005", "普通成员禁止读角色", expect_forbidden, client.request("GET", "/roles", member_token, LONG_PROJECT_ID))
        add_result(
            results,
            "S01-API-006",
            "普通成员禁止写角色",
            expect_forbidden,
            client.request(
                "POST",
                "/roles",
                member_token,
                LONG_PROJECT_ID,
                json_body={"code": "LT_REG_S01_FORBIDDEN_ROLE_PROBE", "name": "长期回归-S01越权角色探针"},
            ),
        )
        add_result(results, "S01-API-007", "普通成员禁止读权限", expect_forbidden, client.request("GET", "/permissions", member_token, LONG_PROJECT_ID))
        add_result(
            results,
            "S01-API-008",
            "普通成员禁止写权限",
            expect_forbidden,
            client.request(
                "POST",
                "/permissions",
                member_token,
                LONG_PROJECT_ID,
                json_body={"code": "lt_reg:s01:forbidden-permission-probe", "name": "长期回归-S01越权权限探针"},
            ),
        )
        add_result(results, "S01-API-009", "普通成员禁止用户管理", expect_forbidden, client.request("GET", "/users", member_token, LONG_PROJECT_ID))
        add_result(
            results,
            "S01-API-010",
            "普通成员禁止项目成员管理",
            expect_forbidden,
            client.request("GET", "/system/project-members", member_token, LONG_PROJECT_ID, params={"projectId": LONG_PROJECT_ID}),
        )
        projects = client.request("GET", "/system/projects", member_token, LONG_PROJECT_ID)
        project_ids = [str(item.get("id")) for item in (data(projects) or [])]
        add_result(
            results,
            "S01-API-011",
            "普通成员项目列表隔离",
            lambda response: is_ok(response) and project_ids == [LONG_PROJECT_ID],
            projects,
            {"projectIds": project_ids},
        )
    else:
        results.append({"case": "S01-API-005..011", "module": "普通成员", "actual": summarize(member_login), "result": "BLOCKED", "needFix": False})

    if receiver_token and workflow_id:
        workflows = client.request("GET", "/workflows", receiver_token, RECEIVER_PROJECT_ID)
        visible_ids = [str(item.get("id")) for item in (data(workflows) or [])]
        add_result(
            results,
            "S01-API-012",
            "接收项目可见共享工作流",
            lambda response: is_ok(response) and workflow_id in visible_ids,
            workflows,
            {"workflowId": workflow_id, "visibleIds": visible_ids},
        )
        add_result(results, "S01-API-013", "接收项目可读共享工作流详情", expect_ok, client.request("GET", f"/workflows/{workflow_id}", receiver_token, RECEIVER_PROJECT_ID), {"workflowId": workflow_id})
        export_response = client.request("GET", "/exports/project", receiver_token, RECEIVER_PROJECT_ID)
        export_ids = [str(item.get("id")) for item in ((data(export_response) or {}).get("workflows") or [])]
        add_result(
            results,
            "S01-API-014",
            "接收项目导出不包含共享工作流",
            lambda response: is_ok(response) and workflow_id not in export_ids,
            export_response,
            {"exportWorkflowIds": export_ids},
        )
        add_result(results, "S01-API-015", "接收项目禁止发布共享工作流", expect_forbidden, client.request("POST", f"/workflows/{workflow_id}/publish", receiver_token, RECEIVER_PROJECT_ID), {"workflowId": workflow_id})
        edit_payload = dict(workflow_fixture["payload"])
        edit_payload["definitionId"] = int(workflow_id)
        edit_payload["name"] = "长期回归-S01接收项目越权编辑探针"
        add_result(results, "S01-API-016", "接收项目禁止编辑共享工作流", expect_forbidden, client.request("POST", "/workflows", receiver_token, RECEIVER_PROJECT_ID, json_body=edit_payload), {"workflowId": workflow_id})
        add_result(results, "S01-API-017", "接收项目禁止删除共享工作流", expect_forbidden, client.request("DELETE", f"/workflows/{workflow_id}", receiver_token, RECEIVER_PROJECT_ID), {"workflowId": workflow_id})
        before = client.request("GET", "/runs", receiver_token, RECEIVER_PROJECT_ID, params={"workflowDefinitionId": workflow_id, "includeRunRecords": "true"})
        before_count = len(((data(before) or {}).get("queuedTasks") or [])) + len(((data(before) or {}).get("runRecords") or [])) if is_ok(before) else None
        trigger_response = client.request("POST", f"/workflows/{workflow_id}/trigger", receiver_token, RECEIVER_PROJECT_ID)
        time.sleep(3)
        after = client.request("GET", "/runs", receiver_token, RECEIVER_PROJECT_ID, params={"workflowDefinitionId": workflow_id, "includeRunRecords": "true"})
        after_count = len(((data(after) or {}).get("queuedTasks") or [])) + len(((data(after) or {}).get("runRecords") or [])) if is_ok(after) else None
        add_result(
            results,
            "S01-API-018",
            "接收项目触发共享工作流边界",
            lambda response: is_ok(response) and after_count is not None and before_count is not None and after_count >= before_count,
            trigger_response,
            {
                "workflowId": workflow_id,
                "beforeRuns": before_count,
                "afterRuns": after_count,
                "semantic": "共享工作流可由接收项目执行，运行记录留在接收项目",
            },
            need_fix=False,
        )
        source_run_id = "2068295159927992321"
        add_result(results, "S01-API-019", "接收项目禁止读取源项目运行详情", expect_not_found, client.request("GET", f"/runs/{source_run_id}", receiver_token, RECEIVER_PROJECT_ID), {"sourceRunId": source_run_id})
        add_result(results, "S01-API-020", "接收项目禁止下载源项目运行日志", expect_not_found, client.request("GET", f"/runs/{source_run_id}/log/download", receiver_token, RECEIVER_PROJECT_ID), {"sourceRunId": source_run_id})
        for index, (domain, log_id) in enumerate(
            (
                ("data-services", "2068123044574588930"),
                ("data-ingestion-services", "2068143682064400385"),
                ("protocol-conversions", "2068149627049918466"),
            ),
            21,
        ):
            add_result(
                results,
                f"S01-API-{index:03d}",
                f"接收项目禁止下载源项目{domain}调用日志",
                expect_not_found,
                client.request("GET", f"/invocation-logs/{domain}/{log_id}/download", receiver_token, RECEIVER_PROJECT_ID),
                {"domain": domain, "accessLogId": log_id},
            )
    else:
        results.append({"case": "S01-API-012..023", "module": "接收项目共享边界", "actual": {"receiver": summarize(receiver_login), "workflowId": workflow_id}, "result": "BLOCKED", "needFix": False})

    if cross_token:
        add_result(
            results,
            "S01-API-024",
            "跨项目成员不能直接使用长期项目上下文",
            expect_forbidden_or_unauth,
            client.request("GET", "/system/project-members", cross_token, LONG_PROJECT_ID, params={"projectId": LONG_PROJECT_ID}),
            {"requestedProject": LONG_PROJECT_ID, "memberProject": RECEIVER_PROJECT_ID},
        )
        cross_projects = client.request("GET", "/system/projects", cross_token, RECEIVER_PROJECT_ID)
        cross_project_ids = [str(item.get("id")) for item in (data(cross_projects) or [])]
        add_result(
            results,
            "S01-API-025",
            "跨项目成员项目列表仅接收项目",
            lambda response: is_ok(response) and cross_project_ids == [RECEIVER_PROJECT_ID],
            cross_projects,
            {"projectIds": cross_project_ids},
        )
    else:
        results.append({"case": "S01-API-024..025", "module": "跨项目成员", "actual": summarize(cross_login), "result": "BLOCKED", "needFix": False})

    output = {
        "executedAt": datetime.now().isoformat(timespec="seconds"),
        "fixtures": {
            "workerBinding": summarize(worker_binding),
            "users": fixture_users,
            "workflow": workflow_fixture,
            "logins": {
                "member": summarize(member_login),
                "receiver": summarize(receiver_login),
                "cross": summarize(cross_login),
                "disabled": summarize(disabled_login),
            },
        },
        "results": results,
        "stats": {
            "PASS": sum(1 for item in results if item["result"] == "PASS"),
            "FAIL": sum(1 for item in results if item["result"] == "FAIL"),
            "BLOCKED": sum(1 for item in results if item["result"] == "BLOCKED"),
            "needFix": sum(1 for item in results if item.get("needFix")),
        },
        "health": read_health(),
    }
    print(json.dumps(output, ensure_ascii=False, indent=2, default=str))
    return 1 if output["stats"]["FAIL"] or output["stats"]["needFix"] else 0


if __name__ == "__main__":
    sys.exit(main())

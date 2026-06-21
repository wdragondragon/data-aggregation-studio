#!/usr/bin/env python
"""Studio S18 follow target ACL probe.

This probe preserves long-term data. It creates or reuses a traceable workflow
fixture and never deletes long-term Studio objects.
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

WORKFLOW_CODE = "lt_reg_s18_follow_acl_workflow"
NONEXISTENT_WORKFLOW_ID = "2099999999999999999"
NONEXISTENT_WORKFLOW_RUN_ID = "2099999999999999998"
FINAL_STATUSES = {"SUCCESS", "FAILED", "CANCELED", "TERMINATED", "SKIPPED"}


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
        timeout: int = 30,
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

    def login(self, username: str, password: str, project_id: str) -> tuple[str | None, dict[str, Any]]:
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


def response_code(response: dict[str, Any]) -> str | None:
    body = response.get("body")
    return body.get("code") if isinstance(body, dict) else None


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def is_ok(response: dict[str, Any]) -> bool:
    return (
        response.get("status") == 200
        and isinstance(response.get("body"), dict)
        and response["body"].get("success") is True
    )


def expect_ok(response: dict[str, Any]) -> bool:
    return is_ok(response)


def expect_not_found(response: dict[str, Any]) -> bool:
    return response.get("status") == 404 and response_code(response) == "NOT_FOUND"


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
            for key in ("id", "code", "name", "status", "workflowRunId", "following"):
                if payload.get(key) is not None:
                    result[f"data.{key}"] = payload.get(key)
            if isinstance(payload.get("items"), list):
                result["itemCount"] = len(payload.get("items"))
        return result
    return {"status": response.get("status"), "body": str(body)[:300]}


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


def add_blocked(results: list[dict[str, Any]], case_id: str, module: str, reason: str, extra: dict[str, Any] | None = None) -> None:
    results.append(
        {
            "case": case_id,
            "module": module,
            "actual": {"reason": reason},
            "result": "BLOCKED",
            "needFix": False,
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
            health[key] = {"status": response.status_code, "body": value}
        except Exception as exc:
            health[key] = {"error": str(exc)}
    return health


def workflow_payload(existing: dict[str, Any] | None, batch: str) -> dict[str, Any]:
    return {
        "definitionId": existing.get("id") if existing else None,
        "code": WORKFLOW_CODE,
        "name": "长期回归-S18关注目标ACL流程",
        "schedule": {"enabled": False, "timezone": "Asia/Shanghai", "cronExpression": None},
        "nodes": [
            {
                "nodeCode": "s18_http_health",
                "nodeName": f"长期回归-S18关注健康检查节点-{batch}",
                "nodeType": "HTTP",
                "config": {"method": "GET", "url": "http://127.0.0.1:18080/actuator/health"},
                "fieldMappings": [],
            }
        ],
        "edges": [],
    }


def ensure_workflow_fixture(client: StudioClient, token: str, batch: str) -> dict[str, Any]:
    workflows_response = client.request("GET", "/workflows", token, LONG_PROJECT_ID)
    workflows = response_data(workflows_response) or []
    existing = next((item for item in workflows if isinstance(item, dict) and item.get("code") == WORKFLOW_CODE), None)
    save_response = client.request(
        "POST",
        "/workflows",
        token,
        LONG_PROJECT_ID,
        json_body=workflow_payload(existing, batch),
        timeout=45,
    )
    workflow = response_data(save_response) if is_ok(save_response) else existing
    workflow_id = str(workflow.get("id")) if isinstance(workflow, dict) and workflow.get("id") else None
    publish_response = (
        client.request("POST", f"/workflows/{workflow_id}/publish", token, LONG_PROJECT_ID, timeout=45)
        if workflow_id
        else None
    )
    share_response = (
        client.request(
            "POST",
            "/system/resource-shares",
            token,
            LONG_PROJECT_ID,
            json_body={
                "sourceProjectId": int(LONG_PROJECT_ID),
                "targetProjectId": int(RECEIVER_PROJECT_ID),
                "resourceType": "WORKFLOW",
                "resourceId": int(workflow_id),
                "enabled": 1,
            },
            timeout=45,
        )
        if workflow_id
        else None
    )
    return {
        "workflowId": workflow_id,
        "list": summarize(workflows_response),
        "save": summarize(save_response),
        "publish": summarize(publish_response),
        "share": summarize(share_response),
    }


def workflow_run_ids(client: StudioClient, token: str, workflow_id: str) -> set[str]:
    response = client.request(
        "GET",
        "/workflow-runs",
        token,
        LONG_PROJECT_ID,
        params={"workflowDefinitionId": workflow_id, "pageNo": 1, "pageSize": 20},
        timeout=20,
    )
    return {str(item.get("workflowRunId")) for item in ((response_data(response) or {}).get("items") or [])}


def wait_for_new_workflow_run(
    client: StudioClient,
    token: str,
    workflow_id: str,
    before_ids: set[str],
    timeout_seconds: int = 120,
) -> dict[str, Any] | None:
    deadline = time.time() + timeout_seconds
    latest: dict[str, Any] | None = None
    while time.time() < deadline:
        response = client.request(
            "GET",
            "/workflow-runs",
            token,
            LONG_PROJECT_ID,
            params={"workflowDefinitionId": workflow_id, "pageNo": 1, "pageSize": 10},
            timeout=20,
        )
        records = (response_data(response) or {}).get("items") or []
        for record in records:
            record_id = str(record.get("workflowRunId"))
            if record_id not in before_ids:
                latest = record
                if str(record.get("status")) in FINAL_STATUSES:
                    return record
        time.sleep(3)
    return latest


def main() -> int:
    client = StudioClient()
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    results: list[dict[str, Any]] = []

    admin_token, admin_login = client.login(ADMIN_USER, ADMIN_PASSWORD, LONG_PROJECT_ID)
    receiver_token, receiver_login = client.login("lt_reg_s01_receiver_member", S01_PASSWORD, RECEIVER_PROJECT_ID)
    if not admin_token or not receiver_token:
        print(json.dumps({
            "fatal": "login failed",
            "adminLogin": summarize(admin_login),
            "receiverLogin": summarize(receiver_login),
        }, ensure_ascii=False, indent=2))
        return 2

    fixture = ensure_workflow_fixture(client, admin_token, batch)
    workflow_id = fixture.get("workflowId")
    if not workflow_id:
        add_blocked(results, "S18-FOLLOW-001", "准备共享工作流", "workflow fixture not available", fixture)
    else:
        receiver_workflow_detail = client.request("GET", f"/workflows/{workflow_id}", receiver_token, RECEIVER_PROJECT_ID)
        add_result(
            results,
            "S18-FOLLOW-001",
            "接收项目成员可读取共享工作流",
            expect_ok,
            receiver_workflow_detail,
            {"workflowId": workflow_id, "batch": batch, "fixture": fixture},
            need_fix=False,
        )
        follow_shared = client.request(
            "POST",
            "/follows",
            receiver_token,
            RECEIVER_PROJECT_ID,
            json_body={"targetType": "WORKFLOW", "targetId": int(workflow_id)},
        )
        add_result(
            results,
            "S18-FOLLOW-002",
            "接收项目成员可关注共享工作流",
            lambda response: is_ok(response) and (response_data(response) or {}).get("following") is True,
            follow_shared,
            {"workflowId": workflow_id, "batch": batch},
        )

        before = workflow_run_ids(client, admin_token, workflow_id)
        trigger_response = client.request("POST", f"/workflows/{workflow_id}/trigger", admin_token, LONG_PROJECT_ID, timeout=45)
        new_run = wait_for_new_workflow_run(client, admin_token, workflow_id, before) if is_ok(trigger_response) else None
        if new_run and new_run.get("workflowRunId"):
            workflow_run_id = str(new_run.get("workflowRunId"))
            add_result(
                results,
                "S18-FOLLOW-003",
                "管理员可关注本项目工作流运行",
                lambda response: is_ok(response) and (response_data(response) or {}).get("following") is True,
                client.request(
                    "POST",
                    "/follows",
                    admin_token,
                    LONG_PROJECT_ID,
                    json_body={"targetType": "WORKFLOW_RUN", "targetId": int(workflow_run_id)},
                ),
                {"workflowRunId": workflow_run_id, "trigger": summarize(trigger_response), "run": new_run},
            )
            add_result(
                results,
                "S18-FOLLOW-004",
                "接收项目成员不可读取源项目工作流运行详情",
                expect_not_found,
                client.request("GET", f"/workflow-runs/{workflow_run_id}", receiver_token, RECEIVER_PROJECT_ID),
                {"workflowRunId": workflow_run_id},
                need_fix=False,
            )
            add_result(
                results,
                "S18-FOLLOW-005",
                "接收项目成员不可关注不可读工作流运行",
                expect_not_found,
                client.request(
                    "POST",
                    "/follows",
                    receiver_token,
                    RECEIVER_PROJECT_ID,
                    json_body={"targetType": "WORKFLOW_RUN", "targetId": int(workflow_run_id)},
                ),
                {"workflowRunId": workflow_run_id},
            )
            add_result(
                results,
                "S18-FOLLOW-006",
                "关注状态接口不可探测不可读工作流运行",
                expect_not_found,
                client.request(
                    "GET",
                    "/follows/status",
                    receiver_token,
                    RECEIVER_PROJECT_ID,
                    params={"targetType": "WORKFLOW_RUN", "targetId": workflow_run_id},
                ),
                {"workflowRunId": workflow_run_id},
            )
        else:
            add_blocked(
                results,
                "S18-FOLLOW-003..006",
                "工作流运行关注 ACL",
                "workflow trigger did not produce a workflowRunId in time",
                {"trigger": summarize(trigger_response), "latestRun": new_run, "workflowId": workflow_id},
            )

    add_result(
        results,
        "S18-FOLLOW-007",
        "接收项目成员不可关注不存在工作流",
        expect_not_found,
        client.request(
            "POST",
            "/follows",
            receiver_token,
            RECEIVER_PROJECT_ID,
            json_body={"targetType": "WORKFLOW", "targetId": int(NONEXISTENT_WORKFLOW_ID)},
        ),
        {"targetId": NONEXISTENT_WORKFLOW_ID},
    )
    add_result(
        results,
        "S18-FOLLOW-008",
        "接收项目成员不可关注不存在工作流运行",
        expect_not_found,
        client.request(
            "POST",
            "/follows",
            receiver_token,
            RECEIVER_PROJECT_ID,
            json_body={"targetType": "WORKFLOW_RUN", "targetId": int(NONEXISTENT_WORKFLOW_RUN_ID)},
        ),
        {"targetId": NONEXISTENT_WORKFLOW_RUN_ID},
    )
    add_result(
        results,
        "S18-FOLLOW-009",
        "取消关注不存在目标保持幂等可清理",
        expect_ok,
        client.request(
            "DELETE",
            "/follows",
            receiver_token,
            RECEIVER_PROJECT_ID,
            params={"targetType": "WORKFLOW", "targetId": NONEXISTENT_WORKFLOW_ID},
        ),
        {"targetId": NONEXISTENT_WORKFLOW_ID, "semantic": "用于兼容修复前遗留或分享撤销后的个人关注记录清理"},
        need_fix=False,
    )

    output = {
        "executedAt": datetime.now().isoformat(timespec="seconds"),
        "batch": batch,
        "fixtures": {
            "workflowCode": WORKFLOW_CODE,
            "workflowId": workflow_id,
            "adminLogin": summarize(admin_login),
            "receiverLogin": summarize(receiver_login),
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

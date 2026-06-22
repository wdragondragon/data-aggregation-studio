#!/usr/bin/env python
"""Studio S20 shared follow notification probe.

This probe preserves long-term data. It creates a traceable shared workflow,
lets the receiver project member follow it, verifies workflow-run notification
fan-out while the share is enabled, then disables the share and verifies the
old follow no longer receives new workflow-run notifications.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime
from typing import Any, Callable

import pymysql
import requests


BASE_URL = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
RECEIVER_PROJECT_ID = os.getenv("STUDIO_RECEIVER_PROJECT_ID", "2068301893337849857")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")
RECEIVER_USER = os.getenv("STUDIO_RECEIVER_USER", "lt_reg_s01_receiver_member")
RECEIVER_PASSWORD = os.getenv("STUDIO_RECEIVER_PASSWORD", "LtRegS01!2026")
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")
NACOS_READINESS = os.getenv(
    "STUDIO_NACOS_READINESS",
    "http://127.0.0.1:8848/nacos/v1/console/health/readiness",
)

MYSQL_HOST = os.getenv("STUDIO_META_MYSQL_HOST", "8.140.247.113")
MYSQL_PORT = int(os.getenv("STUDIO_META_MYSQL_PORT", "13309"))
MYSQL_USER = os.getenv("STUDIO_META_MYSQL_USER", "root")
MYSQL_PASSWORD = os.getenv("STUDIO_META_MYSQL_PASSWORD", "951753")
MYSQL_DB = os.getenv("STUDIO_META_MYSQL_DB", "data_aggregation_studio")

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
            headers["Authorization"] = "Bearer " + token
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
        if isinstance(response.get("body"), dict):
            token = (response["body"].get("data") or {}).get("token")
        return token, response


def mysql_conn() -> pymysql.Connection:
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=MYSQL_DB,
        charset="utf8mb4",
        connect_timeout=10,
        autocommit=True,
    )


def is_ok(response: dict[str, Any]) -> bool:
    return response.get("status") == 200 and isinstance(response.get("body"), dict) and response["body"].get("success") is True


def response_code(response: dict[str, Any]) -> str | None:
    body = response.get("body")
    return body.get("code") if isinstance(body, dict) else None


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def expect_ok(response: dict[str, Any]) -> bool:
    return is_ok(response)


def expect_not_found(response: dict[str, Any]) -> bool:
    return response.get("status") == 404 and response_code(response) == "NOT_FOUND"


def summarize(response: dict[str, Any] | None) -> dict[str, Any]:
    if response is None:
        return {"status": "SKIP"}
    body = response.get("body")
    if isinstance(body, dict):
        data = body.get("data")
        summary: dict[str, Any] = {
            "status": response.get("status"),
            "success": body.get("success"),
            "code": body.get("code"),
            "message": body.get("message"),
        }
        if isinstance(data, dict):
            for key in (
                "id",
                "code",
                "name",
                "status",
                "workflowRunId",
                "following",
                "currentProjectId",
                "targetProjectId",
                "resourceType",
                "resourceId",
                "enabled",
            ):
                if data.get(key) is not None:
                    summary["data." + key] = data.get(key)
        elif isinstance(data, list):
            summary["dataCount"] = len(data)
        return summary
    return {"status": response.get("status"), "body": str(body)[:300]}


def add_case(
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


def add_boolean_case(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    passed: bool,
    actual: dict[str, Any],
    need_fix: bool | None = None,
) -> None:
    results.append(
        {
            "case": case_id,
            "module": module,
            "actual": actual,
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
        }
    )


def read_health() -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, url in (("nacos", NACOS_READINESS), ("server", SERVER_HEALTH), ("worker", WORKER_HEALTH)):
        try:
            response = requests.get(url, timeout=10)
            try:
                body: Any = response.json()
            except Exception:
                body = response.text
            result[key] = {"status": response.status_code, "body": body}
        except Exception as exc:
            result[key] = {"error": str(exc)}
    return result


def user_id_by_username(username: str) -> str | None:
    with mysql_conn() as conn:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                "select id from sys_user where tenant_id = %s and username = %s and deleted = 0 limit 1",
                (TENANT_ID, username),
            )
            row = cur.fetchone()
    return str(row["id"]) if row else None


def notification_rows(recipient_user_id: str, workflow_run_id: str) -> list[dict[str, Any]]:
    with mysql_conn() as conn:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                """
                select id, recipient_user_id, category, title, target_type, target_id, target_path,
                       target_tenant_id, target_project_id, dedupe_key, created_at
                from studio_notification
                where recipient_user_id = %s
                  and category = 'WORKFLOW_RUN'
                  and target_type = 'WORKFLOW_RUN'
                  and target_id = %s
                order by created_at desc, id desc
                """,
                (recipient_user_id, workflow_run_id),
            )
            rows = cur.fetchall()
    return [dict(row) for row in rows]


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


def create_workflow_payload(batch: str) -> dict[str, Any]:
    return {
        "code": "lt_reg_s20_shared_follow_notice_" + batch,
        "name": "长期回归-S20共享关注通知一致性流程-" + batch,
        "schedule": {"enabled": False, "timezone": "Asia/Shanghai", "cronExpression": None},
        "nodes": [
            {
                "nodeCode": "s20_http_health",
                "nodeName": "长期回归-S20共享关注通知健康检查节点-" + batch,
                "nodeType": "HTTP",
                "config": {"method": "GET", "url": "http://127.0.0.1:18080/actuator/health"},
                "fieldMappings": [],
            }
        ],
        "edges": [],
    }


def trigger_and_wait(client: StudioClient, token: str, workflow_id: str) -> tuple[dict[str, Any], dict[str, Any] | None]:
    before = workflow_run_ids(client, token, workflow_id)
    trigger_response: dict[str, Any] | None = None
    for attempt in range(1, 7):
        trigger_response = client.request("POST", f"/workflows/{workflow_id}/trigger", token, LONG_PROJECT_ID, timeout=45)
        if is_ok(trigger_response):
            break
        body = trigger_response.get("body")
        message = body.get("message") if isinstance(body, dict) else ""
        if trigger_response.get("status") == 400 and "active run" in str(message).lower():
            time.sleep(5 * attempt)
            continue
        return trigger_response, None
    if trigger_response is None or not is_ok(trigger_response):
        return trigger_response or {"status": "SKIP", "body": "trigger not attempted"}, None
    new_run = wait_for_new_workflow_run(client, token, workflow_id, before)
    return trigger_response, new_run


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    client = StudioClient()
    results: list[dict[str, Any]] = []
    evidence: dict[str, Any] = {"batch": batch, "health": read_health()}

    admin_token, admin_login = client.login(ADMIN_USER, ADMIN_PASSWORD, LONG_PROJECT_ID)
    receiver_token, receiver_login = client.login(RECEIVER_USER, RECEIVER_PASSWORD, RECEIVER_PROJECT_ID)
    add_case(results, "S20-AUTH-001", "管理员登录", expect_ok, admin_login, need_fix=False)
    add_case(results, "S20-AUTH-002", "接收项目成员登录", expect_ok, receiver_login, need_fix=False)
    receiver_user_id = user_id_by_username(RECEIVER_USER)
    evidence["receiverUser"] = {"username": RECEIVER_USER, "userId": receiver_user_id}
    if not admin_token or not receiver_token or receiver_user_id is None:
        print(json.dumps({"batch": batch, "results": results, "evidence": evidence}, ensure_ascii=False, indent=2, default=str))
        return 1

    workflow_response = client.request(
        "POST",
        "/workflows",
        admin_token,
        LONG_PROJECT_ID,
        json_body=create_workflow_payload(batch),
        timeout=45,
    )
    workflow = response_data(workflow_response) if is_ok(workflow_response) else None
    workflow_id = str(workflow.get("id")) if isinstance(workflow, dict) and workflow.get("id") else None
    add_boolean_case(
        results,
        "S20-FIXTURE-001",
        "共享工作流夹具",
        workflow_id is not None,
        {"workflow": summarize(workflow_response)},
    )
    if workflow_id is None:
        print(json.dumps({"batch": batch, "results": results, "evidence": evidence}, ensure_ascii=False, indent=2, default=str))
        return 1
    evidence["workflowId"] = workflow_id

    publish_response = client.request("POST", f"/workflows/{workflow_id}/publish", admin_token, LONG_PROJECT_ID, timeout=45)
    add_case(results, "S20-FIXTURE-002", "发布共享工作流夹具", expect_ok, publish_response)

    share_body = {
        "sourceProjectId": int(LONG_PROJECT_ID),
        "targetProjectId": int(RECEIVER_PROJECT_ID),
        "resourceType": "WORKFLOW",
        "resourceId": int(workflow_id),
        "enabled": 1,
    }
    share_response = client.request(
        "POST",
        "/system/resource-shares",
        admin_token,
        LONG_PROJECT_ID,
        json_body=share_body,
        timeout=45,
    )
    share = response_data(share_response) if is_ok(share_response) else None
    share_id = str(share.get("id")) if isinstance(share, dict) and share.get("id") else None
    add_boolean_case(
        results,
        "S20-SHARE-001",
        "启用工作流共享",
        share_id is not None,
        {"share": summarize(share_response)},
    )
    if share_id is None:
        print(json.dumps({"batch": batch, "results": results, "evidence": evidence}, ensure_ascii=False, indent=2, default=str))
        return 1
    evidence["shareId"] = share_id

    receiver_workflow_detail = client.request("GET", f"/workflows/{workflow_id}", receiver_token, RECEIVER_PROJECT_ID)
    add_case(results, "S20-SHARE-002", "接收项目成员可读取启用共享工作流", expect_ok, receiver_workflow_detail)

    follow_response = client.request(
        "POST",
        "/follows",
        receiver_token,
        RECEIVER_PROJECT_ID,
        json_body={"targetType": "WORKFLOW", "targetId": int(workflow_id)},
    )
    add_case(
        results,
        "S20-FOLLOW-001",
        "接收项目成员可关注启用共享工作流",
        lambda response: is_ok(response) and (response_data(response) or {}).get("following") is True,
        follow_response,
    )

    trigger_response, active_run = trigger_and_wait(client, admin_token, workflow_id)
    active_run_id = str(active_run.get("workflowRunId")) if isinstance(active_run, dict) and active_run.get("workflowRunId") else None
    add_boolean_case(
        results,
        "S20-NOTIFY-001",
        "启用共享期间可生成工作流运行",
        active_run_id is not None,
        {"trigger": summarize(trigger_response), "run": active_run},
    )
    if active_run_id:
        rows = notification_rows(receiver_user_id, active_run_id)
        add_boolean_case(
            results,
            "S20-NOTIFY-002",
            "接收项目共享关注者应收到工作流运行通知",
            len(rows) == 1,
            {"receiverUserId": receiver_user_id, "workflowRunId": active_run_id, "notificationCount": len(rows), "rows": rows},
            need_fix=len(rows) != 1,
        )
        target_ok = len(rows) == 1 \
            and str(rows[0].get("target_project_id")) == str(RECEIVER_PROJECT_ID) \
            and rows[0].get("target_path") == f"/workflows/{workflow_id}"
        add_boolean_case(
            results,
            "S20-NOTIFY-003",
            "共享关注者通知应指向接收项目可读的共享工作流详情",
            target_ok,
            {
                "receiverProjectId": RECEIVER_PROJECT_ID,
                "expectedTargetPath": f"/workflows/{workflow_id}",
                "rows": rows,
            },
            need_fix=not target_ok,
        )
        run_detail_receiver = client.request("GET", f"/workflow-runs/{active_run_id}", receiver_token, RECEIVER_PROJECT_ID)
        add_case(
            results,
            "S20-NOTIFY-004",
            "源项目运行详情仍不向接收项目成员直接开放",
            expect_not_found,
            run_detail_receiver,
            need_fix=False,
        )

    disabled_share_body = dict(share_body)
    disabled_share_body["id"] = int(share_id)
    disabled_share_body["enabled"] = 0
    disable_share_response = client.request(
        "POST",
        "/system/resource-shares",
        admin_token,
        LONG_PROJECT_ID,
        json_body=disabled_share_body,
        timeout=45,
    )
    add_case(results, "S20-REVOKE-001", "禁用工作流共享", expect_ok, disable_share_response)
    receiver_after_disabled = client.request("GET", f"/workflows/{workflow_id}", receiver_token, RECEIVER_PROJECT_ID)
    add_case(
        results,
        "S20-REVOKE-002",
        "共享禁用后接收项目成员不可读取工作流",
        expect_not_found,
        receiver_after_disabled,
        need_fix=False,
    )

    trigger_after_disable_response, disabled_run = trigger_and_wait(client, admin_token, workflow_id)
    disabled_run_id = str(disabled_run.get("workflowRunId")) if isinstance(disabled_run, dict) and disabled_run.get("workflowRunId") else None
    add_boolean_case(
        results,
        "S20-REVOKE-003",
        "共享禁用后源项目仍可运行工作流",
        disabled_run_id is not None,
        {"trigger": summarize(trigger_after_disable_response), "run": disabled_run},
        need_fix=False,
    )
    if disabled_run_id:
        rows = notification_rows(receiver_user_id, disabled_run_id)
        add_boolean_case(
            results,
            "S20-REVOKE-004",
            "共享禁用后的旧关注不应收到新运行通知",
            len(rows) == 0,
            {"receiverUserId": receiver_user_id, "workflowRunId": disabled_run_id, "notificationCount": len(rows), "rows": rows},
            need_fix=len(rows) != 0,
        )

    summary = {
        "batch": batch,
        "pass": sum(1 for result in results if result["result"] == "PASS"),
        "fail": sum(1 for result in results if result["result"] == "FAIL"),
        "blocked": sum(1 for result in results if result["result"] == "BLOCKED"),
        "needFix": sum(1 for result in results if result.get("needFix")),
        "results": results,
        "evidence": evidence,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2, default=str))
    return 0 if summary["fail"] == 0 and summary["needFix"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

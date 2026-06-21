#!/usr/bin/env python
"""Studio S19 notification fan-out ACL probe.

This probe preserves long-term data. Each run creates a traceable S19 workflow
fixture, shares it to the receiver project, and verifies disabled users do not
receive new resource-share notifications.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime
from typing import Any

import pymysql
import requests


BASE_URL = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
RECEIVER_PROJECT_ID = os.getenv("STUDIO_RECEIVER_PROJECT_ID", "2068301893337849857")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")
S19_DISABLED_USER = os.getenv("STUDIO_S19_DISABLED_USER", "lt_reg_s19_disabled_notice_guard")
S19_DISABLED_DISPLAY = os.getenv("STUDIO_S19_DISABLED_DISPLAY", "长期回归-S19通知禁用账号收件边界")
S19_DISABLED_PASSWORD = os.getenv("STUDIO_S19_DISABLED_PASSWORD", "LtRegS19!2026")
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")
NACOS_READINESS = os.getenv("STUDIO_NACOS_READINESS", "http://127.0.0.1:8848/nacos/v1/console/health/readiness")

MYSQL_HOST = os.getenv("STUDIO_META_MYSQL_HOST", "8.140.247.113")
MYSQL_PORT = int(os.getenv("STUDIO_META_MYSQL_PORT", "13309"))
MYSQL_USER = os.getenv("STUDIO_META_MYSQL_USER", "root")
MYSQL_PASSWORD = os.getenv("STUDIO_META_MYSQL_PASSWORD", "951753")
MYSQL_DB = os.getenv("STUDIO_META_MYSQL_DB", "data_aggregation_studio")


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


def is_ok(response: dict[str, Any]) -> bool:
    return response.get("status") == 200 and isinstance(response.get("body"), dict) and response["body"].get("success") is True


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


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
            for key in ("id", "username", "displayName", "enabled", "projectId", "status", "name", "code"):
                if data.get(key) is not None:
                    summary["data." + key] = data.get(key)
        elif isinstance(data, list):
            summary["dataCount"] = len(data)
        return summary
    return {"status": response.get("status"), "body": str(body)[:300]}


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


def notification_count(user_id: str, workflow_id: str) -> tuple[int, list[dict[str, Any]]]:
    with mysql_conn() as conn:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                """
                select id, recipient_user_id, category, title, target_type, target_id, target_project_id, dedupe_key, created_at
                from studio_notification
                where recipient_user_id = %s
                  and category = 'RESOURCE_SHARE'
                  and target_type = 'WORKFLOW'
                  and target_id = %s
                order by created_at desc, id desc
                """,
                (user_id, workflow_id),
            )
            rows = cur.fetchall()
    return len(rows), rows


def ensure_disabled_user(client: StudioClient, admin_token: str) -> dict[str, Any]:
    users_response = client.request("GET", "/users", admin_token, LONG_PROJECT_ID)
    if not is_ok(users_response):
        raise RuntimeError("Failed to list users: " + json.dumps(summarize(users_response), ensure_ascii=False))
    users = response_data(users_response) or []
    existing = next((user for user in users if isinstance(user, dict) and user.get("username") == S19_DISABLED_USER), None)
    if existing:
        response = client.request(
            "POST",
            "/users",
            admin_token,
            LONG_PROJECT_ID,
            json_body={
                "id": existing.get("id"),
                "username": S19_DISABLED_USER,
                "displayName": S19_DISABLED_DISPLAY,
                "enabled": 0,
            },
        )
    else:
        response = client.request(
            "POST",
            "/users",
            admin_token,
            LONG_PROJECT_ID,
            json_body={
                "username": S19_DISABLED_USER,
                "displayName": S19_DISABLED_DISPLAY,
                "passwordHash": S19_DISABLED_PASSWORD,
                "enabled": 0,
            },
        )
    if not is_ok(response):
        raise RuntimeError("Failed to ensure disabled user: " + json.dumps(summarize(response), ensure_ascii=False))
    return response_data(response)


def workflow_payload(batch: str) -> dict[str, Any]:
    return {
        "code": "lt_reg_s19_notification_fanout_" + batch,
        "name": "长期回归-S19通知收件边界流程-" + batch,
        "schedule": {"enabled": False, "timezone": "Asia/Shanghai", "cronExpression": None},
        "nodes": [
            {
                "nodeCode": "s19_health_probe",
                "nodeName": "长期回归-S19通知收件边界健康检查节点-" + batch,
                "nodeType": "HTTP",
                "config": {"method": "GET", "url": "http://127.0.0.1:18080/actuator/health"},
                "fieldMappings": [],
            }
        ],
        "edges": [],
    }


def add_case(results: list[dict[str, Any]], case_id: str, module: str, passed: bool, actual: dict[str, Any], need_fix: bool | None = None) -> None:
    results.append(
        {
            "case": case_id,
            "module": module,
            "actual": actual,
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
        }
    )


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    client = StudioClient()
    results: list[dict[str, Any]] = []
    evidence: dict[str, Any] = {"batch": batch, "health": read_health()}

    token, login_response = client.login(ADMIN_USER, ADMIN_PASSWORD, LONG_PROJECT_ID)
    add_case(results, "S19-AUTH-001", "认证", token is not None and is_ok(login_response), summarize(login_response))
    if token is None:
        print(json.dumps({"batch": batch, "results": results, "evidence": evidence}, ensure_ascii=False, indent=2))
        return 1

    disabled_user = ensure_disabled_user(client, token)
    disabled_user_id = str(disabled_user.get("id"))
    evidence["disabledUser"] = disabled_user
    disabled_login_token, disabled_login_response = client.login(S19_DISABLED_USER, S19_DISABLED_PASSWORD, RECEIVER_PROJECT_ID)
    add_case(
        results,
        "S19-USER-001",
        "禁用用户",
        disabled_login_token is None and disabled_login_response.get("status") == 401,
        summarize(disabled_login_response),
        need_fix=False,
    )

    member_response = client.request(
        "POST",
        "/system/project-members",
        token,
        RECEIVER_PROJECT_ID,
        json_body={
            "projectId": int(RECEIVER_PROJECT_ID),
            "userId": int(disabled_user_id),
            "roleCode": "PROJECT_MEMBER",
            "status": "ACTIVE",
        },
    )
    add_case(results, "S19-MEMBER-001", "项目成员", is_ok(member_response), summarize(member_response))

    workflow_response = client.request("POST", "/workflows", token, LONG_PROJECT_ID, json_body=workflow_payload(batch), timeout=45)
    workflow = response_data(workflow_response) if is_ok(workflow_response) else None
    workflow_id = str(workflow.get("id")) if isinstance(workflow, dict) and workflow.get("id") else None
    add_case(results, "S19-FIXTURE-001", "工作流夹具", workflow_id is not None, summarize(workflow_response))
    if workflow_id is None:
        print(json.dumps({"batch": batch, "results": results, "evidence": evidence}, ensure_ascii=False, indent=2, default=str))
        return 1
    evidence["workflowId"] = workflow_id

    before_count, before_rows = notification_count(disabled_user_id, workflow_id)
    share_response = client.request(
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
    add_case(results, "S19-SHARE-001", "资源共享", is_ok(share_response), summarize(share_response))
    time.sleep(1)
    after_count, after_rows = notification_count(disabled_user_id, workflow_id)
    evidence["notificationRowsBefore"] = before_rows
    evidence["notificationRowsAfter"] = after_rows
    add_case(
        results,
        "S19-NOTIFY-001",
        "通知Fanout",
        after_count == before_count,
        {
            "disabledUserId": disabled_user_id,
            "workflowId": workflow_id,
            "before": before_count,
            "after": after_count,
            "newRows": max(0, after_count - before_count),
        },
        need_fix=after_count > before_count,
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

#!/usr/bin/env python
"""Studio S17 datasource current-form ACL probe.

This probe preserves long-term data. It does not clean the long-term project,
database, or existing Studio objects.
"""

from __future__ import annotations

import json
import os
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
DATASOURCE_ID = os.getenv("STUDIO_LONG_DATASOURCE_ID", "2068077811652583425")
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


def data(response: dict[str, Any]) -> Any:
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


def expect_forbidden(response: dict[str, Any]) -> bool:
    return response.get("status") == 403 and response_code(response) == "FORBIDDEN"


def expect_connection_success(response: dict[str, Any]) -> bool:
    payload = data(response) or {}
    return is_ok(response) and payload.get("success") is True and payload.get("status") == "AVAILABLE"


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
            for key in ("id", "name", "typeCode", "status", "success", "message"):
                if payload.get(key) is not None:
                    result[f"data.{key}"] = payload.get(key)
            if isinstance(payload.get("models"), list):
                result["modelCount"] = len(payload.get("models"))
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


def current_form_payload(datasource: dict[str, Any]) -> dict[str, Any]:
    metadata = dict(datasource.get("technicalMetadata") or {})
    metadata.pop("password", None)
    return {
        "id": int(DATASOURCE_ID),
        "name": "长期回归-S17跨项目借用连接测试",
        "typeCode": datasource.get("typeCode") or "mysql8",
        "enabled": True,
        "executable": True,
        "technicalMetadata": metadata,
        "businessMetadata": {},
    }


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

    admin_ds = client.request("GET", f"/datasources/{DATASOURCE_ID}", admin_token, LONG_PROJECT_ID)
    add_result(results, "S17-DS-001", "管理员读取长期数据源基线", expect_ok, admin_ds,
               {"datasourceId": DATASOURCE_ID})
    datasource = data(admin_ds) or {}

    receiver_list = client.request("GET", "/datasources", receiver_token, RECEIVER_PROJECT_ID)
    receiver_ids = [str(item.get("id")) for item in (data(receiver_list) or []) if isinstance(item, dict)]
    add_result(
        results,
        "S17-DS-002",
        "接收项目数据源列表不包含长期项目私有数据源",
        lambda response: is_ok(response) and str(DATASOURCE_ID) not in receiver_ids,
        receiver_list,
        {"receiverVisibleDatasourceIds": receiver_ids[:20]},
    )

    add_result(
        results,
        "S17-DS-003",
        "接收项目成员禁止读取长期项目私有数据源",
        expect_not_found,
        client.request("GET", f"/datasources/{DATASOURCE_ID}", receiver_token, RECEIVER_PROJECT_ID),
    )
    add_result(
        results,
        "S17-DS-004",
        "接收项目成员保存态连接测试禁止访问长期项目私有数据源",
        expect_not_found,
        client.request("POST", f"/datasources/{DATASOURCE_ID}/test", receiver_token, RECEIVER_PROJECT_ID, timeout=45),
    )
    add_result(
        results,
        "S17-DS-005",
        "接收项目成员禁止发现长期项目私有数据源模型",
        expect_not_found,
        client.request("POST", f"/datasources/{DATASOURCE_ID}/discover", receiver_token, RECEIVER_PROJECT_ID,
                       params={"pageNo": 1, "pageSize": 2}, timeout=45),
    )

    current_form = client.request(
        "POST",
        "/datasources/test",
        receiver_token,
        RECEIVER_PROJECT_ID,
        json_body=current_form_payload(datasource),
        timeout=45,
    )
    add_result(
        results,
        "S17-DS-006",
        "接收项目成员禁止通过当前表单测试借用长期项目数据源密码",
        expect_forbidden,
        current_form,
        {"payloadOmittedPassword": True, "batch": batch},
    )

    admin_current_form = client.request(
        "POST",
        "/datasources/test",
        admin_token,
        LONG_PROJECT_ID,
        json_body=current_form_payload(datasource),
        timeout=45,
    )
    add_result(
        results,
        "S17-DS-007",
        "管理员当前表单测试仍可保留本项目敏感字段",
        expect_connection_success,
        admin_current_form,
        {"payloadOmittedPassword": True, "batch": batch},
    )

    summary = {
        "batch": batch,
        "health": read_health(),
        "results": results,
        "summary": {
            "pass": sum(1 for item in results if item["result"] == "PASS"),
            "fail": sum(1 for item in results if item["result"] == "FAIL"),
            "blocked": sum(1 for item in results if item["result"] == "BLOCKED"),
            "needFix": sum(1 for item in results if item.get("needFix")),
        },
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if summary["summary"]["fail"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

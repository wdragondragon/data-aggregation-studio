#!/usr/bin/env python
"""Studio S16 ACL deep probe.

This probe preserves long-term data. It creates traceable metadata schemas with
lt_reg_s16_acl prefixes and never deletes long-term test objects.
"""

from __future__ import annotations

import json
import os
import sys
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
            if payload.get("id") is not None:
                result["id"] = str(payload.get("id"))
            if payload.get("schemaCode") is not None:
                result["schemaCode"] = payload.get("schemaCode")
            if payload.get("status") is not None:
                result["dataStatus"] = payload.get("status")
        return result
    return {"status": response.get("status"), "body": str(body)[:300]}


def expect_ok(response: dict[str, Any]) -> bool:
    return is_ok(response)


def expect_forbidden(response: dict[str, Any]) -> bool:
    return response.get("status") == 403 and response_code(response) == "FORBIDDEN"


def expect_unauthorized(response: dict[str, Any]) -> bool:
    return response.get("status") == 401 and response_code(response) == "UNAUTHORIZED"


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


def schema_payload(batch: str) -> dict[str, Any]:
    schema_code = f"business:lt_reg_s16_acl:{batch}"
    return {
        "schemaCode": schema_code,
        "schemaName": f"长期回归-S16客户经营画像元模型-{batch}",
        "objectType": "business",
        "typeCode": f"lt_reg_s16_acl.customer_profile_{batch}",
        "description": (
            'META_MODEL_CONFIG:{"domain":"BUSINESS","directoryCode":"lt_reg_s16_acl",'
            '"directoryName":"客户经营画像","metaModelCode":"customer_profile",'
            '"metaModelName":"客户经营画像元模型","displayMode":"SINGLE","required":false}'
        ),
        "fields": [
            {
                "fieldKey": "customerSegment",
                "fieldName": "客户分层",
                "scope": "BUSINESS",
                "valueType": "STRING",
                "componentType": "INPUT",
                "required": False,
                "sensitive": False,
                "sortOrder": 1,
                "searchable": True,
                "sortable": True,
                "queryOperators": ["EQ", "LIKE"],
                "queryDefaultOperator": "LIKE",
            }
        ],
    }


def contains_schema(response: dict[str, Any], schema_code: str) -> bool:
    items = data(response) or []
    return any(isinstance(item, dict) and item.get("schemaCode") == schema_code for item in items)


def main() -> int:
    client = StudioClient()
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    payload = schema_payload(batch)
    schema_code = payload["schemaCode"]

    admin_token, admin_login = client.login(ADMIN_USER, ADMIN_PASSWORD, LONG_PROJECT_ID)
    if not admin_token:
        print(json.dumps({"fatal": "admin login failed", "login": summarize(admin_login)}, ensure_ascii=False, indent=2))
        return 2

    member_token, member_login = client.login("lt_reg_s01_long_project_member", S01_PASSWORD, LONG_PROJECT_ID)
    receiver_token, receiver_login = client.login("lt_reg_s01_receiver_member", S01_PASSWORD, RECEIVER_PROJECT_ID)

    results: list[dict[str, Any]] = []
    unauth_draft = client.request("POST", "/meta-schemas/draft", None, LONG_PROJECT_ID, json_body=payload)
    add_result(results, "S16-ACL-001", "元模型未登录写入保护", expect_unauthorized, unauth_draft)

    admin_draft = client.request("POST", "/meta-schemas/draft", admin_token, LONG_PROJECT_ID, json_body=payload)
    schema_id = str((data(admin_draft) or {}).get("id") or "")
    add_result(results, "S16-ACL-002", "管理员创建全局业务元模型", expect_ok, admin_draft, {"schemaCode": schema_code})

    admin_publish = client.request("POST", f"/meta-schemas/{schema_id}/publish", admin_token, LONG_PROJECT_ID) if schema_id else None
    if admin_publish is None:
        results.append({"case": "S16-ACL-003", "module": "管理员发布全局业务元模型", "actual": {"status": "SKIP"}, "result": "BLOCKED", "needFix": False})
    else:
        add_result(results, "S16-ACL-003", "管理员发布全局业务元模型", expect_ok, admin_publish, {"schemaId": schema_id})

    if member_token:
        member_list = client.request("GET", "/meta-schemas", member_token, LONG_PROJECT_ID, params={"includeFields": "false"})
        add_result(results, "S16-ACL-004", "普通成员可读取元模型列表", expect_ok, member_list)
        add_result(results, "S16-ACL-005", "普通成员禁止创建元模型草稿", expect_forbidden,
                   client.request("POST", "/meta-schemas/draft", member_token, LONG_PROJECT_ID, json_body=payload))
        add_result(results, "S16-ACL-006", "普通成员禁止发布元模型", expect_forbidden,
                   client.request("POST", f"/meta-schemas/{schema_id}/publish", member_token, LONG_PROJECT_ID), {"schemaId": schema_id})
        add_result(results, "S16-ACL-007", "普通成员禁止同步指定技术元模型", expect_forbidden,
                   client.request("POST", "/meta-schemas/technical/sync/mysql8", member_token, LONG_PROJECT_ID))
        add_result(results, "S16-ACL-008", "普通成员禁止同步全部技术元模型", expect_forbidden,
                   client.request("POST", "/meta-schemas/technical/sync-all", member_token, LONG_PROJECT_ID))
        add_result(results, "S16-ACL-009", "普通成员禁止同步运行参数元模型", expect_forbidden,
                   client.request("POST", "/meta-schemas/runtime-options/sync-standard", member_token, LONG_PROJECT_ID))
        add_result(results, "S16-ACL-010", "普通成员禁止删除元模型", expect_forbidden,
                   client.request("DELETE", f"/meta-schemas/{schema_id}", member_token, LONG_PROJECT_ID), {"schemaId": schema_id})
    else:
        results.append({"case": "S16-ACL-004..010", "module": "普通成员元模型 ACL", "actual": summarize(member_login), "result": "BLOCKED", "needFix": False})

    if receiver_token:
        receiver_list = client.request("GET", "/meta-schemas", receiver_token, RECEIVER_PROJECT_ID, params={"includeFields": "false"})
        add_result(
            results,
            "S16-ACL-011",
            "接收项目成员可读全局元模型",
            lambda response: is_ok(response) and contains_schema(response, schema_code),
            receiver_list,
            {"schemaCode": schema_code, "semantic": "元模型当前为租户级全局读资源，本用例只校验写 ACL 不阻断读取"},
            need_fix=False,
        )
    else:
        results.append({"case": "S16-ACL-011", "module": "接收项目元模型读取", "actual": summarize(receiver_login), "result": "BLOCKED", "needFix": False})

    output = {
        "executedAt": datetime.now().isoformat(timespec="seconds"),
        "batch": batch,
        "fixtures": {
            "schemaCode": schema_code,
            "schemaId": schema_id,
            "logins": {
                "admin": summarize(admin_login),
                "member": summarize(member_login),
                "receiver": summarize(receiver_login),
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

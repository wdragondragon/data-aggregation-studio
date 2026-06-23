#!/usr/bin/env python
"""Studio S23 ACL expansion probe.

The probe preserves long-term data. It creates traceable fixtures with
lt_reg_s23_acl prefixes and never deletes long-term Studio objects.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime
from typing import Any, Callable

import requests

try:
    sys.stdout.reconfigure(encoding="utf-8")
except AttributeError:
    pass

BASE_URL = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
RECEIVER_PROJECT_ID = os.getenv("STUDIO_RECEIVER_PROJECT_ID", "2068301893337849857")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")
S01_PASSWORD = os.getenv("STUDIO_S01_PASSWORD", "LtRegS01!2026")
LONG_DATASOURCE_ID = os.getenv("STUDIO_LONG_DATASOURCE_ID", "2068077811652583425")
NACOS_READINESS = os.getenv(
    "STUDIO_NACOS_READINESS",
    "http://127.0.0.1:8848/nacos/v1/console/health/readiness",
)
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")

SOURCE_DATA_SERVICE_LOG_ID = os.getenv("STUDIO_S23_DATA_SERVICE_LOG_ID", "2068735106325544962")
SOURCE_DATA_INGESTION_LOG_ID = os.getenv("STUDIO_S23_DATA_INGESTION_LOG_ID", "2068735128848957441")
SOURCE_PROTOCOL_CONVERSION_LOG_ID = os.getenv("STUDIO_S23_PROTOCOL_CONVERSION_LOG_ID", "2068742932208996354")


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


def expect_forbidden(response: dict[str, Any]) -> bool:
    return response.get("status") == 403 and response_code(response) == "FORBIDDEN"


def expect_not_found(response: dict[str, Any]) -> bool:
    return response.get("status") == 404 and response_code(response) == "NOT_FOUND"


def expect_forbidden_or_not_found(response: dict[str, Any]) -> bool:
    return (
        (response.get("status") == 403 and response_code(response) == "FORBIDDEN")
        or (response.get("status") == 404 and response_code(response) == "NOT_FOUND")
    )


def expect_no_cross_project_data(response: dict[str, Any]) -> bool:
    if expect_forbidden_or_not_found(response):
        return True
    if not is_ok(response):
        return False
    payload = response_data(response)
    if payload is None:
        return True
    if isinstance(payload, list):
        return len(payload) == 0
    if isinstance(payload, dict):
        if isinstance(payload.get("items"), list):
            return len(payload["items"]) == 0
        if isinstance(payload.get("records"), list):
            return len(payload["records"]) == 0
    return False


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
            for key in (
                "id",
                "name",
                "code",
                "fileName",
                "status",
                "workflowRunId",
                "issueCode",
                "modelName",
                "mappingCode",
            ):
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


def page_items(response: dict[str, Any]) -> list[dict[str, Any]]:
    payload = response_data(response)
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        for key in ("items", "records"):
            if isinstance(payload.get(key), list):
                return [item for item in payload[key] if isinstance(item, dict)]
    return []


def ensure_field_mapping_rule(client: StudioClient, admin_token: str, batch: str) -> dict[str, Any]:
    payload = {
        "mappingName": f"长期回归-S23客户手机号脱敏规则-{batch}",
        "mappingType": "脱敏",
        "mappingCode": f"lt_reg_s23_acl_phone_mask_{batch}",
        "enabled": True,
        "description": "长期回归 S23 ACL 扩展探针保留规则",
        "params": [
            {
                "paramName": "保留后四位",
                "paramOrder": 1,
                "componentType": "numberPicker",
                "paramValueJson": None,
                "description": "客户手机号脱敏保留位数",
            }
        ],
    }
    response = client.request("POST", "/field-mapping-rules", admin_token, LONG_PROJECT_ID, json_body=payload, timeout=45)
    rule = response_data(response) if is_ok(response) else {}
    return {"id": str(rule.get("id")) if isinstance(rule, dict) and rule.get("id") else None, "save": summarize(response), "payload": payload}


def ensure_data_development_script(client: StudioClient, admin_token: str, batch: str) -> dict[str, Any]:
    directory_response = client.request(
        "POST",
        "/data-development/directories",
        admin_token,
        LONG_PROJECT_ID,
        json_body={
            "name": f"长期回归-S23跨项目脚本目录-{batch}",
            "description": "长期回归 S23 ACL 扩展探针目录",
        },
        timeout=45,
    )
    directory = response_data(directory_response) if is_ok(directory_response) else {}
    directory_id = directory.get("id") if isinstance(directory, dict) else None
    script_response = client.request(
        "POST",
        "/data-development/scripts",
        admin_token,
        LONG_PROJECT_ID,
        json_body={
            "directoryId": directory_id,
            "fileName": f"长期回归-S23客户订单ACL查询-{batch}.sql",
            "scriptType": "SQL",
            "datasourceId": int(LONG_DATASOURCE_ID),
            "description": "长期回归 S23 ACL 扩展探针脚本",
            "content": "select 'S23客户订单ACL探针' as probe_name",
        },
        timeout=45,
    )
    script = response_data(script_response) if is_ok(script_response) else {}
    return {
        "directoryId": str(directory_id) if directory_id is not None else None,
        "scriptId": str(script.get("id")) if isinstance(script, dict) and script.get("id") else None,
        "directory": summarize(directory_response),
        "script": summarize(script_response),
    }


def first_model_id(client: StudioClient, admin_token: str) -> tuple[str | None, dict[str, Any]]:
    response = client.request(
        "GET",
        f"/models/datasource/{LONG_DATASOURCE_ID}",
        admin_token,
        LONG_PROJECT_ID,
        params={"pageNo": 1, "pageSize": 1},
        timeout=45,
    )
    items = page_items(response)
    model_id = str(items[0].get("id")) if items and items[0].get("id") else None
    return model_id, response


def first_quality_issue_id(client: StudioClient, admin_token: str) -> tuple[str | None, dict[str, Any]]:
    response = client.request("POST", "/quality-metrics/issues/query", admin_token, LONG_PROJECT_ID, json_body={}, timeout=30)
    issues = response_data(response) or []
    issue_id = str(issues[0].get("id")) if isinstance(issues, list) and issues and issues[0].get("id") else None
    return issue_id, response


def first_workflow_run_id(client: StudioClient, admin_token: str) -> tuple[str | None, dict[str, Any]]:
    response = client.request(
        "GET",
        "/workflow-runs",
        admin_token,
        LONG_PROJECT_ID,
        params={"pageNo": 1, "pageSize": 1},
        timeout=30,
    )
    items = page_items(response)
    workflow_run_id = str(items[0].get("workflowRunId")) if items and items[0].get("workflowRunId") else None
    return workflow_run_id, response


def main() -> int:
    client = StudioClient()
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    results: list[dict[str, Any]] = []

    admin_token, admin_login = client.login(ADMIN_USER, ADMIN_PASSWORD, LONG_PROJECT_ID)
    member_token, member_login = client.login("lt_reg_s01_long_project_member", S01_PASSWORD, LONG_PROJECT_ID)
    receiver_token, receiver_login = client.login("lt_reg_s01_receiver_member", S01_PASSWORD, RECEIVER_PROJECT_ID)
    if not admin_token or not member_token or not receiver_token:
        print(
            json.dumps(
                {
                    "fatal": "login failed",
                    "adminLogin": summarize(admin_login),
                    "memberLogin": summarize(member_login),
                    "receiverLogin": summarize(receiver_login),
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 2

    field_rule = ensure_field_mapping_rule(client, admin_token, batch)
    script_fixture = ensure_data_development_script(client, admin_token, batch)
    model_id, model_probe = first_model_id(client, admin_token)
    issue_id, issue_probe = first_quality_issue_id(client, admin_token)
    workflow_run_id, workflow_run_probe = first_workflow_run_id(client, admin_token)

    add_result(
        results,
        "S23-FMR-001",
        "字段映射规则",
        expect_forbidden,
        client.request("GET", "/field-mapping-rules", member_token, LONG_PROJECT_ID),
        {"semantic": "系统级字段映射规则列表仅超级管理员可读"},
    )
    if field_rule.get("id"):
        add_result(
            results,
            "S23-FMR-002",
            "字段映射规则",
            expect_forbidden,
            client.request("GET", f"/field-mapping-rules/{field_rule['id']}", member_token, LONG_PROJECT_ID),
            {"ruleId": field_rule["id"]},
        )
        add_result(
            results,
            "S23-FMR-003",
            "字段映射规则",
            expect_forbidden,
            client.request("DELETE", f"/field-mapping-rules/{field_rule['id']}", member_token, LONG_PROJECT_ID),
            {"ruleId": field_rule["id"]},
        )
    else:
        add_blocked(results, "S23-FMR-002..003", "字段映射规则", "admin fixture rule was not created", field_rule)

    if script_fixture.get("scriptId") and script_fixture.get("directoryId"):
        script_id = script_fixture["scriptId"]
        directory_id = script_fixture["directoryId"]
        add_result(
            results,
            "S23-DD-001",
            "数据开发",
            expect_not_found,
            client.request("GET", f"/data-development/scripts/{script_id}", receiver_token, RECEIVER_PROJECT_ID),
            {"scriptId": script_id},
        )
        add_result(
            results,
            "S23-DD-002",
            "数据开发",
            expect_forbidden_or_not_found,
            client.request(
                "POST",
                "/data-development/scripts",
                receiver_token,
                RECEIVER_PROJECT_ID,
                json_body={
                    "id": int(script_id),
                    "directoryId": None,
                    "fileName": f"长期回归-S23越权编辑脚本-{batch}.sql",
                    "scriptType": "SQL",
                    "datasourceId": int(LONG_DATASOURCE_ID),
                    "description": "接收项目成员越权编辑探针",
                    "content": "select '越权编辑' as probe_name",
                },
            ),
            {"scriptId": script_id},
        )
        add_result(
            results,
            "S23-DD-003",
            "数据开发",
            expect_forbidden_or_not_found,
            client.request("DELETE", f"/data-development/directories/{directory_id}", receiver_token, RECEIVER_PROJECT_ID),
            {"directoryId": directory_id},
        )
    else:
        add_blocked(results, "S23-DD-001..003", "数据开发", "admin fixture script was not created", script_fixture)

    add_result(
        results,
        "S23-DD-004",
        "数据开发",
        expect_forbidden_or_not_found,
        client.request(
            "POST",
            "/data-development/sql/execute",
            receiver_token,
            RECEIVER_PROJECT_ID,
            json_body={
                "scriptType": "SQL",
                "datasourceId": int(LONG_DATASOURCE_ID),
                "content": "select '接收项目越权SQL执行探针' as probe_name",
                "maxRows": 1,
            },
            timeout=45,
        ),
        {"datasourceId": LONG_DATASOURCE_ID},
    )

    if model_id:
        add_result(
            results,
            "S23-MODEL-001",
            "模型中心",
            expect_not_found,
            client.request("GET", f"/models/{model_id}", receiver_token, RECEIVER_PROJECT_ID),
            {"modelId": model_id},
        )
        add_result(
            results,
            "S23-MODEL-002",
            "模型中心",
            expect_no_cross_project_data,
            client.request("GET", f"/models/{model_id}/preview", receiver_token, RECEIVER_PROJECT_ID, params={"limit": 1}, timeout=45),
            {"modelId": model_id, "semantic": "不可读模型预览不得泄漏数据"},
        )
        add_result(
            results,
            "S23-MODEL-003",
            "模型中心",
            expect_forbidden_or_not_found,
            client.request(
                "POST",
                f"/models/{model_id}/lineage/manual",
                receiver_token,
                RECEIVER_PROJECT_ID,
                json_body={
                    "level": "TABLE",
                    "sourceModelId": int(model_id),
                    "targetModelId": int(model_id),
                },
            ),
            {"modelId": model_id},
        )
        add_result(
            results,
            "S23-MODEL-004",
            "模型中心",
            expect_forbidden_or_not_found,
            client.request("DELETE", f"/models/{model_id}", receiver_token, RECEIVER_PROJECT_ID),
            {"modelId": model_id},
        )
    else:
        add_blocked(results, "S23-MODEL-001..004", "模型中心", "long datasource has no model fixture", {"modelProbe": summarize(model_probe)})

    add_result(
        results,
        "S23-SYNC-001",
        "模型同步任务",
        expect_forbidden_or_not_found,
        client.request(
            "POST",
            "/model-sync-tasks",
            receiver_token,
            RECEIVER_PROJECT_ID,
            json_body={
                "datasourceId": int(LONG_DATASOURCE_ID),
                "physicalLocators": ["lt_reg_s23_cross_project_forbidden_probe"],
                "source": "MANUAL",
            },
            timeout=45,
        ),
        {"datasourceId": LONG_DATASOURCE_ID},
    )

    if issue_id:
        add_result(
            results,
            "S23-QI-001",
            "质量问题",
            expect_not_found,
            client.request("GET", f"/quality-metrics/issues/{issue_id}", receiver_token, RECEIVER_PROJECT_ID),
            {"issueId": issue_id},
        )
        add_result(
            results,
            "S23-QI-002",
            "质量问题",
            expect_forbidden_or_not_found,
            client.request(
                "POST",
                f"/quality-metrics/issues/{issue_id}/status",
                receiver_token,
                RECEIVER_PROJECT_ID,
                json_body={"status": "ACKNOWLEDGED", "comment": f"长期回归-S23越权状态变更-{batch}"},
            ),
            {"issueId": issue_id},
        )
        add_result(
            results,
            "S23-QI-003",
            "质量问题",
            expect_forbidden_or_not_found,
            client.request(
                "POST",
                f"/quality-metrics/issues/{issue_id}/comment",
                receiver_token,
                RECEIVER_PROJECT_ID,
                json_body={"content": f"长期回归-S23越权评论-{batch}"},
            ),
            {"issueId": issue_id},
        )
    else:
        add_blocked(results, "S23-QI-001..003", "质量问题", "long project has no quality issue fixture", {"issueProbe": summarize(issue_probe)})

    if workflow_run_id:
        add_result(
            results,
            "S23-WFR-001",
            "工作流运行",
            expect_not_found,
            client.request("GET", f"/workflow-runs/{workflow_run_id}", receiver_token, RECEIVER_PROJECT_ID),
            {"workflowRunId": workflow_run_id},
        )
        add_result(
            results,
            "S23-WFR-002",
            "工作流运行",
            expect_forbidden_or_not_found,
            client.request("POST", f"/workflow-runs/{workflow_run_id}/terminate", receiver_token, RECEIVER_PROJECT_ID),
            {"workflowRunId": workflow_run_id},
        )
    else:
        add_blocked(results, "S23-WFR-001..002", "工作流运行", "long project has no workflow run fixture", {"workflowRunProbe": summarize(workflow_run_probe)})

    for index, (domain, log_id) in enumerate(
        (
            ("data-services", SOURCE_DATA_SERVICE_LOG_ID),
            ("data-ingestion-services", SOURCE_DATA_INGESTION_LOG_ID),
            ("protocol-conversions", SOURCE_PROTOCOL_CONVERSION_LOG_ID),
        ),
        start=1,
    ):
        add_result(
            results,
            f"S23-LOG-{index:03d}",
            "调用日志",
            expect_not_found,
            client.request("GET", f"/invocation-logs/{domain}/{log_id}", receiver_token, RECEIVER_PROJECT_ID),
            {"domain": domain, "accessLogId": log_id, "semantic": "跨项目完整日志查看不可读取"},
        )

    output = {
        "executedAt": datetime.now().isoformat(timespec="seconds"),
        "batch": batch,
        "fixtures": {
            "fieldRule": field_rule,
            "dataDevelopment": script_fixture,
            "modelId": model_id,
            "qualityIssueId": issue_id,
            "workflowRunId": workflow_run_id,
            "adminLogin": summarize(admin_login),
            "memberLogin": summarize(member_login),
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

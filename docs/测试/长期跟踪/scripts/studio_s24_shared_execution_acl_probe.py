#!/usr/bin/env python
"""Studio S24 shared resource execution ACL probe.

This probe preserves long-term data. It creates traceable S24 workflow and
collection-task fixtures, shares them to the receiver project, and verifies
that enabled resource sharing grants read access only, not write or manual
execution privileges.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime
from typing import Any, Callable

import pymysql
import requests

try:
    sys.stdout.reconfigure(encoding="utf-8")
except AttributeError:
    pass

BASE_URL = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
RECEIVER_PROJECT_ID = os.getenv("STUDIO_RECEIVER_PROJECT_ID", "2068301893337849857")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")
RECEIVER_USER = os.getenv("STUDIO_RECEIVER_USER", "lt_reg_s01_receiver_member")
RECEIVER_PASSWORD = os.getenv("STUDIO_RECEIVER_PASSWORD", "LtRegS01!2026")

NACOS_READINESS = os.getenv(
    "STUDIO_NACOS_READINESS",
    "http://127.0.0.1:8848/nacos/v1/console/health/readiness",
)
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")

MYSQL_HOST = os.getenv("STUDIO_MYSQL_HOST", "8.140.247.113")
MYSQL_PORT = int(os.getenv("STUDIO_MYSQL_PORT", "13309"))
MYSQL_USER = os.getenv("STUDIO_MYSQL_USER", "root")
MYSQL_PASSWORD = os.getenv("STUDIO_MYSQL_PASSWORD", "951753")
LONG_MYSQL_DB = os.getenv("STUDIO_MYSQL_DATABASE", "studio_longterm_regression")
META_MYSQL_DB = os.getenv("STUDIO_META_MYSQL_DB", "data_aggregation_studio")
DATASOURCE_ID = os.getenv("STUDIO_LONG_DATASOURCE_ID", "2068077811652583425")


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
        json_body: Any | None = None,
        timeout: int = 30,
    ) -> dict[str, Any]:
        headers = {"X-Tenant-Id": TENANT_ID}
        if project_id is not None:
            headers["X-Project-Id"] = str(project_id)
        if token:
            headers["Authorization"] = "Bearer " + token
        try:
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
        except Exception as exc:
            return {"status": "EXCEPTION", "body": {"message": str(exc)}}

    def login(self, username: str, password: str, project_id: str) -> tuple[str | None, dict[str, Any]]:
        response = self.request(
            "POST",
            "/auth/login",
            project_id=project_id,
            json_body={"username": username, "password": password},
            timeout=20,
        )
        token = None
        if isinstance(response.get("body"), dict):
            token = (response["body"].get("data") or {}).get("token")
        return token, response


def mysql_conn(database: str) -> pymysql.Connection:
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=10,
        autocommit=True,
    )


def is_ok(response: dict[str, Any] | None) -> bool:
    return (
        response is not None
        and response.get("status") == 200
        and isinstance(response.get("body"), dict)
        and response["body"].get("success") is True
    )


def response_code(response: dict[str, Any] | None) -> str | None:
    body = response.get("body") if response else None
    return body.get("code") if isinstance(body, dict) else None


def response_data(response: dict[str, Any] | None) -> Any:
    body = response.get("body") if response else None
    return body.get("data") if isinstance(body, dict) else None


def expect_ok(response: dict[str, Any]) -> bool:
    return is_ok(response)


def expect_forbidden_or_not_found(response: dict[str, Any]) -> bool:
    return (
        (response.get("status") == 403 and response_code(response) == "FORBIDDEN")
        or (response.get("status") == 404 and response_code(response) == "NOT_FOUND")
    )


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
                "resourceType",
                "resourceId",
                "enabled",
                "targetProjectId",
                "workflowRunId",
                "projectId",
            ):
                if data.get(key) is not None:
                    summary["data." + key] = data.get(key)
            if isinstance(data.get("items"), list):
                summary["data.itemsCount"] = len(data.get("items") or [])
        elif isinstance(data, list):
            summary["dataCount"] = len(data)
        return summary
    return {"status": response.get("status"), "body": str(body)[:500]}


def add_case(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    expected: str,
    expectation: Callable[[dict[str, Any]], bool],
    response: dict[str, Any],
    need_fix: bool | None = None,
    evidence: dict[str, Any] | None = None,
) -> None:
    passed = expectation(response)
    results.append(
        {
            "case": case_id,
            "module": module,
            "expected": expected,
            "actual": summarize(response),
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
            "evidence": evidence or {},
        }
    )


def add_bool_case(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    expected: str,
    passed: bool,
    actual: Any,
    need_fix: bool | None = None,
) -> None:
    results.append(
        {
            "case": case_id,
            "module": module,
            "expected": expected,
            "actual": actual,
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
        }
    )


def read_health() -> dict[str, Any]:
    values: dict[str, Any] = {}
    for key, url in (("nacos", NACOS_READINESS), ("server", SERVER_HEALTH), ("worker", WORKER_HEALTH)):
        try:
            response = requests.get(url, timeout=10)
            try:
                body: Any = response.json()
            except Exception:
                body = response.text
            values[key] = {"status": response.status_code, "body": body}
        except Exception as exc:
            values[key] = {"error": str(exc)}
    return values


def ensure_batch_tables(source_table: str, target_table: str, batch: str) -> dict[str, Any]:
    with mysql_conn(LONG_MYSQL_DB) as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                create table if not exists {source_table} (
                  order_id varchar(48) primary key comment '订单编号',
                  customer_name varchar(120) not null comment '客户名称',
                  order_amount decimal(12,2) not null comment '订单金额',
                  updated_at datetime not null comment '更新时间'
                ) comment='长期回归-S24共享执行ACL源表'
                """
            )
            cur.execute(
                f"""
                create table if not exists {target_table} (
                  order_id varchar(48) primary key comment '订单编号',
                  customer_name varchar(120) not null comment '客户名称',
                  order_amount decimal(12,2) not null comment '订单金额',
                  updated_at datetime not null comment '更新时间'
                ) comment='长期回归-S24共享执行ACL目标表'
                """
            )
            cur.execute(
                f"""
                replace into {source_table}
                  (order_id, customer_name, order_amount, updated_at)
                values
                  (%s, '华北续费客户事业部', 3188.60, '2026-06-23 10:10:00')
                """,
                (f"S24-ACL-{batch}-001",),
            )
    return {"sourceTable": source_table, "targetTable": target_table, "sourceRows": 1}


def sync_selected_models(client: StudioClient, token: str, locators: list[str]) -> dict[str, Any]:
    return client.request(
        "POST",
        f"/models/datasource/{DATASOURCE_ID}/sync-selected",
        token=token,
        project_id=LONG_PROJECT_ID,
        json_body={"physicalLocators": locators},
        timeout=90,
    )


def list_models(client: StudioClient, token: str) -> list[dict[str, Any]]:
    response = client.request(
        "GET",
        f"/models/datasource/{DATASOURCE_ID}",
        token=token,
        project_id=LONG_PROJECT_ID,
        params={"pageNo": 1, "pageSize": 5000},
        timeout=30,
    )
    data = response_data(response) or {}
    return data.get("items") or data.get("records") or []


def model_by_locator(models: list[dict[str, Any]], locator: str) -> dict[str, Any] | None:
    for model in models:
        if model.get("physicalLocator") == locator or model.get("name") == locator:
            return model
    return None


def create_workflow_payload(batch: str) -> dict[str, Any]:
    return {
        "code": "lt_reg_s24_shared_execution_acl_workflow_" + batch,
        "name": "长期回归-S24共享执行权限流程-" + batch,
        "schedule": {"enabled": False, "timezone": "Asia/Shanghai", "cronExpression": None},
        "nodes": [
            {
                "nodeCode": "s24_http_health",
                "nodeName": "长期回归-S24共享执行健康检查节点-" + batch,
                "nodeType": "HTTP",
                "config": {"method": "GET", "url": "http://127.0.0.1:18080/actuator/health"},
                "fieldMappings": [],
            }
        ],
        "edges": [],
    }


def create_collection_task_payload(batch: str, source_model: dict[str, Any], target_model: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": "长期回归-S24共享执行权限采集任务-" + batch,
        "sourceBindings": [
            {
                "sourceAlias": "s24_order",
                "datasourceId": DATASOURCE_ID,
                "modelId": source_model["id"],
                "readerOptions": {},
            }
        ],
        "targetBinding": {
            "datasourceId": DATASOURCE_ID,
            "modelId": target_model["id"],
            "writerOptions": {"writeMode": "insert"},
        },
        "fieldMappings": [
            {"sourceAlias": "s24_order", "sourceField": "order_id", "targetField": "order_id", "transformers": []},
            {"sourceAlias": "s24_order", "sourceField": "customer_name", "targetField": "customer_name", "transformers": []},
            {"sourceAlias": "s24_order", "sourceField": "order_amount", "targetField": "order_amount", "transformers": []},
            {"sourceAlias": "s24_order", "sourceField": "updated_at", "targetField": "updated_at", "transformers": []},
        ],
        "executionOptions": {},
        "schedule": None,
    }


def create_share(client: StudioClient, token: str, resource_type: str, resource_id: str) -> tuple[str | None, dict[str, Any]]:
    payload = {
        "sourceProjectId": int(LONG_PROJECT_ID),
        "targetProjectId": int(RECEIVER_PROJECT_ID),
        "resourceType": resource_type,
        "resourceId": int(resource_id),
        "enabled": 1,
    }
    response = client.request("POST", "/system/resource-shares", token, LONG_PROJECT_ID, json_body=payload, timeout=45)
    data = response_data(response) if is_ok(response) else None
    share_id = str(data.get("id")) if isinstance(data, dict) and data.get("id") else None
    return share_id, response


def disable_share(client: StudioClient, token: str, share_id: str, resource_type: str, resource_id: str) -> dict[str, Any]:
    payload = {
        "id": int(share_id),
        "sourceProjectId": int(LONG_PROJECT_ID),
        "targetProjectId": int(RECEIVER_PROJECT_ID),
        "resourceType": resource_type,
        "resourceId": int(resource_id),
        "enabled": 0,
    }
    return client.request("POST", "/system/resource-shares", token, LONG_PROJECT_ID, json_body=payload, timeout=45)


def receiver_runtime_rows(workflow_id: str | None, collection_task_id: str | None, started_at: str) -> dict[str, Any]:
    result: dict[str, Any] = {}
    with mysql_conn(META_MYSQL_DB) as conn:
        with conn.cursor() as cur:
            if workflow_id:
                cur.execute(
                    """
                    select count(*) as cnt
                    from dispatch_task
                    where tenant_id=%s and project_id=%s and workflow_definition_id=%s and created_at >= %s
                    """,
                    (TENANT_ID, RECEIVER_PROJECT_ID, workflow_id, started_at),
                )
                result["receiverWorkflowDispatchTasks"] = int((cur.fetchone() or {}).get("cnt") or 0)
                cur.execute(
                    """
                    select count(*) as cnt
                    from run_record
                    where tenant_id=%s and project_id=%s and workflow_definition_id=%s and created_at >= %s
                    """,
                    (TENANT_ID, RECEIVER_PROJECT_ID, workflow_id, started_at),
                )
                result["receiverWorkflowRunRecords"] = int((cur.fetchone() or {}).get("cnt") or 0)
            if collection_task_id:
                cur.execute(
                    """
                    select count(*) as cnt
                    from dispatch_task
                    where tenant_id=%s and project_id=%s and collection_task_id=%s and created_at >= %s
                    """,
                    (TENANT_ID, RECEIVER_PROJECT_ID, collection_task_id, started_at),
                )
                result["receiverCollectionDispatchTasks"] = int((cur.fetchone() or {}).get("cnt") or 0)
                cur.execute(
                    """
                    select count(*) as cnt
                    from run_record
                    where tenant_id=%s and project_id=%s and collection_task_id=%s and created_at >= %s
                    """,
                    (TENANT_ID, RECEIVER_PROJECT_ID, collection_task_id, started_at),
                )
                result["receiverCollectionRunRecords"] = int((cur.fetchone() or {}).get("cnt") or 0)
    return result


def result_stats(results: list[dict[str, Any]]) -> dict[str, int]:
    return {
        "PASS": sum(1 for item in results if item["result"] == "PASS"),
        "FAIL": sum(1 for item in results if item["result"] == "FAIL"),
        "BLOCKED": sum(1 for item in results if item["result"] == "BLOCKED"),
        "needFix": sum(1 for item in results if item.get("needFix")),
    }


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")
    source_table = "lt_reg_s24_src_" + batch
    target_table = "lt_reg_s24_tgt_" + batch
    client = StudioClient()
    results: list[dict[str, Any]] = []
    evidence: dict[str, Any] = {
        "batch": batch,
        "projectId": LONG_PROJECT_ID,
        "receiverProjectId": RECEIVER_PROJECT_ID,
        "datasourceId": DATASOURCE_ID,
        "health": read_health(),
    }

    health_ok = (
        evidence["health"].get("nacos", {}).get("status") == 200
        and evidence["health"].get("nacos", {}).get("body") == "OK"
        and evidence["health"].get("server", {}).get("status") == 200
        and (evidence["health"].get("server", {}).get("body") or {}).get("status") == "UP"
        and evidence["health"].get("worker", {}).get("status") == 200
        and (evidence["health"].get("worker", {}).get("body") or {}).get("status") == "UP"
    )
    add_bool_case(results, "S24-ENV-001", "运行态", "Nacos readiness=OK，Server/Worker health=UP", health_ok, evidence["health"], need_fix=False)

    admin_token, admin_login = client.login(ADMIN_USER, ADMIN_PASSWORD, LONG_PROJECT_ID)
    receiver_token, receiver_login = client.login(RECEIVER_USER, RECEIVER_PASSWORD, RECEIVER_PROJECT_ID)
    add_case(results, "S24-AUTH-001", "认证", "管理员登录成功", expect_ok, admin_login, need_fix=False)
    add_case(results, "S24-AUTH-002", "认证", "接收项目成员登录成功", expect_ok, receiver_login, need_fix=False)
    if not admin_token or not receiver_token:
        output = {"batch": batch, "results": results, "evidence": evidence, "stats": result_stats(results)}
        print(json.dumps(output, ensure_ascii=False, indent=2, default=str))
        return 1

    workflow_response = client.request("POST", "/workflows", admin_token, LONG_PROJECT_ID, json_body=create_workflow_payload(batch), timeout=45)
    workflow = response_data(workflow_response) if is_ok(workflow_response) else None
    workflow_id = str(workflow.get("id")) if isinstance(workflow, dict) and workflow.get("id") else None
    evidence["workflowId"] = workflow_id
    add_bool_case(results, "S24-FIXTURE-001", "工作流夹具", "创建 S24 共享执行权限工作流", workflow_id is not None, {"workflow": summarize(workflow_response)})
    if workflow_id:
        publish_workflow = client.request("POST", f"/workflows/{workflow_id}/publish", admin_token, LONG_PROJECT_ID, timeout=45)
        add_case(results, "S24-FIXTURE-002", "工作流夹具", "源项目管理员发布工作流", expect_ok, publish_workflow)

    table_setup = ensure_batch_tables(source_table, target_table, batch)
    evidence["tables"] = table_setup
    add_bool_case(results, "S24-MYSQL-001", "MySQL 长期夹具", "创建并保留 S24 源/目标表，源表写入 1 行业务样例", True, table_setup, need_fix=False)

    sync_response = sync_selected_models(client, admin_token, [source_table, target_table])
    models = list_models(client, admin_token)
    source_model = model_by_locator(models, source_table)
    target_model = model_by_locator(models, target_table)
    add_bool_case(
        results,
        "S24-MODEL-001",
        "模型同步",
        "S24 源/目标表模型同步后可被采集任务引用",
        is_ok(sync_response) and source_model is not None and target_model is not None,
        {"sync": summarize(sync_response), "sourceModelId": (source_model or {}).get("id"), "targetModelId": (target_model or {}).get("id")},
    )

    collection_task_id = None
    if source_model and target_model:
        task_response = client.request(
            "POST",
            "/collection-tasks",
            admin_token,
            LONG_PROJECT_ID,
            json_body=create_collection_task_payload(batch, source_model, target_model),
            timeout=60,
        )
        task = response_data(task_response) if is_ok(task_response) else None
        collection_task_id = str(task.get("id")) if isinstance(task, dict) and task.get("id") else None
        evidence["collectionTaskId"] = collection_task_id
        add_bool_case(results, "S24-FIXTURE-003", "采集任务夹具", "创建 S24 共享执行权限采集任务", collection_task_id is not None, {"task": summarize(task_response)})
        if collection_task_id:
            publish_task = client.request("POST", f"/collection-tasks/{collection_task_id}/online", admin_token, LONG_PROJECT_ID, timeout=45)
            add_case(results, "S24-FIXTURE-004", "采集任务夹具", "源项目管理员上线采集任务", expect_ok, publish_task)

    workflow_share_id = None
    if workflow_id:
        workflow_share_id, workflow_share = create_share(client, admin_token, "WORKFLOW", workflow_id)
        evidence["workflowShareId"] = workflow_share_id
        add_bool_case(results, "S24-SHARE-001", "资源共享", "源项目工作流共享到接收项目", workflow_share_id is not None, {"share": summarize(workflow_share)})
        add_case(
            results,
            "S24-WF-READ-001",
            "共享工作流",
            "接收项目成员可读取启用共享工作流详情",
            expect_ok,
            client.request("GET", f"/workflows/{workflow_id}", receiver_token, RECEIVER_PROJECT_ID),
            need_fix=False,
        )
        for case_id, method, path, expected in (
            ("S24-WF-WRITE-001", "POST", f"/workflows/{workflow_id}/publish", "接收项目成员不可发布共享工作流"),
            ("S24-WF-WRITE-002", "DELETE", f"/workflows/{workflow_id}", "接收项目成员不可删除共享工作流"),
            ("S24-WF-EXEC-001", "POST", f"/workflows/{workflow_id}/trigger", "接收项目成员不可手动触发共享工作流"),
        ):
            add_case(
                results,
                case_id,
                "共享工作流",
                expected,
                expect_forbidden_or_not_found,
                client.request(method, path, receiver_token, RECEIVER_PROJECT_ID, timeout=45),
            )

    collection_share_id = None
    if collection_task_id:
        collection_share_id, collection_share = create_share(client, admin_token, "COLLECTION_TASK", collection_task_id)
        evidence["collectionShareId"] = collection_share_id
        add_bool_case(results, "S24-SHARE-002", "资源共享", "源项目采集任务共享到接收项目", collection_share_id is not None, {"share": summarize(collection_share)})
        add_case(
            results,
            "S24-CT-READ-001",
            "共享采集任务",
            "接收项目成员可读取启用共享采集任务详情",
            expect_ok,
            client.request("GET", f"/collection-tasks/{collection_task_id}", receiver_token, RECEIVER_PROJECT_ID),
            need_fix=False,
        )
        for case_id, method, path, expected, body in (
            ("S24-CT-WRITE-001", "POST", f"/collection-tasks/{collection_task_id}/online", "接收项目成员不可上线共享采集任务", None),
            ("S24-CT-WRITE-002", "POST", f"/collection-tasks/{collection_task_id}/schedule", "接收项目成员不可修改共享采集任务调度", {"enabled": False, "timezone": "Asia/Shanghai", "cronExpression": None}),
            ("S24-CT-WRITE-003", "DELETE", f"/collection-tasks/{collection_task_id}", "接收项目成员不可删除共享采集任务", None),
            ("S24-CT-EXEC-001", "POST", f"/collection-tasks/{collection_task_id}/trigger", "接收项目成员不可手动触发共享采集任务", None),
        ):
            add_case(
                results,
                case_id,
                "共享采集任务",
                expected,
                expect_forbidden_or_not_found,
                client.request(method, path, receiver_token, RECEIVER_PROJECT_ID, json_body=body, timeout=45),
            )

    runtime_rows = receiver_runtime_rows(workflow_id, collection_task_id, started_at)
    evidence["receiverRuntimeRows"] = runtime_rows
    add_bool_case(
        results,
        "S24-RUNTIME-001",
        "共享执行副作用",
        "接收项目不应因手动触发共享源项目工作流或采集任务产生运行/调度记录",
        all(int(value or 0) == 0 for value in runtime_rows.values()),
        runtime_rows,
    )

    if workflow_id and workflow_share_id:
        disable_workflow_share = disable_share(client, admin_token, workflow_share_id, "WORKFLOW", workflow_id)
        add_case(results, "S24-REVOKE-001", "共享撤销", "禁用工作流共享成功", expect_ok, disable_workflow_share, need_fix=False)
        add_case(
            results,
            "S24-REVOKE-002",
            "共享撤销",
            "共享禁用后接收项目成员不可读取旧工作流 ID",
            expect_not_found,
            client.request("GET", f"/workflows/{workflow_id}", receiver_token, RECEIVER_PROJECT_ID),
            need_fix=False,
        )
        add_case(
            results,
            "S24-REVOKE-003",
            "共享撤销",
            "共享禁用后接收项目成员不可触发旧工作流 ID",
            expect_forbidden_or_not_found,
            client.request("POST", f"/workflows/{workflow_id}/trigger", receiver_token, RECEIVER_PROJECT_ID, timeout=45),
            need_fix=False,
        )

    if collection_task_id and collection_share_id:
        disable_collection_share = disable_share(client, admin_token, collection_share_id, "COLLECTION_TASK", collection_task_id)
        add_case(results, "S24-REVOKE-004", "共享撤销", "禁用采集任务共享成功", expect_ok, disable_collection_share, need_fix=False)
        add_case(
            results,
            "S24-REVOKE-005",
            "共享撤销",
            "共享禁用后接收项目成员不可读取旧采集任务 ID",
            expect_not_found,
            client.request("GET", f"/collection-tasks/{collection_task_id}", receiver_token, RECEIVER_PROJECT_ID),
            need_fix=False,
        )
        add_case(
            results,
            "S24-REVOKE-006",
            "共享撤销",
            "共享禁用后接收项目成员不可触发旧采集任务 ID",
            expect_forbidden_or_not_found,
            client.request("POST", f"/collection-tasks/{collection_task_id}/trigger", receiver_token, RECEIVER_PROJECT_ID, timeout=45),
            need_fix=False,
        )

    output = {
        "batch": batch,
        "fixtures": {
            "projectId": LONG_PROJECT_ID,
            "receiverProjectId": RECEIVER_PROJECT_ID,
            "workflowId": workflow_id,
            "workflowShareId": workflow_share_id,
            "collectionTaskId": collection_task_id,
            "collectionShareId": collection_share_id,
            "tables": table_setup,
            "receiverRuntimeRows": runtime_rows,
        },
        "health": evidence["health"],
        "results": results,
        "stats": result_stats(results),
    }
    print(json.dumps(output, ensure_ascii=False, indent=2, default=str))
    return 0 if output["stats"]["FAIL"] == 0 and output["stats"]["needFix"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

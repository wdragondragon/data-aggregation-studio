#!/usr/bin/env python
"""Studio S09 schema drift probe.

The probe is data-preserving. It uses dedicated lt_reg_s09_* tables and
long-term Studio objects to exercise source/target schema changes without
touching the established S02/M06 baseline tables.
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
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")

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
MYSQL_DATABASE = os.getenv("STUDIO_MYSQL_DATABASE", "studio_longterm_regression")

META_MYSQL_DATABASE = os.getenv("STUDIO_META_MYSQL_DATABASE", "data_aggregation_studio")
DATASOURCE_ID = os.getenv("STUDIO_LONG_DATASOURCE_ID", "2068077811652583425")

TABLE_CONTRACT_SOURCE = "lt_reg_s09_contract_source"
TABLE_CONTRACT_TARGET = "lt_reg_s09_contract_target"
TABLE_REQUIRED_TARGET = "lt_reg_s09_required_target"
TABLE_MISSING_SOURCE = "lt_reg_s09_missing_source"
TABLE_MISSING_TARGET = "lt_reg_s09_missing_target"

TASK_CONTRACT = "长期回归-S09合同结构变更采集任务"
TASK_REQUIRED = "长期回归-S09目标必填列失败采集任务"
TASK_MISSING = "长期回归-S09源字段缺失失败采集任务"

FINAL_STATUSES = {"SUCCESS", "FAILED", "CANCELLED", "TERMINATED"}
FORBIDDEN_MARKERS = ("java.", "com.jdragon", "Traceback", ".java:", "\tat ")


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
            headers["Authorization"] = f"Bearer {token}"
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

    def login(self) -> tuple[str | None, dict[str, Any]]:
        response = self.request(
            "POST",
            "/auth/login",
            project_id=LONG_PROJECT_ID,
            json_body={"username": ADMIN_USER, "password": ADMIN_PASSWORD},
            timeout=20,
        )
        token = None
        if isinstance(response.get("body"), dict):
            token = (response["body"].get("data") or {}).get("token")
        return token, response


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def is_ok(response: dict[str, Any]) -> bool:
    body = response.get("body")
    return response.get("status") == 200 and isinstance(body, dict) and body.get("success") is True


def summarize(response: dict[str, Any] | None) -> dict[str, Any]:
    if response is None:
        return {"status": "SKIP"}
    body = response.get("body")
    if isinstance(body, dict):
        payload = body.get("data")
        summary: dict[str, Any] = {
            "status": response.get("status"),
            "success": body.get("success"),
            "code": body.get("code"),
            "message": body.get("message"),
        }
        if isinstance(payload, list):
            summary["dataCount"] = len(payload)
        elif isinstance(payload, dict):
            summary["dataKeys"] = list(payload.keys())[:12]
            for key in ("id", "name", "status", "message", "physicalLocator"):
                if key in payload:
                    summary[key] = payload.get(key)
        return summary
    return {"status": response.get("status"), "body": str(body)[:500]}


def add_result(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    expected: str,
    actual: Any,
    passed: bool,
    need_fix: bool | None = None,
    evidence: dict[str, Any] | None = None,
) -> None:
    results.append(
        {
            "case": case_id,
            "module": module,
            "expected": expected,
            "actual": actual,
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
            "evidence": evidence or {},
        }
    )


def read_health() -> dict[str, Any]:
    values: dict[str, Any] = {}
    for key, url in (("nacos", NACOS_READINESS), ("server", SERVER_HEALTH), ("worker", WORKER_HEALTH)):
        try:
            response = requests.get(url, timeout=10)
            try:
                values[key] = response.json()
            except Exception:
                values[key] = response.text
        except Exception as exc:
            values[key] = {"error": str(exc)}
    return values


def mysql_connection(database: str = MYSQL_DATABASE):
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=False,
    )


def column_exists(cursor, table: str, column: str) -> bool:
    cursor.execute(
        """
        select count(*) as cnt
        from information_schema.columns
        where table_schema=%s and table_name=%s and column_name=%s
        """,
        (MYSQL_DATABASE, table, column),
    )
    return int((cursor.fetchone() or {}).get("cnt") or 0) > 0


def index_exists(cursor, table: str, index_name: str) -> bool:
    cursor.execute(
        """
        select count(*) as cnt
        from information_schema.statistics
        where table_schema=%s and table_name=%s and index_name=%s
        """,
        (MYSQL_DATABASE, table, index_name),
    )
    return int((cursor.fetchone() or {}).get("cnt") or 0) > 0


def ensure_column(cursor, table: str, column: str, ddl: str) -> bool:
    if column_exists(cursor, table, column):
        return False
    cursor.execute(f"alter table {table} add column {ddl}")
    return True


def ensure_s09_tables() -> dict[str, Any]:
    changes: dict[str, Any] = {"createdOrAltered": []}
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                f"""
                create table if not exists {TABLE_CONTRACT_SOURCE} (
                  contract_id varchar(40) primary key comment '合同编号',
                  customer_name varchar(120) not null comment '客户名称',
                  contract_amount decimal(12,2) not null comment '合同金额',
                  signed_at datetime not null comment '签署时间',
                  contract_status varchar(32) not null comment '合同状态',
                  updated_at datetime not null comment '更新时间'
                ) comment='长期回归-S09合同源表'
                """
            )
            cursor.execute(
                f"""
                create table if not exists {TABLE_CONTRACT_TARGET} (
                  contract_id varchar(40) primary key comment '合同编号',
                  customer_name varchar(120) not null comment '客户名称',
                  contract_amount decimal(12,2) not null comment '合同金额',
                  signed_at datetime not null comment '签署时间',
                  risk_level varchar(32) null comment '风险等级'
                ) comment='长期回归-S09合同目标表'
                """
            )
            cursor.execute(
                f"""
                create table if not exists {TABLE_REQUIRED_TARGET} (
                  contract_id varchar(40) primary key comment '合同编号',
                  customer_name varchar(120) not null comment '客户名称',
                  audit_required varchar(32) not null comment '审计要求'
                ) comment='长期回归-S09目标必填列失败表'
                """
            )
            cursor.execute(f"alter table {TABLE_REQUIRED_TARGET} modify audit_required varchar(32) not null comment '审计要求'")
            cursor.execute(f"alter table {TABLE_REQUIRED_TARGET} alter column audit_required drop default")
            cursor.execute(
                f"""
                create table if not exists {TABLE_MISSING_SOURCE} (
                  contract_id varchar(40) primary key comment '合同编号',
                  customer_name varchar(120) not null comment '客户名称',
                  contract_amount decimal(12,2) not null comment '合同金额',
                  updated_at datetime not null comment '更新时间'
                ) comment='长期回归-S09源字段缺失源表'
                """
            )
            cursor.execute(
                f"""
                create table if not exists {TABLE_MISSING_TARGET} (
                  contract_id varchar(40) primary key comment '合同编号',
                  customer_name varchar(120) not null comment '客户名称',
                  contract_amount decimal(12,2) not null comment '合同金额'
                ) comment='长期回归-S09源字段缺失目标表'
                """
            )
            if column_exists(cursor, TABLE_MISSING_SOURCE, "contract_total_amount") and not column_exists(cursor, TABLE_MISSING_SOURCE, "contract_amount"):
                cursor.execute(f"alter table {TABLE_MISSING_SOURCE} rename column contract_total_amount to contract_amount")
                changes["createdOrAltered"].append(f"{TABLE_MISSING_SOURCE}.contract_total_amount->contract_amount")
            if ensure_column(cursor, TABLE_CONTRACT_TARGET, "risk_level", "risk_level varchar(32) null comment '风险等级'"):
                changes["createdOrAltered"].append(f"{TABLE_CONTRACT_TARGET}.risk_level")
            if not index_exists(cursor, TABLE_CONTRACT_SOURCE, "idx_s09_contract_source_updated"):
                cursor.execute(f"create index idx_s09_contract_source_updated on {TABLE_CONTRACT_SOURCE}(updated_at)")
                changes["createdOrAltered"].append("idx_s09_contract_source_updated")
            cursor.execute(
                f"""
                replace into {TABLE_CONTRACT_SOURCE}
                  (contract_id, customer_name, contract_amount, signed_at, contract_status, updated_at)
                values
                  ('S09-CON-001', '华东零售事业群', 128800.50, '2026-06-21 09:15:00', 'ACTIVE', '2026-06-21 09:30:00'),
                  ('S09-CON-002', '西南渠道合作中心', 76120.00, '2026-06-21 10:20:00', 'REVIEW', '2026-06-21 10:40:00')
                """
            )
            risk_added = ensure_column(cursor, TABLE_CONTRACT_SOURCE, "risk_level", "risk_level varchar(32) null comment '风险等级'")
            if risk_added:
                changes["createdOrAltered"].append(f"{TABLE_CONTRACT_SOURCE}.risk_level")
            cursor.execute(
                f"""
                update {TABLE_CONTRACT_SOURCE}
                set risk_level = case contract_id
                    when 'S09-CON-001' then '高风险-重点跟进'
                    when 'S09-CON-002' then '中风险-复核中'
                    else risk_level
                end
                where contract_id in ('S09-CON-001', 'S09-CON-002')
                """
            )
            cursor.execute(
                f"""
                replace into {TABLE_MISSING_SOURCE}
                  (contract_id, customer_name, contract_amount, updated_at)
                values ('S09-MISS-001', '北方政企客户群', 9900.00, '2026-06-21 11:00:00')
                """
            )
        connection.commit()
    return changes


def sync_selected_models(client: StudioClient, token: str, locators: list[str]) -> dict[str, Any]:
    return client.request(
        "POST",
        f"/models/datasource/{DATASOURCE_ID}/sync-selected",
        token=token,
        json_body={"physicalLocators": locators},
        timeout=60,
    )


def list_models(client: StudioClient, token: str) -> list[dict[str, Any]]:
    response = client.request(
        "GET",
        f"/models/datasource/{DATASOURCE_ID}",
        token=token,
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


def model_columns(model: dict[str, Any] | None) -> list[str]:
    metadata = (model or {}).get("technicalMetadata") or {}
    return [str(item.get("name")) for item in metadata.get("columns") or [] if item.get("name")]


def task_by_name(client: StudioClient, token: str, name: str) -> dict[str, Any] | None:
    response = client.request("GET", "/collection-tasks", token=token, params={"name": name}, timeout=30)
    if not is_ok(response):
        return None
    for task in response_data(response) or []:
        if task.get("name") == name:
            return task
    return None


def save_task(
    client: StudioClient,
    token: str,
    name: str,
    source_model: dict[str, Any],
    target_model: dict[str, Any],
    mappings: list[dict[str, Any]],
    writer_options: dict[str, Any] | None = None,
) -> dict[str, Any]:
    existing = task_by_name(client, token, name)
    payload = {
        "id": existing.get("id") if existing else None,
        "name": name,
        "sourceBindings": [
            {
                "sourceAlias": "contract",
                "datasourceId": DATASOURCE_ID,
                "modelId": source_model["id"],
                "readerOptions": {},
            }
        ],
        "targetBinding": {
            "datasourceId": DATASOURCE_ID,
            "modelId": target_model["id"],
            "writerOptions": writer_options or {"writeMode": "replace"},
        },
        "fieldMappings": mappings,
        "executionOptions": {},
        "schedule": None,
    }
    return client.request("POST", "/collection-tasks", token=token, json_body=payload, timeout=60)


def publish_task(client: StudioClient, token: str, task_id: str) -> dict[str, Any]:
    return client.request("POST", f"/collection-tasks/{task_id}/online", token=token, timeout=30)


def active_counts(task_id: str) -> dict[str, int]:
    with mysql_connection(META_MYSQL_DATABASE) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select count(*) as cnt
                from dispatch_task
                where tenant_id=%s and project_id=%s and collection_task_id=%s and status in ('QUEUED','RUNNING')
                """,
                (TENANT_ID, LONG_PROJECT_ID, task_id),
            )
            dispatch_count = int((cursor.fetchone() or {}).get("cnt") or 0)
            cursor.execute(
                """
                select count(*) as cnt
                from run_record
                where tenant_id=%s and project_id=%s and collection_task_id=%s and status='RUNNING'
                """,
                (TENANT_ID, LONG_PROJECT_ID, task_id),
            )
            run_count = int((cursor.fetchone() or {}).get("cnt") or 0)
    return {"dispatch": dispatch_count, "run": run_count, "total": dispatch_count + run_count}


def wait_idle(task_id: str, timeout_seconds: int = 180) -> bool:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        if active_counts(task_id)["total"] == 0:
            return True
        time.sleep(2)
    return False


def latest_run_after(task_id: str, started_at: str) -> dict[str, Any] | None:
    with mysql_connection(META_MYSQL_DATABASE) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select id, collection_task_id, status, message, started_at, ended_at,
                       collected_records, write_succeed_records, write_failed_records, failed_records
                from run_record
                where tenant_id=%s and project_id=%s and collection_task_id=%s and created_at >= %s
                order by created_at desc
                limit 1
                """,
                (TENANT_ID, LONG_PROJECT_ID, task_id, started_at),
            )
            return cursor.fetchone()


def trigger_and_wait(client: StudioClient, token: str, task_id: str, timeout_seconds: int = 240) -> tuple[dict[str, Any], dict[str, Any] | None]:
    wait_idle(task_id)
    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")
    trigger_response = client.request("POST", f"/collection-tasks/{task_id}/trigger", token=token, timeout=30)
    deadline = time.time() + timeout_seconds
    latest = None
    while time.time() < deadline:
        latest = latest_run_after(task_id, started_at)
        if latest and str(latest.get("status")) in FINAL_STATUSES:
            return trigger_response, latest
        time.sleep(3)
    return trigger_response, latest


def clean_message(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return not any(marker in text for marker in FORBIDDEN_MARKERS)


def target_contract_rows() -> list[dict[str, Any]]:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                f"""
                select contract_id, customer_name, cast(contract_amount as char) as contract_amount,
                       date_format(signed_at, '%Y-%m-%d %H:%i:%s') as signed_at,
                       risk_level
                from {TABLE_CONTRACT_TARGET}
                where contract_id like 'S09-CON-%%'
                order by contract_id
                """
            )
            return cursor.fetchall()


def target_row_count(table: str, contract_id: str) -> int:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(f"select count(*) as cnt from {table} where contract_id=%s", (contract_id,))
            return int((cursor.fetchone() or {}).get("cnt") or 0)


def rename_missing_source_column() -> bool:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            if column_exists(cursor, TABLE_MISSING_SOURCE, "contract_amount"):
                if column_exists(cursor, TABLE_MISSING_SOURCE, "contract_total_amount"):
                    cursor.execute(f"alter table {TABLE_MISSING_SOURCE} drop column contract_total_amount")
                cursor.execute(f"alter table {TABLE_MISSING_SOURCE} rename column contract_amount to contract_total_amount")
                connection.commit()
                return True
    return False


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    client = StudioClient()
    results: list[dict[str, Any]] = []
    health = read_health()
    health_ok = (
        health.get("nacos") == "OK"
        and isinstance(health.get("server"), dict)
        and health["server"].get("status") == "UP"
        and isinstance(health.get("worker"), dict)
        and health["worker"].get("status") == "UP"
    )
    add_result(
        results,
        "S09-ENV-001",
        "运行态",
        "Nacos readiness=OK，Server/Worker health=UP",
        health,
        health_ok,
    )

    token, login_response = client.login()
    if not token:
        add_result(
            results,
            "S09-AUTH-001",
            "认证",
            "admin/admin123 登录成功",
            summarize(login_response),
            False,
            need_fix=True,
        )
        payload = {"batch": batch, "results": results}
        print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
        return 1

    setup = ensure_s09_tables()
    add_result(
        results,
        "S09-MYSQL-001",
        "MySQL 结构扰动夹具",
        "专用 lt_reg_s09_* 表存在，且不清理长期数据",
        setup,
        True,
        need_fix=False,
    )

    locators = [
        TABLE_CONTRACT_SOURCE,
        TABLE_CONTRACT_TARGET,
        TABLE_REQUIRED_TARGET,
        TABLE_MISSING_SOURCE,
        TABLE_MISSING_TARGET,
    ]
    sync_response = sync_selected_models(client, token, locators)
    models = list_models(client, token)
    resolved = {locator: model_by_locator(models, locator) for locator in locators}
    missing_models = [locator for locator, model in resolved.items() if model is None]
    add_result(
        results,
        "S09-MODEL-001",
        "模型同步/新增字段",
        "模型同步后 5 张 S09 表均可见，合同源表和目标表包含 risk_level",
        {
            "sync": summarize(sync_response),
            "missingModels": missing_models,
            "sourceColumns": model_columns(resolved.get(TABLE_CONTRACT_SOURCE)),
            "targetColumns": model_columns(resolved.get(TABLE_CONTRACT_TARGET)),
        },
        is_ok(sync_response)
        and not missing_models
        and "risk_level" in model_columns(resolved.get(TABLE_CONTRACT_SOURCE))
        and "risk_level" in model_columns(resolved.get(TABLE_CONTRACT_TARGET)),
    )

    if missing_models:
        payload = {"batch": batch, "results": results}
        print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
        return 1

    mappings_contract = [
        {"sourceAlias": "contract", "sourceField": "contract_id", "targetField": "contract_id", "transformers": []},
        {"sourceAlias": "contract", "sourceField": "customer_name", "targetField": "customer_name", "transformers": []},
        {"sourceAlias": "contract", "sourceField": "contract_amount", "targetField": "contract_amount", "transformers": []},
        {"sourceAlias": "contract", "sourceField": "signed_at", "targetField": "signed_at", "transformers": []},
        {"sourceAlias": "contract", "sourceField": "risk_level", "targetField": "risk_level", "transformers": []},
    ]
    contract_save = save_task(
        client,
        token,
        TASK_CONTRACT,
        resolved[TABLE_CONTRACT_SOURCE],
        resolved[TABLE_CONTRACT_TARGET],
        mappings_contract,
        {"writeMode": "replace"},
    )
    contract_task = response_data(contract_save)
    contract_publish = publish_task(client, token, str(contract_task.get("id"))) if is_ok(contract_save) else None
    contract_trigger, contract_run = ({}, None)
    if is_ok(contract_save) and contract_task and is_ok(contract_publish):
        contract_trigger, contract_run = trigger_and_wait(client, token, str(contract_task.get("id")))
    contract_rows = target_contract_rows()
    add_result(
        results,
        "S09-COLL-001",
        "采集/加列后同步与写入",
        "加列并重新同步模型后，采集任务成功写入 risk_level 且字段值正确",
        {
            "save": summarize(contract_save),
            "publish": summarize(contract_publish),
            "trigger": summarize(contract_trigger),
            "run": contract_run,
            "targetRows": contract_rows,
        },
        is_ok(contract_save)
        and is_ok(contract_publish)
        and is_ok(contract_trigger)
        and contract_run is not None
        and contract_run.get("status") == "SUCCESS"
        and any(row.get("contract_id") == "S09-CON-001" and row.get("risk_level") == "高风险-重点跟进" for row in contract_rows)
        and any(row.get("contract_id") == "S09-CON-002" and row.get("risk_level") == "中风险-复核中" for row in contract_rows),
    )

    mappings_required = [
        {"sourceAlias": "contract", "sourceField": "contract_id", "targetField": "contract_id", "transformers": []},
        {"sourceAlias": "contract", "sourceField": "customer_name", "targetField": "customer_name", "transformers": []},
    ]
    required_save = save_task(
        client,
        token,
        TASK_REQUIRED,
        resolved[TABLE_CONTRACT_SOURCE],
        resolved[TABLE_REQUIRED_TARGET],
        mappings_required,
        {"writeMode": "insert"},
    )
    required_task = response_data(required_save)
    required_publish = publish_task(client, token, str(required_task.get("id"))) if is_ok(required_save) else None
    required_before = target_row_count(TABLE_REQUIRED_TARGET, "S09-CON-001")
    required_trigger, required_run = ({}, None)
    if is_ok(required_save) and required_task and is_ok(required_publish):
        required_trigger, required_run = trigger_and_wait(client, token, str(required_task.get("id")))
    required_after = target_row_count(TABLE_REQUIRED_TARGET, "S09-CON-001")
    required_clean = clean_message(required_run)
    add_result(
        results,
        "S09-COLL-002",
        "采集/目标表新增必填列",
        "目标表新增未映射 NOT NULL 列后，采集失败且错误可理解，不写入半成品数据",
        {
            "save": summarize(required_save),
            "publish": summarize(required_publish),
            "trigger": summarize(required_trigger),
            "run": required_run,
            "rowCountBefore": required_before,
            "rowCountAfter": required_after,
            "messageClean": required_clean,
        },
        is_ok(required_save)
        and is_ok(required_publish)
        and is_ok(required_trigger)
        and required_run is not None
        and required_run.get("status") == "FAILED"
        and required_after == required_before
        and required_clean,
        need_fix=not required_clean,
    )

    missing_mappings = [
        {"sourceAlias": "contract", "sourceField": "contract_id", "targetField": "contract_id", "transformers": []},
        {"sourceAlias": "contract", "sourceField": "customer_name", "targetField": "customer_name", "transformers": []},
        {"sourceAlias": "contract", "sourceField": "contract_amount", "targetField": "contract_amount", "transformers": []},
    ]
    missing_save = save_task(
        client,
        token,
        TASK_MISSING,
        resolved[TABLE_MISSING_SOURCE],
        resolved[TABLE_MISSING_TARGET],
        missing_mappings,
        {"writeMode": "insert"},
    )
    missing_task = response_data(missing_save)
    missing_publish = publish_task(client, token, str(missing_task.get("id"))) if is_ok(missing_save) else None
    renamed = rename_missing_source_column()
    resync_missing = sync_selected_models(client, token, [TABLE_MISSING_SOURCE])
    models_after_rename = list_models(client, token)
    missing_source_model = model_by_locator(models_after_rename, TABLE_MISSING_SOURCE)
    missing_before = target_row_count(TABLE_MISSING_TARGET, "S09-MISS-001")
    missing_trigger, missing_run = ({}, None)
    if is_ok(missing_save) and missing_task and is_ok(missing_publish):
        missing_trigger, missing_run = trigger_and_wait(client, token, str(missing_task.get("id")))
    missing_after = target_row_count(TABLE_MISSING_TARGET, "S09-MISS-001")
    missing_clean = clean_message(missing_run)
    missing_columns = model_columns(missing_source_model)
    add_result(
        results,
        "S09-COLL-003",
        "采集/源字段改名缺失",
        "源字段改名并重新同步模型后，旧映射采集失败且错误可理解，不写入半成品数据",
        {
            "save": summarize(missing_save),
            "publish": summarize(missing_publish),
            "renamed": renamed,
            "resync": summarize(resync_missing),
            "sourceColumnsAfterRename": missing_columns,
            "trigger": summarize(missing_trigger),
            "run": missing_run,
            "rowCountBefore": missing_before,
            "rowCountAfter": missing_after,
            "messageClean": missing_clean,
        },
        is_ok(missing_save)
        and is_ok(missing_publish)
        and renamed
        and is_ok(resync_missing)
        and "contract_amount" not in missing_columns
        and "contract_total_amount" in missing_columns
        and is_ok(missing_trigger)
        and missing_run is not None
        and missing_run.get("status") == "FAILED"
        and missing_after == missing_before
        and missing_clean,
        need_fix=not missing_clean,
    )

    payload = {
        "batch": batch,
        "fixtures": {
            "projectId": LONG_PROJECT_ID,
            "datasourceId": DATASOURCE_ID,
            "tables": locators,
            "tasks": {
                "contract": (contract_task or {}).get("id"),
                "requiredTarget": (required_task or {}).get("id"),
                "missingSource": (missing_task or {}).get("id"),
            },
        },
        "health": health,
        "results": results,
        "stats": {
            "PASS": sum(1 for item in results if item["result"] == "PASS"),
            "FAIL": sum(1 for item in results if item["result"] == "FAIL"),
            "BLOCKED": sum(1 for item in results if item["result"] == "BLOCKED"),
            "needFix": sum(1 for item in results if item.get("needFix")),
        },
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    return 1 if payload["stats"]["FAIL"] or payload["stats"]["needFix"] else 0


if __name__ == "__main__":
    sys.exit(main())

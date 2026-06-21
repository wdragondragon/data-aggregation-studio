#!/usr/bin/env python
"""Studio S10 schema drift probe for service, ingestion and quality paths.

The probe is data-preserving. It uses dedicated lt_reg_s10_* tables and
long-term Studio objects, then restores table structures to their runnable
baseline after each drift assertion so the objects remain reusable.
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

DATASOURCE_ID = os.getenv("STUDIO_LONG_DATASOURCE_ID", "2068077811652583425")

TABLE_SERVICE = "lt_reg_s10_contract_service"
TABLE_INGESTION = "lt_reg_s10_ingestion_target"
TABLE_QUALITY = "lt_reg_s10_quality_contract"

DATA_SERVICE_CODE = "lt_reg_s10_contract_schema_service_api"
DATA_SERVICE_NAME = "长期回归-S10合同结构扰动查询服务"
INGESTION_SERVICE_CODE = "lt_reg_s10_required_column_ingestion_api"
INGESTION_SERVICE_NAME = "长期回归-S10目标必填列接入服务"
QUALITY_RULE_CODE = "lt_reg_s10_contract_amount_completeness_rule"
QUALITY_RULE_NAME = "长期回归-S10合同金额完整性规则"
QUALITY_TASK_CODE = "lt_reg_s10_contract_amount_completeness_task"
QUALITY_TASK_NAME = "长期回归-S10合同金额完整性质量任务"

FINAL_STATUSES = {"SUCCESS", "FAILED", "CANCELLED", "TERMINATED"}
FORBIDDEN_MARKERS = ("java.", "com.jdragon", "Traceback", ".java:", "\tat ", "SQLSyntaxErrorException")


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
        data = body.get("data")
        summary: dict[str, Any] = {
            "status": response.get("status"),
            "success": body.get("success"),
            "code": body.get("code"),
            "message": body.get("message"),
        }
        if isinstance(data, dict):
            for key in ("id", "serviceName", "taskName", "ruleName", "status", "message"):
                if key in data:
                    summary[key] = data.get(key)
            summary["dataKeys"] = list(data.keys())[:12]
        elif isinstance(data, list):
            summary["dataCount"] = len(data)
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


def mysql_connection():
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=MYSQL_DATABASE,
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


def ensure_column(cursor, table: str, column: str, ddl: str) -> None:
    if not column_exists(cursor, table, column):
        cursor.execute(f"alter table {table} add column {ddl}")


def change_column(cursor, table: str, old_column: str, new_column: str, ddl: str) -> None:
    if column_exists(cursor, table, old_column):
        cursor.execute(f"alter table {table} change column {old_column} {new_column} {ddl}")


def prepare_mysql_fixtures(batch: str) -> list[str]:
    changes: list[str] = []
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                f"""
                create table if not exists {TABLE_SERVICE} (
                    contract_id varchar(32) primary key,
                    customer_name varchar(128) not null,
                    contract_amount decimal(18,2) not null,
                    signed_at datetime not null,
                    service_level varchar(32) null,
                    batch_code varchar(64) not null,
                    updated_at timestamp default current_timestamp on update current_timestamp
                ) engine=InnoDB default charset=utf8mb4
                """
            )
            if column_exists(cursor, TABLE_SERVICE, "service_grade") and not column_exists(cursor, TABLE_SERVICE, "service_level"):
                change_column(cursor, TABLE_SERVICE, "service_grade", "service_level", "varchar(32) null")
                changes.append(f"{TABLE_SERVICE}.service_grade->service_level")
            ensure_column(cursor, TABLE_SERVICE, "service_level", "service_level varchar(32) null")

            cursor.execute(
                f"""
                create table if not exists {TABLE_INGESTION} (
                    ingest_id varchar(64) primary key,
                    customer_id varchar(32) not null,
                    payload_note varchar(255) null,
                    audit_required varchar(16) null,
                    created_at timestamp default current_timestamp
                ) engine=InnoDB default charset=utf8mb4
                """
            )
            ensure_column(cursor, TABLE_INGESTION, "audit_required", "audit_required varchar(16) null")
            cursor.execute(f"update {TABLE_INGESTION} set audit_required='历史保留' where audit_required is null")
            cursor.execute(f"alter table {TABLE_INGESTION} modify column audit_required varchar(16) null")

            cursor.execute(
                f"""
                create table if not exists {TABLE_QUALITY} (
                    contract_id varchar(32) primary key,
                    customer_name varchar(128) not null,
                    contract_amount decimal(18,2) null,
                    contract_status varchar(32) not null,
                    batch_code varchar(64) not null,
                    updated_at timestamp default current_timestamp on update current_timestamp
                ) engine=InnoDB default charset=utf8mb4
                """
            )
            if column_exists(cursor, TABLE_QUALITY, "contract_total_amount") and not column_exists(cursor, TABLE_QUALITY, "contract_amount"):
                change_column(cursor, TABLE_QUALITY, "contract_total_amount", "contract_amount", "decimal(18,2) null")
                changes.append(f"{TABLE_QUALITY}.contract_total_amount->contract_amount")
            ensure_column(cursor, TABLE_QUALITY, "contract_amount", "contract_amount decimal(18,2) null")

            cursor.execute(
                f"""
                replace into {TABLE_SERVICE}
                  (contract_id, customer_name, contract_amount, signed_at, service_level, batch_code)
                values
                  ('S10-SVC-001', '华南合同履约中心', 56000.25, '2026-06-21 14:10:00', '金牌保障', %s),
                  ('S10-SVC-002', '西北区域渠道客户', 18750.00, '2026-06-21 14:40:00', '标准保障', %s)
                """,
                (batch, batch),
            )
            cursor.execute(
                f"""
                replace into {TABLE_QUALITY}
                  (contract_id, customer_name, contract_amount, contract_status, batch_code)
                values
                  ('S10-QLT-001', '华中重点续约客户', 42000.00, 'ACTIVE', 'S10-BASE'),
                  ('S10-QLT-002', '东北渠道培育客户', 8300.50, 'ACTIVE', 'S10-BASE')
                """
            )
        connection.commit()
    return changes


def restore_structures() -> None:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            if column_exists(cursor, TABLE_SERVICE, "service_grade") and not column_exists(cursor, TABLE_SERVICE, "service_level"):
                change_column(cursor, TABLE_SERVICE, "service_grade", "service_level", "varchar(32) null")
            if column_exists(cursor, TABLE_QUALITY, "contract_total_amount") and not column_exists(cursor, TABLE_QUALITY, "contract_amount"):
                change_column(cursor, TABLE_QUALITY, "contract_total_amount", "contract_amount", "decimal(18,2) null")
            if column_exists(cursor, TABLE_INGESTION, "audit_required"):
                cursor.execute(f"alter table {TABLE_INGESTION} modify column audit_required varchar(16) null")
        connection.commit()


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


def page_items(response: dict[str, Any]) -> list[dict[str, Any]]:
    data = response_data(response) or {}
    if isinstance(data, list):
        return data
    return data.get("items") or data.get("records") or []


def find_item_by_code(client: StudioClient, token: str, path: str, code_key: str, code: str) -> dict[str, Any] | None:
    response = client.request("GET", path, token=token, params={"keyword": code, "pageNo": 1, "pageSize": 50}, timeout=30)
    for item in page_items(response):
        if item.get(code_key) == code:
            return item
    return None


def resolve_data_service_fields(client: StudioClient, token: str, sql: str) -> dict[str, Any]:
    response = client.request(
        "POST",
        "/data-services/resolve-fields",
        token=token,
        json_body={"sourceType": "SQL", "datasourceId": DATASOURCE_ID, "customSql": sql},
        timeout=60,
    )
    return response


def save_data_service(client: StudioClient, token: str) -> tuple[dict[str, Any], dict[str, Any]]:
    sql = f"""
        select contract_id,
               customer_name,
               contract_amount,
               signed_at,
               service_level
        from {TABLE_SERVICE}
    """
    existing = find_item_by_code(client, token, "/data-services", "serviceCode", DATA_SERVICE_CODE)
    resolved = resolve_data_service_fields(client, token, sql)
    data = response_data(resolved) or {}
    payload = {
        "id": existing.get("id") if existing else None,
        "serviceCode": DATA_SERVICE_CODE,
        "serviceName": DATA_SERVICE_NAME,
        "serviceType": "MODEL_PUBLISH",
        "sourceType": "SQL",
        "datasourceId": DATASOURCE_ID,
        "customSql": sql,
        "requestMethod": "POST",
        "responseType": "JSON",
        "cacheEnabled": False,
        "tokenRequired": False,
        "defaultSubscriptionName": "长期回归-S10合同结构扰动免密调用方",
        "webserviceEnabled": False,
        "webserviceConfig": {"enabled": False},
        "requestParams": data.get("requestParams") or [],
        "responseParams": data.get("responseParams") or [],
        "publishParams": [],
    }
    saved = client.request("POST", "/data-services", token=token, json_body=payload, timeout=60)
    service_id = (response_data(saved) or {}).get("id")
    published = client.request("POST", f"/data-services/{service_id}/publish", token=token, timeout=60) if service_id else None
    return saved, (published or {"status": "SKIP", "body": {"message": "service not saved"}})


def save_ingestion_service(client: StudioClient, token: str, model_id: str) -> tuple[dict[str, Any], dict[str, Any]]:
    existing = find_item_by_code(client, token, "/data-ingestion-services", "serviceCode", INGESTION_SERVICE_CODE)
    mappings = [
        {
            "sortOrder": 1,
            "sourcePosition": "BODY",
            "sourceField": "ingestId",
            "targetField": "ingest_id",
            "valueType": "STRING",
            "required": True,
            "description": "接入流水号",
        },
        {
            "sortOrder": 2,
            "sourcePosition": "BODY",
            "sourceField": "customerId",
            "targetField": "customer_id",
            "valueType": "STRING",
            "required": True,
            "description": "客户编号",
        },
        {
            "sortOrder": 3,
            "sourcePosition": "BODY",
            "sourceField": "payloadNote",
            "targetField": "payload_note",
            "valueType": "STRING",
            "required": False,
            "description": "业务备注",
        },
    ]
    payload = {
        "id": existing.get("id") if existing else None,
        "serviceCode": INGESTION_SERVICE_CODE,
        "serviceName": INGESTION_SERVICE_NAME,
        "requestFormat": "JSON",
        "payloadMode": "OBJECT",
        "targetType": "DATABASE",
        "datasourceId": DATASOURCE_ID,
        "modelId": model_id,
        "maxBatchSize": 10,
        "tokenRequired": False,
        "defaultSubscriptionName": "长期回归-S10目标必填列接入免密调用方",
        "webserviceEnabled": False,
        "webserviceConfig": {"enabled": False},
        "writerOptions": {},
        "fieldMappings": mappings,
    }
    saved = client.request("POST", "/data-ingestion-services", token=token, json_body=payload, timeout=60)
    service_id = (response_data(saved) or {}).get("id")
    published = client.request("POST", f"/data-ingestion-services/{service_id}/publish", token=token, timeout=60) if service_id else None
    return saved, (published or {"status": "SKIP", "body": {"message": "service not saved"}})


def save_quality_rule_and_task(client: StudioClient, token: str, model_id: str) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    existing_rule = find_item_by_code(client, token, "/quality-rules", "ruleCode", QUALITY_RULE_CODE)
    rule_payload = {
        "id": existing_rule.get("id") if existing_rule else None,
        "ruleName": QUALITY_RULE_NAME,
        "ruleCode": QUALITY_RULE_CODE,
        "scopeType": "PROJECT",
        "ruleDimension": "COMPLETENESS",
        "description": "长期保留规则：验证字段删除/改名后质量任务错误信息是否可理解。",
        "supportedDatasourceTypes": ["mysql8"],
        "granularity": "COLUMN",
        "logicSql": "select count(*) as missing_amount_count from ${Schema_Table} where ${Column} is null",
        "enabled": True,
        "inputParams": [
            {"paramOrder": 1, "paramName": "Schema_Table", "paramType": "TABLE", "paramMeaning": "运行时替换为模型物理表名"},
            {"paramOrder": 2, "paramName": "Column", "paramType": "COLUMN", "paramMeaning": "运行时替换为合同金额字段"},
        ],
        "outputParams": [
            {
                "outputOrder": 1,
                "resultField": "missing_amount_count",
                "outputType": "NUMBER",
                "outputDescription": "合同金额缺失数",
            }
        ],
    }
    rule = client.request("POST", "/quality-rules", token=token, json_body=rule_payload, timeout=60)
    rule_id = (response_data(rule) or {}).get("id")
    existing_task = find_item_by_code(client, token, "/quality-tasks", "taskCode", QUALITY_TASK_CODE)
    task_payload = {
        "id": existing_task.get("id") if existing_task else None,
        "taskName": QUALITY_TASK_NAME,
        "taskCode": QUALITY_TASK_CODE,
        "ruleId": rule_id,
        "granularity": "COLUMN",
        "datasourceId": DATASOURCE_ID,
        "modelId": model_id,
        "columnName": "contract_amount",
        "whereClause": "batch_code = 'S10-BASE'",
        "parameterBindings": [],
        "alertConfigs": [
            {
                "outputOrder": 1,
                "resultField": "missing_amount_count",
                "outputType": "NUMBER",
                "enabled": True,
                "operator": "GT",
                "expectedValue": "0",
            }
        ],
        "schedule": {"cronExpression": "0 */30 * * * ?", "enabled": False, "timezone": "Asia/Shanghai"},
    }
    task = client.request("POST", "/quality-tasks", token=token, json_body=task_payload, timeout=60) if rule_id else {
        "status": "SKIP",
        "body": {"message": "rule not saved"},
    }
    task_id = (response_data(task) or {}).get("id")
    published = client.request("POST", f"/quality-tasks/{task_id}/online", token=token, timeout=60) if task_id else None
    return rule, task, (published or {"status": "SKIP", "body": {"message": "quality task not saved"}})


def run_ids(client: StudioClient, token: str, params: dict[str, Any]) -> set[str]:
    response = client.request("GET", "/runs", token=token, params=params, timeout=30)
    records = (response_data(response) or {}).get("runRecords") or []
    return {str(item.get("id")) for item in records if item.get("id") is not None}


def wait_for_new_run(client: StudioClient, token: str, params: dict[str, Any], before: set[str], timeout: int = 90) -> dict[str, Any] | None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        response = client.request("GET", "/runs", token=token, params=params, timeout=30)
        records = (response_data(response) or {}).get("runRecords") or []
        for record in records:
            if str(record.get("id")) not in before and str(record.get("status") or "").upper() in FINAL_STATUSES:
                return record
        time.sleep(2)
    return None


def clean_message(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return not any(marker in text for marker in FORBIDDEN_MARKERS)


def table_row_count(table: str, column: str, value: str) -> int:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(f"select count(*) as cnt from {table} where {column}=%s", (value,))
            return int((cursor.fetchone() or {}).get("cnt") or 0)


def drift_service_column() -> None:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            if column_exists(cursor, TABLE_SERVICE, "service_level"):
                change_column(cursor, TABLE_SERVICE, "service_level", "service_grade", "varchar(32) null")
        connection.commit()


def drift_ingestion_required_column() -> None:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(f"update {TABLE_INGESTION} set audit_required='历史保留' where audit_required is null")
            cursor.execute(f"alter table {TABLE_INGESTION} modify column audit_required varchar(16) not null")
        connection.commit()


def drift_quality_column() -> None:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            if column_exists(cursor, TABLE_QUALITY, "contract_amount"):
                change_column(cursor, TABLE_QUALITY, "contract_amount", "contract_total_amount", "decimal(18,2) null")
        connection.commit()


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    client = StudioClient()
    token, login = client.login()
    results: list[dict[str, Any]] = []
    fixtures: dict[str, Any] = {"batch": batch, "projectId": LONG_PROJECT_ID, "datasourceId": DATASOURCE_ID}

    health = read_health()
    health_ok = (
        health.get("nacos") == "OK"
        and isinstance(health.get("server"), dict)
        and health["server"].get("status") == "UP"
        and isinstance(health.get("worker"), dict)
        and health["worker"].get("status") == "UP"
    )
    add_result(results, "S10-ENV-001", "运行态", "Nacos readiness=OK，Server/Worker health=UP", health, health_ok)
    if not token:
        add_result(results, "S10-AUTH-001", "认证", "admin 登录成功", summarize(login), False)
        print(json.dumps({"batch": batch, "health": health, "results": results}, ensure_ascii=False, indent=2, default=str))
        return 1

    try:
        changes = prepare_mysql_fixtures(batch)
        add_result(
            results,
            "S10-MYSQL-001",
            "MySQL 结构扰动夹具",
            "专用 lt_reg_s10_* 表存在，且可重复恢复到基线结构",
            {"changes": changes, "tables": [TABLE_SERVICE, TABLE_INGESTION, TABLE_QUALITY]},
            True,
            need_fix=False,
        )

        sync = sync_selected_models(client, token, [TABLE_INGESTION, TABLE_QUALITY])
        models = list_models(client, token)
        ingestion_model = model_by_locator(models, TABLE_INGESTION)
        quality_model = model_by_locator(models, TABLE_QUALITY)
        fixtures["models"] = {
            "ingestion": (ingestion_model or {}).get("id"),
            "quality": (quality_model or {}).get("id"),
        }
        add_result(
            results,
            "S10-MODEL-001",
            "模型同步",
            "S10 接入目标表和质量表模型可同步并获取模型 ID",
            {"sync": summarize(sync), "models": fixtures["models"]},
            is_ok(sync) and ingestion_model is not None and quality_model is not None,
        )

        saved_service, published_service = save_data_service(client, token)
        service_id = (response_data(published_service) or response_data(saved_service) or {}).get("id")
        fixtures["dataServiceId"] = service_id
        service_debug_ok = client.request(
            "POST",
            f"/data-services/{service_id}/debug",
            token=token,
            json_body={"headers": {}, "query": {}, "body": {"pageNum": 1, "pageSize": 10}},
            timeout=60,
        ) if service_id else {"status": "SKIP", "body": {"message": "service not saved"}}
        service_rows = (((response_data(service_debug_ok) or {}).get("table") or {}).get("bodies") or [])
        add_result(
            results,
            "S10-SVC-001",
            "数据服务/基线查询",
            "S10 合同查询服务发布后可查询 service_level",
            {"save": summarize(saved_service), "publish": summarize(published_service), "debug": summarize(service_debug_ok), "rows": service_rows[:3]},
            is_ok(service_debug_ok) and any(row.get("service_level") == "金牌保障" for row in service_rows),
        )

        drift_service_column()
        service_debug_drift = client.request(
            "POST",
            f"/data-services/{service_id}/debug",
            token=token,
            json_body={"headers": {}, "query": {}, "body": {"pageNum": 1, "pageSize": 10}},
            timeout=60,
        ) if service_id else {"status": "SKIP", "body": {"message": "service not saved"}}
        service_error_text = json.dumps(service_debug_drift, ensure_ascii=False, default=str)
        service_clean = clean_message(service_debug_drift)
        add_result(
            results,
            "S10-SVC-002",
            "数据服务/SQL 字段改名扰动",
            "底层字段 service_level 改名后调试失败，错误可理解且不泄露内部类/堆栈",
            {"debug": summarize(service_debug_drift), "clean": service_clean},
            not is_ok(service_debug_drift) and "service_level" in service_error_text and service_clean,
            need_fix=not service_clean,
        )
        restore_structures()

        if ingestion_model is not None:
            saved_ingestion, published_ingestion = save_ingestion_service(client, token, str(ingestion_model.get("id")))
        else:
            saved_ingestion = {"status": "SKIP", "body": {"message": "ingestion model missing"}}
            published_ingestion = {"status": "SKIP", "body": {"message": "ingestion model missing"}}
        ingestion_id = (response_data(published_ingestion) or response_data(saved_ingestion) or {}).get("id")
        fixtures["ingestionServiceId"] = ingestion_id
        ingest_ok_id = f"LT-S10-OK-{batch}"
        ingestion_ok = client.request(
            "POST",
            f"/data-ingestion-services/{ingestion_id}/debug",
            token=token,
            json_body={
                "headers": {},
                "query": {},
                "form": {},
                "body": {"ingestId": ingest_ok_id, "customerId": "CUST-S10-001", "payloadNote": "S10基线成功写入"},
            },
            timeout=90,
        ) if ingestion_id else {"status": "SKIP", "body": {"message": "ingestion service not saved"}}
        add_result(
            results,
            "S10-ING-001",
            "数据接入/基线写入",
            "S10 接入服务发布后可写入目标表",
            {"save": summarize(saved_ingestion), "publish": summarize(published_ingestion), "debug": summarize(ingestion_ok), "rowCount": table_row_count(TABLE_INGESTION, "ingest_id", ingest_ok_id)},
            is_ok(ingestion_ok) and table_row_count(TABLE_INGESTION, "ingest_id", ingest_ok_id) == 1,
        )

        drift_ingestion_required_column()
        ingest_fail_id = f"LT-S10-FAIL-{batch}"
        before_fail_rows = table_row_count(TABLE_INGESTION, "ingest_id", ingest_fail_id)
        ingestion_fail = client.request(
            "POST",
            f"/data-ingestion-services/{ingestion_id}/debug",
            token=token,
            json_body={
                "headers": {},
                "query": {},
                "form": {},
                "body": {"ingestId": ingest_fail_id, "customerId": "CUST-S10-002", "payloadNote": "S10必填列扰动失败样例"},
            },
            timeout=90,
        ) if ingestion_id else {"status": "SKIP", "body": {"message": "ingestion service not saved"}}
        after_fail_rows = table_row_count(TABLE_INGESTION, "ingest_id", ingest_fail_id)
        ingestion_error_text = json.dumps(ingestion_fail, ensure_ascii=False, default=str)
        ingestion_clean = clean_message(ingestion_fail)
        add_result(
            results,
            "S10-ING-002",
            "数据接入/目标必填列扰动",
            "目标新增未映射 NOT NULL 列后写入失败，不插入半成品，错误不泄露内部类/堆栈",
            {"debug": summarize(ingestion_fail), "beforeRows": before_fail_rows, "afterRows": after_fail_rows, "clean": ingestion_clean},
            not is_ok(ingestion_fail)
            and before_fail_rows == 0
            and after_fail_rows == 0
            and "audit_required" in ingestion_error_text
            and ingestion_clean,
            need_fix=not ingestion_clean,
        )
        restore_structures()

        if quality_model is not None:
            rule, task, published_quality = save_quality_rule_and_task(client, token, str(quality_model.get("id")))
        else:
            rule = {"status": "SKIP", "body": {"message": "quality model missing"}}
            task = {"status": "SKIP", "body": {"message": "quality model missing"}}
            published_quality = {"status": "SKIP", "body": {"message": "quality model missing"}}
        quality_task_id = (response_data(published_quality) or response_data(task) or {}).get("id")
        fixtures["qualityTaskId"] = quality_task_id
        before_quality = run_ids(client, token, {"qualityTaskId": quality_task_id}) if quality_task_id else set()
        trigger_quality = client.request("POST", f"/quality-tasks/{quality_task_id}/trigger", token=token, timeout=60) if quality_task_id else {
            "status": "SKIP",
            "body": {"message": "quality task not saved"},
        }
        quality_run_ok = wait_for_new_run(client, token, {"qualityTaskId": quality_task_id}, before_quality) if quality_task_id else None
        add_result(
            results,
            "S10-QLT-001",
            "数据质量/基线执行",
            "S10 合同金额完整性质量任务发布后可成功执行",
            {"rule": summarize(rule), "task": summarize(task), "publish": summarize(published_quality), "trigger": summarize(trigger_quality), "run": quality_run_ok},
            is_ok(trigger_quality) and quality_run_ok is not None and quality_run_ok.get("status") == "SUCCESS",
        )

        drift_quality_column()
        sync_drift_quality = sync_selected_models(client, token, [TABLE_QUALITY])
        before_quality_drift = run_ids(client, token, {"qualityTaskId": quality_task_id}) if quality_task_id else set()
        trigger_quality_drift = client.request("POST", f"/quality-tasks/{quality_task_id}/trigger", token=token, timeout=60) if quality_task_id else {
            "status": "SKIP",
            "body": {"message": "quality task not saved"},
        }
        quality_run_fail = wait_for_new_run(client, token, {"qualityTaskId": quality_task_id}, before_quality_drift) if quality_task_id else None
        quality_clean = clean_message(quality_run_fail)
        quality_error_text = json.dumps(quality_run_fail, ensure_ascii=False, default=str)
        add_result(
            results,
            "S10-QLT-002",
            "数据质量/字段改名扰动",
            "质量任务绑定字段被改名后运行失败，错误可理解且不泄露内部类/堆栈",
            {"sync": summarize(sync_drift_quality), "trigger": summarize(trigger_quality_drift), "run": quality_run_fail, "clean": quality_clean},
            is_ok(trigger_quality_drift)
            and quality_run_fail is not None
            and quality_run_fail.get("status") == "FAILED"
            and "contract_amount" in quality_error_text
            and quality_clean,
            need_fix=not quality_clean,
        )
    finally:
        restore_structures()
        if token:
            sync_selected_models(client, token, [TABLE_SERVICE, TABLE_INGESTION, TABLE_QUALITY])

    payload = {
        "batch": batch,
        "fixtures": fixtures,
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

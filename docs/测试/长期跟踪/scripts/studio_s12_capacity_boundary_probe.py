#!/usr/bin/env python
"""Studio S12 capacity and boundary probe.

The probe preserves long-term data. It creates dedicated lt_reg_s12_* tables
and Studio services, then appends traceable LT-S12-* rows for each run.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any

import pymysql
import requests


API_BASE = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
OPEN_BASE = os.getenv("STUDIO_OPEN_BASE_URL", "http://127.0.0.1:18080/openapi").rstrip("/")
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
TABLE_SOURCE = "lt_reg_s12_boundary_source"
TABLE_INGEST = "lt_reg_s12_boundary_ingest"
BASE_BATCH = "S12-BASE"
SOURCE_ROWS = 1206
LONG_TEXT_MIN_LENGTH = 2000

DATA_SERVICE_CODE = "lt_reg_s12_boundary_query_api"
DATA_SERVICE_NAME = "长期回归-S12客户边界大结果集查询服务"
INGESTION_SERVICE_CODE = "lt_reg_s12_boundary_ingestion_api"
INGESTION_SERVICE_NAME = "长期回归-S12客户边界批量接入服务"

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
                API_BASE + path,
                headers=headers,
                params=params,
                json=json_body,
                timeout=timeout,
            )
            return normalize_response(response)
        except Exception as exc:
            return {"status": "EXCEPTION", "body": {"message": str(exc)}}

    def open_request(self, method: str, path: str, json_body: Any | None = None, timeout: int = 60) -> dict[str, Any]:
        try:
            response = self.session.request(
                method,
                OPEN_BASE + path,
                headers={"X-Tenant-Id": TENANT_ID},
                json=json_body,
                timeout=timeout,
            )
            return normalize_response(response)
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
        body = response.get("body")
        if isinstance(body, dict):
            token = (body.get("data") or {}).get("token")
        return token, response


def normalize_response(response: requests.Response) -> dict[str, Any]:
    try:
        body: Any = response.json()
    except Exception:
        body = response.text
    return {"status": response.status_code, "body": body}


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def is_result_ok(response: dict[str, Any]) -> bool:
    body = response.get("body")
    return response.get("status") == 200 and isinstance(body, dict) and body.get("success") is True


def is_ingestion_ok(response: dict[str, Any]) -> bool:
    body = response.get("body")
    return response.get("status") == 200 and isinstance(body, dict) and str(body.get("status") or "").upper() == "SUCCESS"


def clean_message(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return not any(marker in text for marker in FORBIDDEN_MARKERS)


def summarize(response: dict[str, Any] | None) -> dict[str, Any]:
    if response is None:
        return {"status": "SKIP"}
    body = response.get("body")
    if isinstance(body, dict):
        data = body.get("data")
        summary: dict[str, Any] = {
            "httpStatus": response.get("status"),
            "success": body.get("success"),
            "code": body.get("code"),
            "message": body.get("message"),
        }
        payload = data if isinstance(data, dict) else body
        if isinstance(payload, dict):
            for key in ("id", "serviceCode", "serviceName", "status", "requestId", "receivedCount", "successCount", "failedCount", "pageNum", "pageSize", "pages"):
                if key in payload:
                    summary[key] = payload.get(key)
            if isinstance(data, dict):
                summary["dataKeys"] = list(data.keys())[:12]
            else:
                summary["bodyKeys"] = list(body.keys())[:12]
        elif isinstance(data, list):
            summary["dataCount"] = len(data)
        return summary
    return {"httpStatus": response.get("status"), "body": str(body)[:300]}


def add_result(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    expected: str,
    actual: Any,
    passed: bool,
    need_fix: bool | None = None,
    evidence: dict[str, Any] | None = None,
    status: str | None = None,
) -> None:
    result = status or ("PASS" if passed else "FAIL")
    results.append(
        {
            "case": case_id,
            "module": module,
            "expected": expected,
            "actual": actual,
            "result": result,
            "needFix": (result == "FAIL" if need_fix is None else need_fix),
            "evidence": evidence or {},
        }
    )


def to_int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


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


def prepare_mysql_fixtures() -> dict[str, Any]:
    long_note = "长期回归S12长文本边界-" + ("客户经营边界说明。" * 260)
    unicode_note = '特殊字符：中文、空格、引号"、反斜杠\\、换行\\n、制表\\t、人民币￥、百分号%、连接符-、括号（）'
    start_time = datetime(2026, 6, 21, 9, 30, 0)
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                f"""
                create table if not exists {TABLE_SOURCE} (
                    record_id varchar(64) primary key,
                    customer_name varchar(128) not null,
                    region_name varchar(64) null,
                    boundary_amount decimal(18,4) null,
                    business_date date null,
                    event_time datetime null,
                    event_timestamp timestamp null default null,
                    nullable_note varchar(255) null,
                    empty_note varchar(255) not null default '',
                    unicode_note text null,
                    long_note text null,
                    batch_code varchar(64) not null,
                    updated_at timestamp default current_timestamp on update current_timestamp
                ) engine=InnoDB default charset=utf8mb4
                """
            )
            cursor.execute(
                f"""
                create table if not exists {TABLE_INGEST} (
                    ingest_id varchar(64) primary key,
                    customer_name varchar(128) not null,
                    boundary_amount decimal(18,4) null,
                    business_date date null,
                    event_time datetime null,
                    nullable_note varchar(255) null,
                    empty_note varchar(255) not null default '',
                    unicode_note text null,
                    long_note text null,
                    batch_code varchar(64) not null,
                    created_at timestamp default current_timestamp
                ) engine=InnoDB default charset=utf8mb4
                """
            )
            edge_values = (
                "LT-S12-SRC-0000",
                "华东边界客户-特殊字符",
                "华东",
                Decimal("123456789012.3456"),
                "2026-06-21",
                "2026-06-21 09:30:00",
                "2026-06-21 09:31:02",
                None,
                "",
                unicode_note,
                long_note,
                BASE_BATCH,
            )
            cursor.execute(
                f"""
                insert into {TABLE_SOURCE}
                  (record_id, customer_name, region_name, boundary_amount, business_date, event_time, event_timestamp,
                   nullable_note, empty_note, unicode_note, long_note, batch_code)
                values (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                on duplicate key update record_id=record_id
                """,
                edge_values,
            )
            rows = []
            for index in range(1, SOURCE_ROWS):
                current = start_time + timedelta(minutes=index)
                rows.append(
                    (
                        f"LT-S12-SRC-{index:04d}",
                        f"长期回归S12批量客户-{index:04d}",
                        "华北" if index % 2 == 0 else "华南",
                        Decimal(index) / Decimal("10.0000"),
                        current.strftime("%Y-%m-%d"),
                        current.strftime("%Y-%m-%d %H:%M:%S"),
                        current.strftime("%Y-%m-%d %H:%M:%S"),
                        f"备注-{index:04d}" if index % 5 else None,
                        "" if index % 7 == 0 else f"非空-{index:04d}",
                        f"批量边界字符-{index:04d}",
                        "常规说明",
                        BASE_BATCH,
                    )
                )
            cursor.executemany(
                f"""
                insert into {TABLE_SOURCE}
                  (record_id, customer_name, region_name, boundary_amount, business_date, event_time, event_timestamp,
                   nullable_note, empty_note, unicode_note, long_note, batch_code)
                values (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                on duplicate key update record_id=record_id
                """,
                rows,
            )
        connection.commit()
    return {"sourceRows": count_rows(TABLE_SOURCE, "batch_code", BASE_BATCH), "longNoteLength": len(long_note)}


def count_rows(table: str, column: str, value: str) -> int:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(f"select count(*) as cnt from {table} where {column}=%s", (value,))
            return int((cursor.fetchone() or {}).get("cnt") or 0)


def count_like(table: str, column: str, prefix: str) -> int:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(f"select count(*) as cnt from {table} where {column} like %s", (prefix + "%",))
            return int((cursor.fetchone() or {}).get("cnt") or 0)


def get_ingest_row(ingest_id: str) -> dict[str, Any] | None:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(f"select * from {TABLE_INGEST} where ingest_id=%s", (ingest_id,))
            return cursor.fetchone()


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


def save_data_service(client: StudioClient, token: str) -> tuple[dict[str, Any], dict[str, Any]]:
    sql = f"""
        select record_id,
               customer_name,
               region_name,
               boundary_amount,
               business_date,
               event_time,
               event_timestamp,
               nullable_note,
               empty_note,
               unicode_note,
               long_note,
               batch_code
        from {TABLE_SOURCE}
        where batch_code = '{BASE_BATCH}'
        order by record_id asc
    """
    existing = find_item_by_code(client, token, "/data-services", "serviceCode", DATA_SERVICE_CODE)
    resolved = client.request(
        "POST",
        "/data-services/resolve-fields",
        token=token,
        json_body={"sourceType": "SQL", "datasourceId": DATASOURCE_ID, "customSql": sql},
        timeout=60,
    )
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
        "defaultSubscriptionName": "长期回归-S12大结果集免密调用方",
        "webserviceEnabled": False,
        "webserviceConfig": {"enabled": False},
        "requestParams": data.get("requestParams") or [],
        "responseParams": data.get("responseParams") or [],
        "publishParams": [],
    }
    saved = client.request("POST", "/data-services", token=token, json_body=payload, timeout=60)
    service_id = (response_data(saved) or {}).get("id")
    published = client.request("POST", f"/data-services/{service_id}/publish", token=token, timeout=60) if service_id else {
        "status": "SKIP",
        "body": {"message": "service not saved"},
    }
    return saved, published


def save_ingestion_service(client: StudioClient, token: str, model_id: str) -> tuple[dict[str, Any], dict[str, Any]]:
    existing = find_item_by_code(client, token, "/data-ingestion-services", "serviceCode", INGESTION_SERVICE_CODE)
    fields = [
        ("ingestId", "ingest_id", "STRING", True, "接入流水号"),
        ("customerName", "customer_name", "STRING", True, "客户名称"),
        ("boundaryAmount", "boundary_amount", "DECIMAL", False, "边界金额"),
        ("businessDate", "business_date", "STRING", False, "业务日期"),
        ("eventTime", "event_time", "STRING", False, "事件时间"),
        ("nullableNote", "nullable_note", "STRING", False, "可空备注"),
        ("emptyNote", "empty_note", "STRING", False, "空字符串备注"),
        ("unicodeNote", "unicode_note", "STRING", False, "中文特殊字符备注"),
        ("longNote", "long_note", "STRING", False, "长文本备注"),
        ("batchCode", "batch_code", "STRING", True, "测试批次"),
    ]
    mappings = [
        {
            "sortOrder": index + 1,
            "sourcePosition": "BODY",
            "sourceField": source,
            "targetField": target,
            "valueType": value_type,
            "required": required,
            "description": description,
        }
        for index, (source, target, value_type, required, description) in enumerate(fields)
    ]
    payload = {
        "id": existing.get("id") if existing else None,
        "serviceCode": INGESTION_SERVICE_CODE,
        "serviceName": INGESTION_SERVICE_NAME,
        "requestFormat": "JSON",
        "payloadMode": "ARRAY",
        "targetType": "DATABASE",
        "datasourceId": DATASOURCE_ID,
        "modelId": model_id,
        "maxBatchSize": 500,
        "tokenRequired": False,
        "defaultSubscriptionName": "长期回归-S12批量接入免密调用方",
        "webserviceEnabled": False,
        "webserviceConfig": {"enabled": False},
        "writerOptions": {},
        "fieldMappings": mappings,
    }
    saved = client.request("POST", "/data-ingestion-services", token=token, json_body=payload, timeout=60)
    service_id = (response_data(saved) or {}).get("id")
    published = client.request("POST", f"/data-ingestion-services/{service_id}/publish", token=token, timeout=60) if service_id else {
        "status": "SKIP",
        "body": {"message": "service not saved"},
    }
    return saved, published


def data_service_open_call(client: StudioClient, service: dict[str, Any], body: dict[str, Any]) -> dict[str, Any]:
    return client.open_request(
        "POST",
        f"/data-services/{service.get('serviceCode')}/{service.get('serviceKey')}",
        json_body=body,
        timeout=90,
    )


def ingestion_open_call(client: StudioClient, service: dict[str, Any], body: Any) -> dict[str, Any]:
    return client.open_request(
        "POST",
        f"/data-ingestion-services/{service.get('serviceCode')}/{service.get('serviceKey')}",
        json_body=body,
        timeout=120,
    )


def service_rows(response: dict[str, Any]) -> list[dict[str, Any]]:
    data = response_data(response) or {}
    return ((data.get("table") or {}).get("bodies") or []) if isinstance(data, dict) else []


def build_ingestion_rows(prefix: str, batch: str, size: int) -> list[dict[str, Any]]:
    long_note = "S12批量接入长文本-" + ("边界写入说明。" * 80)
    rows = []
    for index in range(size):
        rows.append(
            {
                "ingestId": f"{prefix}{index:03d}",
                "customerName": f"长期回归S12接入客户-{index:03d}",
                "boundaryAmount": str(Decimal("1000.1234") + Decimal(index) / Decimal("10")),
                "businessDate": "2026-06-21",
                "eventTime": f"2026-06-21 10:{index % 60:02d}:00",
                "nullableNote": None if index % 4 == 0 else f"可空备注-{index:03d}",
                "emptyNote": "" if index % 3 == 0 else f"非空备注-{index:03d}",
                "unicodeNote": f"中文特殊字符-{index:03d}-引号\"-反斜杠\\-人民币￥",
                "longNote": long_note,
                "batchCode": batch,
            }
        )
    return rows


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    results: list[dict[str, Any]] = []
    client = StudioClient()
    token, login = client.login()

    health = read_health()
    health_ok = (
        health.get("nacos") == "OK"
        and isinstance(health.get("server"), dict)
        and health["server"].get("status") == "UP"
        and isinstance(health.get("worker"), dict)
        and health["worker"].get("status") == "UP"
    )
    add_result(results, "S12-ENV-001", "运行态", "Nacos readiness=OK，Server/Worker health=UP", health, health_ok)
    if not token:
        add_result(results, "S12-AUTH-001", "认证", "admin 登录成功", summarize(login), False)
        print(json.dumps({"batch": batch, "health": health, "results": results}, ensure_ascii=False, indent=2, default=str))
        return 1

    fixture_info = prepare_mysql_fixtures()
    add_result(
        results,
        "S12-MYSQL-001",
        "MySQL 容量边界夹具",
        f"{TABLE_SOURCE} 固定 {SOURCE_ROWS} 行，{TABLE_INGEST} 可写入",
        fixture_info,
        fixture_info.get("sourceRows") == SOURCE_ROWS and fixture_info.get("longNoteLength", 0) >= LONG_TEXT_MIN_LENGTH,
        need_fix=False,
    )

    sync = sync_selected_models(client, token, [TABLE_INGEST])
    models = list_models(client, token)
    ingest_model = model_by_locator(models, TABLE_INGEST)
    add_result(
        results,
        "S12-MODEL-001",
        "模型同步",
        "S12 接入目标表模型可同步并获取模型 ID",
        {"sync": summarize(sync), "modelId": (ingest_model or {}).get("id")},
        is_result_ok(sync) and ingest_model is not None,
    )

    service_saved, service_published = save_data_service(client, token)
    service_id = (response_data(service_published) or response_data(service_saved) or {}).get("id")
    service_detail = client.request("GET", f"/data-services/{service_id}", token=token, timeout=30) if service_id else {
        "status": "SKIP",
        "body": {"message": "service not saved"},
    }
    service = response_data(service_detail) or {}
    page1 = data_service_open_call(client, service, {"pageNum": 1, "pageSize": 1500}) if service else {"status": "SKIP", "body": {}}
    page1_data = response_data(page1) or {}
    rows1 = service_rows(page1)
    first_row = rows1[0] if rows1 else {}
    page1_page_size = to_int(page1_data.get("pageSize"))
    page1_pages = to_int(page1_data.get("pages"))
    add_result(
        results,
        "S12-SVC-001",
        "数据服务/大结果集上限",
        "请求 pageSize=1500 时按上限返回 pageSize=1000、1000 行、2 页",
        {
            "save": summarize(service_saved),
            "publish": summarize(service_published),
            "page": summarize(page1),
            "rowCount": len(rows1),
            "firstRow": {k: first_row.get(k) for k in ("record_id", "customer_name", "nullable_note", "empty_note")},
        },
        is_result_ok(page1)
        and page1_page_size == 1000
        and page1_pages == 2
        and len(rows1) == 1000,
    )

    add_result(
        results,
        "S12-SVC-002",
        "数据服务/特殊字符空值长文本",
        "首行保留 NULL、空字符串、中文特殊字符和长文本",
        {
            "recordId": first_row.get("record_id"),
            "nullableNote": first_row.get("nullable_note"),
            "emptyNote": first_row.get("empty_note"),
            "unicodeNote": first_row.get("unicode_note"),
            "longNoteLength": len(str(first_row.get("long_note") or "")),
            "businessDate": first_row.get("business_date"),
            "eventTime": first_row.get("event_time"),
        },
        first_row.get("record_id") == "LT-S12-SRC-0000"
        and first_row.get("nullable_note") is None
        and first_row.get("empty_note") == ""
        and "引号" in str(first_row.get("unicode_note") or "")
        and len(str(first_row.get("long_note") or "")) >= LONG_TEXT_MIN_LENGTH,
    )

    page2 = data_service_open_call(client, service, {"pageNum": 2, "pageSize": 1000}) if service else {"status": "SKIP", "body": {}}
    rows2 = service_rows(page2)
    add_result(
        results,
        "S12-SVC-003",
        "数据服务/第二页边界",
        "pageNum=2,pageSize=1000 返回余量 206 行且无内部错误",
        {"page": summarize(page2), "rowCount": len(rows2), "clean": clean_message(page2)},
        is_result_ok(page2) and len(rows2) == SOURCE_ROWS - 1000 and clean_message(page2),
        need_fix=not clean_message(page2),
    )

    if ingest_model is not None:
        ingestion_saved, ingestion_published = save_ingestion_service(client, token, str(ingest_model.get("id")))
    else:
        ingestion_saved = {"status": "SKIP", "body": {"message": "model missing"}}
        ingestion_published = {"status": "SKIP", "body": {"message": "model missing"}}
    ingestion_id = (response_data(ingestion_published) or response_data(ingestion_saved) or {}).get("id")
    ingestion_detail = client.request("GET", f"/data-ingestion-services/{ingestion_id}", token=token, timeout=30) if ingestion_id else {
        "status": "SKIP",
        "body": {"message": "ingestion not saved"},
    }
    ingestion = response_data(ingestion_detail) or {}

    insert_batch_code = f"S12-BATCH-{batch}"
    batch_prefix = f"LT-S12-ING-{batch}-"
    rows_before = count_like(TABLE_INGEST, "ingest_id", batch_prefix)
    batch_rows = build_ingestion_rows(batch_prefix, insert_batch_code, 500)
    ingestion_batch = ingestion_open_call(client, ingestion, batch_rows) if ingestion else {"status": "SKIP", "body": {}}
    rows_after = count_like(TABLE_INGEST, "ingest_id", batch_prefix)
    sample = get_ingest_row(f"{batch_prefix}000")
    add_result(
        results,
        "S12-ING-001",
        "数据接入/500行批量边界",
        "500 行批量接入成功，目标表写入数一致，NULL/空字符串/中文特殊字符保留",
        {
            "save": summarize(ingestion_saved),
            "publish": summarize(ingestion_published),
            "invoke": summarize(ingestion_batch),
            "rowsBefore": rows_before,
            "rowsAfter": rows_after,
            "sample": {
                "ingest_id": (sample or {}).get("ingest_id"),
                "nullable_note": (sample or {}).get("nullable_note"),
                "empty_note": (sample or {}).get("empty_note"),
                "unicode_note": (sample or {}).get("unicode_note"),
                "boundary_amount": str((sample or {}).get("boundary_amount")),
            },
        },
        is_ingestion_ok(ingestion_batch)
        and rows_before == 0
        and rows_after == 500
        and sample is not None
        and sample.get("nullable_note") is None
        and sample.get("empty_note") == ""
        and "人民币" in str(sample.get("unicode_note") or ""),
    )

    over_prefix = f"LT-S12-OVER-{batch}-"
    over_before = count_like(TABLE_INGEST, "ingest_id", over_prefix)
    over_rows = build_ingestion_rows(over_prefix, f"S12-OVER-{batch}", 501)
    over_response = ingestion_open_call(client, ingestion, over_rows) if ingestion else {"status": "SKIP", "body": {}}
    over_after = count_like(TABLE_INGEST, "ingest_id", over_prefix)
    over_text = json.dumps(over_response, ensure_ascii=False, default=str)
    add_result(
        results,
        "S12-ING-002",
        "数据接入/超过批量上限",
        "501 行超过 maxBatchSize=500 时拒绝写入，错误可理解且无内部泄露",
        {"invoke": summarize(over_response), "rowsBefore": over_before, "rowsAfter": over_after, "clean": clean_message(over_response)},
        over_response.get("status") == 400
        and "exceeds max batch size: 500" in over_text
        and over_before == 0
        and over_after == 0
        and clean_message(over_response),
        need_fix=not clean_message(over_response),
    )

    dup_id = f"{batch_prefix}000"
    if rows_after == 500:
        dup_response = ingestion_open_call(client, ingestion, [dict(batch_rows[0])]) if ingestion else {"status": "SKIP", "body": {}}
        dup_count = count_rows(TABLE_INGEST, "ingest_id", dup_id)
        add_result(
            results,
            "S12-ING-003",
            "数据接入/重复主键",
            "重复主键按 insert 语义失败，已有行保持 1 条，错误可理解且无内部泄露",
            {"invoke": summarize(dup_response), "rowCount": dup_count, "clean": clean_message(dup_response)},
            dup_response.get("status") in (400, 500)
            and dup_count == 1
            and "Duplicate" in json.dumps(dup_response, ensure_ascii=False, default=str)
            and clean_message(dup_response),
            need_fix=not clean_message(dup_response),
        )
    else:
        add_result(
            results,
            "S12-ING-003",
            "数据接入/重复主键",
            "重复主键依赖 500 行批量接入先成功写入首行",
            {"batchRowCount": rows_after, "blockedReason": "500 行批量接入前置未成功，避免用例连锁误判"},
            False,
            need_fix=False,
            status="BLOCKED",
        )

    invalid_id = f"LT-S12-BAD-AMOUNT-{batch}"
    invalid_before = count_rows(TABLE_INGEST, "ingest_id", invalid_id)
    invalid_row = dict(batch_rows[0])
    invalid_row["ingestId"] = invalid_id
    invalid_row["boundaryAmount"] = "金额不是数字"
    invalid_response = ingestion_open_call(client, ingestion, [invalid_row]) if ingestion else {"status": "SKIP", "body": {}}
    invalid_after = count_rows(TABLE_INGEST, "ingest_id", invalid_id)
    add_result(
        results,
        "S12-ING-004",
        "数据接入/非法金额转换",
        "非法 DECIMAL 值拒绝写入，错误可理解且无内部泄露",
        {"invoke": summarize(invalid_response), "rowsBefore": invalid_before, "rowsAfter": invalid_after, "clean": clean_message(invalid_response)},
        invalid_response.get("status") == 400
        and invalid_before == 0
        and invalid_after == 0
        and "Field value conversion failed" in json.dumps(invalid_response, ensure_ascii=False, default=str)
        and clean_message(invalid_response),
        need_fix=not clean_message(invalid_response),
    )

    payload = {
        "batch": batch,
        "fixtures": {
            "projectId": LONG_PROJECT_ID,
            "datasourceId": DATASOURCE_ID,
            "sourceTable": TABLE_SOURCE,
            "ingestionTable": TABLE_INGEST,
            "dataServiceId": service_id,
            "ingestionServiceId": ingestion_id,
            "ingestionModelId": (ingest_model or {}).get("id"),
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

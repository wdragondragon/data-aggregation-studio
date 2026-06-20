#!/usr/bin/env python
"""Studio S02 data correctness and idempotency probe.

The probe is data-preserving. It appends long-term ingestion samples and run
records, then reconciles Studio API results with the long-term MySQL database.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime
from decimal import Decimal
from typing import Any, Callable

import pymysql
import requests


BASE_URL = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
OPEN_BASE_URL = os.getenv("STUDIO_OPEN_BASE_URL", "http://127.0.0.1:18080").rstrip("/")
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
MODEL_CUSTOMER_ORDER_ID = os.getenv("STUDIO_MODEL_CUSTOMER_ORDER_ID", "2068089468038635522")
MODEL_INGESTION_ID = os.getenv("STUDIO_MODEL_INGESTION_ID", "2068089464502837249")
DATA_SERVICE_ORDER_ID = os.getenv("STUDIO_DATA_SERVICE_ORDER_ID", "2068117382251651073")
INGESTION_SERVICE_ID = os.getenv("STUDIO_INGESTION_SERVICE_ID", "2068130320853168129")
COLLECTION_INCREMENTAL_ID = os.getenv("STUDIO_COLLECTION_INCREMENTAL_ID", "2068153332268711937")
COLLECTION_FUSION_ID = os.getenv("STUDIO_COLLECTION_FUSION_ID", "2068153361725308929")
QUALITY_ROW_TASK_ID = os.getenv("STUDIO_QUALITY_ROW_TASK_ID", "2068278671531704322")
WORKFLOW_ID = os.getenv("STUDIO_WORKFLOW_ID", "2068289624298184705")

FINAL_STATUSES = {"SUCCESS", "FAILED", "CANCELLED", "TERMINATED"}


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

    def open_request(self, method: str, path: str, json_body: Any | None = None, timeout: int = 30) -> dict[str, Any]:
        try:
            response = self.session.request(method, OPEN_BASE_URL + path, json=json_body, timeout=timeout)
            try:
                body: Any = response.json()
            except Exception:
                body = response.text
            return {"status": response.status_code, "body": body}
        except Exception as exc:
            return {"status": "EXCEPTION", "body": {"message": str(exc)}}

    def login(self, username: str, password: str) -> tuple[str | None, dict[str, Any]]:
        response = self.request(
            "POST",
            "/auth/login",
            project_id=LONG_PROJECT_ID,
            json_body={"username": username, "password": password},
        )
        token = None
        if isinstance(response["body"], dict):
            token = (response["body"].get("data") or {}).get("token")
        return token, response


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def is_ok(response: dict[str, Any]) -> bool:
    return response.get("status") == 200 and isinstance(response.get("body"), dict) and response["body"].get("success") is True


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
            for key in ("status", "message", "endpointPath", "serviceCode", "serviceName"):
                if key in payload:
                    summary[key] = payload.get(key)
        return summary
    return {"status": response.get("status"), "body": str(body)[:500]}


def clean_message(response: dict[str, Any]) -> bool:
    text = json.dumps(response.get("body"), ensure_ascii=False, default=str)
    forbidden = ("java.", "com.jdragon", "at ", ".java:", "Traceback", "DataIngestionExecutionSupport")
    return not any(marker in text for marker in forbidden)


def add_result(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    expectation: Callable[[Any], bool],
    evidence: Any,
    extra: dict[str, Any] | None = None,
    need_fix: bool | None = None,
) -> None:
    passed = False
    try:
        passed = expectation(evidence)
    except Exception as exc:
        extra = dict(extra or {})
        extra["expectationError"] = str(exc)
    results.append(
        {
            "case": case_id,
            "module": module,
            "actual": summarize(evidence) if isinstance(evidence, dict) and "body" in evidence else evidence,
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
            "extra": extra or {},
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
    )


def decimal_str(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, Decimal):
        return format(value.quantize(Decimal("0.01")), "f")
    return format(Decimal(str(value)).quantize(Decimal("0.01")), "f")


def datetime_str(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    return str(value)


def fetch_mysql_baseline() -> dict[str, Any]:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select count(*) as row_count,
                       count(distinct customer_id) as customer_count,
                       coalesce(sum(account_balance), 0) as balance_sum
                from lt_reg_customer_profile
                """
            )
            customer_profile = cursor.fetchone()
            cursor.execute(
                """
                select count(*) as row_count,
                       count(distinct order_id) as order_count,
                       coalesce(sum(order_amount), 0) as amount_sum,
                       min(order_time) as min_order_time,
                       max(order_time) as max_order_time
                from lt_reg_customer_order
                """
            )
            customer_order = cursor.fetchone()
            cursor.execute(
                """
                select count(*) as row_count,
                       count(distinct order_id) as order_count,
                       coalesce(sum(order_amount), 0) as amount_sum,
                       min(order_time) as min_order_time,
                       max(order_time) as max_order_time
                from lt_reg_order_incremental_result
                """
            )
            incremental = cursor.fetchone()
            cursor.execute(
                """
                select count(*) as row_count,
                       count(distinct order_id) as order_count,
                       coalesce(sum(order_amount), 0) as amount_sum,
                       coalesce(sum(pay_amount), 0) as pay_sum
                from lt_reg_order_payment_fusion_result
                """
            )
            fusion = cursor.fetchone()
            cursor.execute(
                """
                select count(*) as row_count,
                       sum(case when mobile is null or trim(mobile) = '' then 1 else 0 end) as missing_mobile,
                       count(*) - count(distinct duplicate_key) as duplicate_key_delta
                from lt_reg_quality_exception_sample
                """
            )
            quality = cursor.fetchone()
            cursor.execute(
                """
                select count(*) as row_count,
                       count(distinct ingest_id) as ingest_count,
                       max(received_at) as max_received_at
                from lt_reg_api_ingestion_result
                """
            )
            ingestion = cursor.fetchone()
            cursor.execute(
                """
                select order_id, customer_id, order_amount, order_status, order_time
                from lt_reg_customer_order
                order by order_id
                """
            )
            source_orders = cursor.fetchall()
            cursor.execute(
                """
                select order_id, customer_id, order_amount, order_status, order_time
                from lt_reg_order_incremental_result
                order by order_id
                """
            )
            incremental_orders = cursor.fetchall()
            cursor.execute(
                """
                select order_id, payment_amount, pay_method, pay_time
                from lt_reg_order_payment
                order by order_id
                """
            )
            payments = cursor.fetchall()
            cursor.execute(
                """
                select order_id, customer_id, order_amount, order_status, pay_amount, pay_channel, pay_time
                from lt_reg_order_payment_fusion_result
                order by order_id
                """
            )
            fusion_rows = cursor.fetchall()

    return {
        "customerProfile": normalize_row(customer_profile),
        "customerOrder": normalize_row(customer_order),
        "incremental": normalize_row(incremental),
        "fusion": normalize_row(fusion),
        "quality": normalize_row(quality),
        "ingestion": normalize_row(ingestion),
        "sourceOrders": [normalize_row(row) for row in source_orders],
        "incrementalOrders": [normalize_row(row) for row in incremental_orders],
        "payments": [normalize_row(row) for row in payments],
        "fusionRows": [normalize_row(row) for row in fusion_rows],
    }


def normalize_row(row: dict[str, Any] | None) -> dict[str, Any]:
    if not row:
        return {}
    normalized: dict[str, Any] = {}
    for key, value in row.items():
        if isinstance(value, Decimal):
            normalized[key] = decimal_str(value)
        elif isinstance(value, datetime):
            normalized[key] = datetime_str(value)
        else:
            normalized[key] = value
    return normalized


def compare_incremental_orders(baseline: dict[str, Any]) -> dict[str, Any]:
    source_by_id = {row["order_id"]: row for row in baseline["sourceOrders"]}
    target_by_id = {row["order_id"]: row for row in baseline["incrementalOrders"]}
    missing = sorted(set(source_by_id) - set(target_by_id))
    extra = sorted(set(target_by_id) - set(source_by_id))
    diffs = []
    for order_id in sorted(set(source_by_id) & set(target_by_id)):
        source = source_by_id[order_id]
        target = target_by_id[order_id]
        for field in ("customer_id", "order_status", "order_time"):
            if source.get(field) != target.get(field):
                diffs.append({"orderId": order_id, "field": field, "source": source.get(field), "target": target.get(field)})
        if decimal_str(source.get("order_amount")) != decimal_str(target.get("order_amount")):
            diffs.append(
                {
                    "orderId": order_id,
                    "field": "order_amount",
                    "source": decimal_str(source.get("order_amount")),
                    "target": decimal_str(target.get("order_amount")),
                }
            )
    return {"missing": missing, "extra": extra, "diffs": diffs}


def compare_fusion_rows(baseline: dict[str, Any]) -> dict[str, Any]:
    source_by_id = {row["order_id"]: row for row in baseline["sourceOrders"]}
    payment_by_id = {row["order_id"]: row for row in baseline["payments"]}
    target_by_id = {row["order_id"]: row for row in baseline["fusionRows"]}
    missing = sorted(set(source_by_id) - set(target_by_id))
    extra = sorted(set(target_by_id) - set(source_by_id))
    diffs = []
    null_payment_orders = []
    for order_id in sorted(set(source_by_id) & set(target_by_id)):
        source = source_by_id[order_id]
        payment = payment_by_id.get(order_id, {})
        target = target_by_id[order_id]
        expected = {
            "customer_id": source.get("customer_id"),
            "order_status": source.get("order_status"),
            "order_amount": decimal_str(source.get("order_amount")),
            "pay_amount": decimal_str(payment.get("payment_amount")),
            "pay_channel": payment.get("pay_method"),
            "pay_time": datetime_str(payment.get("pay_time")),
        }
        actual = {
            "customer_id": target.get("customer_id"),
            "order_status": target.get("order_status"),
            "order_amount": decimal_str(target.get("order_amount")),
            "pay_amount": decimal_str(target.get("pay_amount")),
            "pay_channel": target.get("pay_channel"),
            "pay_time": datetime_str(target.get("pay_time")),
        }
        if not payment:
            null_payment_orders.append(order_id)
        for field, expected_value in expected.items():
            if actual.get(field) != expected_value:
                diffs.append({"orderId": order_id, "field": field, "expected": expected_value, "actual": actual.get(field)})
    return {"missing": missing, "extra": extra, "diffs": diffs, "nullPaymentOrders": null_payment_orders}


def wait_for_new_run(
    client: StudioClient,
    token: str,
    params: dict[str, Any],
    before_ids: set[str],
    timeout_seconds: int = 120,
) -> dict[str, Any] | None:
    deadline = time.time() + timeout_seconds
    latest: dict[str, Any] | None = None
    while time.time() < deadline:
        response = client.request("GET", "/runs", token, LONG_PROJECT_ID, params=dict(params, includeRunRecords="true"), timeout=20)
        records = (response_data(response) or {}).get("runRecords") or []
        for record in records:
            record_id = str(record.get("id"))
            if record_id not in before_ids:
                latest = record
                if str(record.get("status")) in FINAL_STATUSES:
                    return record
        time.sleep(3)
    return latest


def wait_for_new_workflow_run(
    client: StudioClient,
    token: str,
    before_ids: set[str],
    timeout_seconds: int = 180,
) -> dict[str, Any] | None:
    deadline = time.time() + timeout_seconds
    latest: dict[str, Any] | None = None
    while time.time() < deadline:
        response = client.request(
            "GET",
            "/workflow-runs",
            token,
            LONG_PROJECT_ID,
            params={"workflowDefinitionId": WORKFLOW_ID, "pageNo": 1, "pageSize": 10},
            timeout=20,
        )
        records = (response_data(response) or {}).get("items") or []
        for record in records:
            record_id = str(record.get("workflowRunId"))
            if record_id not in before_ids:
                latest = record
                if str(record.get("status")) in FINAL_STATUSES:
                    return record
        time.sleep(4)
    return latest


def run_ids(client: StudioClient, token: str, params: dict[str, Any]) -> set[str]:
    response = client.request("GET", "/runs", token, LONG_PROJECT_ID, params=dict(params, includeRunRecords="true"), timeout=20)
    return {str(item.get("id")) for item in ((response_data(response) or {}).get("runRecords") or [])}


def workflow_run_ids(client: StudioClient, token: str) -> set[str]:
    response = client.request(
        "GET",
        "/workflow-runs",
        token,
        LONG_PROJECT_ID,
        params={"workflowDefinitionId": WORKFLOW_ID, "pageNo": 1, "pageSize": 20},
        timeout=20,
    )
    return {str(item.get("workflowRunId")) for item in ((response_data(response) or {}).get("items") or [])}


def read_ingestion_row(ingest_id: str) -> dict[str, Any] | None:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select ingest_id, customer_id, tag_name, tag_value, request_source,
                       cast(raw_payload as char) as raw_payload,
                       received_at
                from lt_reg_api_ingestion_result
                where ingest_id = %s
                """,
                (ingest_id,),
            )
            return normalize_row(cursor.fetchone())


def main() -> int:
    client = StudioClient()
    admin_token, login_response = client.login(ADMIN_USER, ADMIN_PASSWORD)
    if not admin_token:
        print(json.dumps({"fatal": "admin login failed", "login": summarize(login_response)}, ensure_ascii=False, indent=2))
        return 2

    results: list[dict[str, Any]] = []
    health = read_health()
    add_result(
        results,
        "S02-ENV-001",
        "运行态健康",
        lambda value: value.get("nacos") == "OK"
        and value.get("server", {}).get("status") == "UP"
        and value.get("worker", {}).get("status") == "UP",
        health,
        need_fix=False,
    )

    datasource_test = client.request("POST", f"/datasources/{DATASOURCE_ID}/test", admin_token, LONG_PROJECT_ID, timeout=40)
    add_result(
        results,
        "S02-DS-001",
        "数据源连接",
        lambda response: is_ok(response) and (response_data(response) or {}).get("success") is True,
        datasource_test,
        {"url": f"POST /datasources/{DATASOURCE_ID}/test"},
    )
    discover = client.request(
        "POST",
        f"/datasources/{DATASOURCE_ID}/discover",
        admin_token,
        LONG_PROJECT_ID,
        params={"keyword": "lt_reg_customer_order", "pageNo": 1, "pageSize": 5},
        timeout=60,
    )
    discovered_names = [item.get("name") for item in ((response_data(discover) or {}).get("models") or [])]
    add_result(
        results,
        "S02-DS-002",
        "数据源发现",
        lambda response: is_ok(response) and "lt_reg_customer_order" in discovered_names,
        discover,
        {"discoveredNames": discovered_names},
    )
    sync_selected = client.request(
        "POST",
        f"/models/datasource/{DATASOURCE_ID}/sync-selected",
        admin_token,
        LONG_PROJECT_ID,
        json_body={"physicalLocators": ["lt_reg_customer_order"]},
        timeout=90,
    )
    sync_names = [item.get("name") for item in (response_data(sync_selected) or [])]
    add_result(
        results,
        "S02-MODEL-001",
        "模型选表同步幂等",
        lambda response: is_ok(response) and "lt_reg_customer_order" in sync_names,
        sync_selected,
        {"syncNames": sync_names},
    )
    model_preview = client.request("GET", f"/models/{MODEL_CUSTOMER_ORDER_ID}/preview", admin_token, LONG_PROJECT_ID, params={"limit": 6})
    preview_rows = response_data(model_preview) or []
    add_result(
        results,
        "S02-MODEL-002",
        "模型预览数据",
        lambda response: is_ok(response) and any(row.get("order_id") == "ORD-LT-001" for row in preview_rows),
        model_preview,
        {"previewCount": len(preview_rows)},
    )

    data_service_detail = client.request("GET", f"/data-services/{DATA_SERVICE_ORDER_ID}", admin_token, LONG_PROJECT_ID)
    service = response_data(data_service_detail) or {}
    data_service_debug = client.request(
        "POST",
        f"/data-services/{DATA_SERVICE_ORDER_ID}/debug",
        admin_token,
        LONG_PROJECT_ID,
        json_body={"headers": {}, "query": {}, "body": {"customerId": "CUST-LT-001", "orderStatus": "PAID", "pageNum": 1, "pageSize": 10}},
        timeout=60,
    )
    debug_rows = (((response_data(data_service_debug) or {}).get("table") or {}).get("bodies") or [])
    add_result(
        results,
        "S02-SVC-001",
        "数据服务调试查询",
        lambda response: is_ok(response)
        and any(row.get("order_id") == "ORD-LT-001" and decimal_str(row.get("order_amount")) == "1899.00" for row in debug_rows)
        and any(row.get("order_id") == "ORD-M06-20260620-001" and decimal_str(row.get("order_amount")) == "199.90" for row in debug_rows),
        data_service_debug,
        {"rowCount": len(debug_rows), "serviceName": service.get("serviceName")},
    )
    open_path = service.get("endpointPath")
    open_response = client.open_request(
        "POST",
        str(open_path),
        json_body={"customerId": "CUST-LT-001", "orderStatus": "PAID", "pageNum": 1, "pageSize": 10},
        timeout=60,
    ) if open_path else None
    open_rows = (((response_data(open_response or {}) or {}).get("table") or {}).get("bodies") or [])
    add_result(
        results,
        "S02-SVC-002",
        "免 Token 开放服务调用",
        lambda response: open_response is not None and is_ok(response) and any(row.get("order_id") == "ORD-LT-001" for row in open_rows),
        open_response,
        {"endpointPath": open_path, "rowCount": len(open_rows)},
    )

    batch_code = datetime.now().strftime("%Y%m%d%H%M%S")
    ingest_id = f"LT-S02-{batch_code}"
    ingestion_body = {
        "ingestId": ingest_id,
        "customerId": "CUST-LT-001",
        "tagName": "S02幂等性客户分层标签",
        "tagValue": "高价值复购客户",
        "rawPayload": {"业务含义": "第二阶段数据正确性探针", "测试批次": batch_code},
    }
    ingestion_query = {"requestSource": "长期回归S02专项调试"}
    ingestion_debug = client.request(
        "POST",
        f"/data-ingestion-services/{INGESTION_SERVICE_ID}/debug",
        admin_token,
        LONG_PROJECT_ID,
        json_body={"headers": {}, "query": ingestion_query, "form": {}, "body": ingestion_body},
        timeout=90,
    )
    inserted_row = read_ingestion_row(ingest_id)
    add_result(
        results,
        "S02-ING-001",
        "数据接入写入",
        lambda response: is_ok(response)
        and inserted_row is not None
        and inserted_row.get("tag_name") == "S02幂等性客户分层标签"
        and inserted_row.get("tag_value") == "高价值复购客户"
        and inserted_row.get("request_source") == "长期回归S02专项调试",
        ingestion_debug,
        {"ingestId": ingest_id, "insertedRow": inserted_row},
    )
    duplicate_debug = client.request(
        "POST",
        f"/data-ingestion-services/{INGESTION_SERVICE_ID}/debug",
        admin_token,
        LONG_PROJECT_ID,
        json_body={"headers": {}, "query": ingestion_query, "form": {}, "body": dict(ingestion_body, tagValue="重复写入不应覆盖")},
        timeout=90,
    )
    duplicate_row = read_ingestion_row(ingest_id)
    add_result(
        results,
        "S02-ING-002",
        "数据接入重复主键幂等边界",
        lambda response: not is_ok(response)
        and clean_message(response)
        and duplicate_row is not None
        and duplicate_row.get("tag_value") == "高价值复购客户",
        duplicate_debug,
        {"ingestId": ingest_id, "rowAfterDuplicate": duplicate_row},
        need_fix=False,
    )

    before_incremental = run_ids(client, admin_token, {"collectionTaskId": COLLECTION_INCREMENTAL_ID})
    trigger_incremental = client.request("POST", f"/collection-tasks/{COLLECTION_INCREMENTAL_ID}/trigger", admin_token, LONG_PROJECT_ID)
    incremental_run = wait_for_new_run(client, admin_token, {"collectionTaskId": COLLECTION_INCREMENTAL_ID}, before_incremental)
    add_result(
        results,
        "S02-COLL-001",
        "增量采集重复执行",
        lambda response: is_ok(response) and incremental_run is not None and incremental_run.get("status") == "SUCCESS",
        trigger_incremental,
        {"newRun": incremental_run},
    )
    before_fusion = run_ids(client, admin_token, {"collectionTaskId": COLLECTION_FUSION_ID})
    trigger_fusion = client.request("POST", f"/collection-tasks/{COLLECTION_FUSION_ID}/trigger", admin_token, LONG_PROJECT_ID)
    fusion_run = wait_for_new_run(client, admin_token, {"collectionTaskId": COLLECTION_FUSION_ID}, before_fusion)
    add_result(
        results,
        "S02-COLL-002",
        "融合采集重复执行",
        lambda response: is_ok(response) and fusion_run is not None and fusion_run.get("status") == "SUCCESS",
        trigger_fusion,
        {"newRun": fusion_run},
    )
    before_quality = run_ids(client, admin_token, {"qualityTaskId": QUALITY_ROW_TASK_ID})
    trigger_quality = client.request("POST", f"/quality-tasks/{QUALITY_ROW_TASK_ID}/trigger", admin_token, LONG_PROJECT_ID)
    quality_run = wait_for_new_run(client, admin_token, {"qualityTaskId": QUALITY_ROW_TASK_ID}, before_quality)
    add_result(
        results,
        "S02-QUALITY-001",
        "质量任务重复执行",
        lambda response: is_ok(response)
        and quality_run is not None
        and quality_run.get("status") == "SUCCESS"
        and ((quality_run.get("resultJson") or {}).get("alertCount") in (0, "0", None)),
        trigger_quality,
        {"newRun": quality_run},
    )
    quality_dashboard = client.request("POST", "/quality-metrics/dashboard/query", admin_token, LONG_PROJECT_ID, json_body={})
    summary_metrics = (response_data(quality_dashboard) or {}).get("summaryMetrics") or {}
    add_result(
        results,
        "S02-QUALITY-002",
        "质量指标基线",
        lambda response: is_ok(response) and int(summary_metrics.get("activeIssueCount") or 0) >= 2,
        quality_dashboard,
        {"summaryMetrics": summary_metrics},
    )

    before_workflow = workflow_run_ids(client, admin_token)
    trigger_workflow = client.request("POST", f"/workflows/{WORKFLOW_ID}/trigger", admin_token, LONG_PROJECT_ID)
    workflow_run = wait_for_new_workflow_run(client, admin_token, before_workflow)
    add_result(
        results,
        "S02-WF-001",
        "工作流重复执行",
        lambda response: is_ok(response)
        and workflow_run is not None
        and workflow_run.get("status") == "SUCCESS"
        and workflow_run.get("successNodes") == 3,
        trigger_workflow,
        {"newWorkflowRun": workflow_run},
    )

    baseline = fetch_mysql_baseline()
    incremental_diff = compare_incremental_orders(baseline)
    fusion_diff = compare_fusion_rows(baseline)
    add_result(
        results,
        "S02-MYSQL-001",
        "MySQL 源目标行数金额对账",
        lambda value: value["customerOrder"]["row_count"] == value["incremental"]["row_count"]
        and value["customerOrder"]["order_count"] == value["incremental"]["order_count"]
        and value["customerOrder"]["amount_sum"] == value["incremental"]["amount_sum"]
        and value["customerOrder"]["row_count"] == value["fusion"]["row_count"],
        baseline,
    )
    add_result(
        results,
        "S02-MYSQL-002",
        "增量采集字段级对账",
        lambda value: not value["missing"] and not value["extra"] and not value["diffs"],
        incremental_diff,
    )
    add_result(
        results,
        "S02-MYSQL-003",
        "融合采集 join 对账",
        lambda value: not value["missing"] and not value["extra"] and not value["diffs"],
        fusion_diff,
        {"nullPaymentOrders": fusion_diff.get("nullPaymentOrders")},
    )

    output = {
        "executedAt": datetime.now().isoformat(timespec="seconds"),
        "fixtures": {
            "datasourceId": DATASOURCE_ID,
            "dataServiceId": DATA_SERVICE_ORDER_ID,
            "ingestionServiceId": INGESTION_SERVICE_ID,
            "collectionIncrementalId": COLLECTION_INCREMENTAL_ID,
            "collectionFusionId": COLLECTION_FUSION_ID,
            "qualityTaskId": QUALITY_ROW_TASK_ID,
            "workflowId": WORKFLOW_ID,
            "newIngestId": ingest_id,
        },
        "results": results,
        "stats": {
            "PASS": sum(1 for item in results if item["result"] == "PASS"),
            "FAIL": sum(1 for item in results if item["result"] == "FAIL"),
            "BLOCKED": sum(1 for item in results if item["result"] == "BLOCKED"),
            "needFix": sum(1 for item in results if item.get("needFix")),
        },
        "health": health,
        "mysqlBaseline": baseline,
        "mysqlDiffs": {
            "incremental": incremental_diff,
            "fusion": fusion_diff,
        },
    }
    print(json.dumps(output, ensure_ascii=False, indent=2, default=str))
    return 1 if output["stats"]["FAIL"] or output["stats"]["needFix"] else 0


if __name__ == "__main__":
    sys.exit(main())

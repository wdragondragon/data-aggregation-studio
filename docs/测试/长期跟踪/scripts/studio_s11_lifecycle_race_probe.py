#!/usr/bin/env python
"""Studio S11 lifecycle race probe.

This probe keeps long-term data. It reuses S10 service/quality objects and
adds S11 invocation rows only. At the end it republishes services/tasks so the
objects stay reusable for later regression.
"""

from __future__ import annotations

import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
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

DATA_SERVICE_ID = os.getenv("STUDIO_S11_DATA_SERVICE_ID", "2068704693544779778")
INGESTION_SERVICE_ID = os.getenv("STUDIO_S11_INGESTION_SERVICE_ID", "2068704728974065665")
QUALITY_TASK_ID = os.getenv("STUDIO_S11_QUALITY_TASK_ID", "2068704774037667842")

INGESTION_TABLE = "lt_reg_s10_ingestion_target"
FINAL_STATUSES = {"SUCCESS", "FAILED", "CANCELLED", "TERMINATED"}
FORBIDDEN_MARKERS = ("java.", "com.jdragon", "Traceback", ".java:", "\tat ", "SQLSyntaxErrorException")
EXPECTED_UNAVAILABLE = ("not available", "offline", "未发布", "不可用")


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

    def open_request(
        self,
        method: str,
        path: str,
        json_body: Any | None = None,
        timeout: int = 30,
    ) -> dict[str, Any]:
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


def is_ok(response: dict[str, Any]) -> bool:
    body = response.get("body")
    if response.get("status") != 200 or not isinstance(body, dict):
        return False
    if body.get("success") is True:
        return True
    return str(body.get("status") or "").upper() == "SUCCESS"


def clean_message(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return not any(marker in text for marker in FORBIDDEN_MARKERS)


def message_text(response: dict[str, Any]) -> str:
    body = response.get("body")
    if isinstance(body, dict):
        return str(body.get("message") or body.get("code") or "")
    return str(body)


def expected_unavailable(response: dict[str, Any]) -> bool:
    if is_ok(response):
        return False
    text = json.dumps(response, ensure_ascii=False, default=str).lower()
    return any(marker.lower() in text for marker in EXPECTED_UNAVAILABLE) or response.get("status") in (400, 404)


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
        payload = data if isinstance(data, dict) else body
        if isinstance(payload, dict):
            for key in ("id", "serviceCode", "serviceName", "taskCode", "taskName", "status", "requestId", "successCount", "failedCount"):
                if key in payload:
                    summary[key] = payload.get(key)
            if isinstance(data, dict):
                summary["dataKeys"] = list(data.keys())[:12]
            else:
                summary["bodyKeys"] = list(body.keys())[:12]
        elif isinstance(data, list):
            summary["dataCount"] = len(data)
        return summary
    return {"status": response.get("status"), "body": str(body)[:300]}


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
        autocommit=True,
    )


def ingestion_row_count(prefix: str) -> int:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(f"select count(*) as cnt from {INGESTION_TABLE} where ingest_id like %s", (prefix + "%",))
            return int((cursor.fetchone() or {}).get("cnt") or 0)


def detail(client: StudioClient, token: str, path: str, object_id: str) -> dict[str, Any]:
    return client.request("GET", f"{path}/{object_id}", token=token, timeout=30)


def data_service_open_call(client: StudioClient, service: dict[str, Any]) -> dict[str, Any]:
    return client.open_request(
        "POST",
        f"/data-services/{service.get('serviceCode')}/{service.get('serviceKey')}",
        json_body={"pageNum": 1, "pageSize": 5},
        timeout=60,
    )


def ingestion_open_call(client: StudioClient, service: dict[str, Any], ingest_id: str) -> dict[str, Any]:
    return client.open_request(
        "POST",
        f"/data-ingestion-services/{service.get('serviceCode')}/{service.get('serviceKey')}",
        json_body={
            "ingestId": ingest_id,
            "customerId": "CUST-S11-LIFECYCLE",
            "payloadNote": "S11生命周期交错并发写入样例",
        },
        timeout=60,
    )


def worker_call(token: str, operation: dict[str, Any]) -> dict[str, Any]:
    local_client = StudioClient()
    time.sleep(float(operation.get("delay", 0)) / 1000.0)
    kind = operation["kind"]
    target = operation["target"]
    if target == "data-service":
        service_id = DATA_SERVICE_ID
        if kind in ("publish", "offline"):
            return local_client.request("POST", f"/data-services/{service_id}/{kind}", token=token, timeout=60)
        return data_service_open_call(local_client, operation["service"])
    if target == "ingestion-service":
        service_id = INGESTION_SERVICE_ID
        if kind in ("publish", "offline"):
            return local_client.request("POST", f"/data-ingestion-services/{service_id}/{kind}", token=token, timeout=60)
        return ingestion_open_call(local_client, operation["service"], operation["ingestId"])
    if target == "quality-task":
        task_id = QUALITY_TASK_ID
        if kind == "online":
            return local_client.request("POST", f"/quality-tasks/{task_id}/online", token=token, timeout=60)
        return local_client.request("POST", f"/quality-tasks/{task_id}/trigger", token=token, timeout=60)
    return {"status": "UNKNOWN", "body": {"message": f"Unsupported operation: {operation}"}}


def run_concurrent(token: str, operations: list[dict[str, Any]], max_workers: int = 12) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(worker_call, token, op): op for op in operations}
        for future in as_completed(futures):
            op = futures[future]
            try:
                response = future.result()
            except Exception as exc:
                response = {"status": "EXCEPTION", "body": {"message": str(exc)}}
            results.append({"operation": op, "response": response})
    results.sort(key=lambda item: item["operation"]["name"])
    return results


def run_ids(client: StudioClient, token: str, params: dict[str, Any]) -> set[str]:
    response = client.request("GET", "/runs", token=token, params=params, timeout=30)
    records = (response_data(response) or {}).get("runRecords") or []
    return {str(item.get("id")) for item in records if item.get("id") is not None}


def wait_quality_runs(client: StudioClient, token: str, before: set[str], timeout: int = 90) -> list[dict[str, Any]]:
    deadline = time.time() + timeout
    observed: list[dict[str, Any]] = []
    while time.time() < deadline:
        response = client.request("GET", "/runs", token=token, params={"qualityTaskId": QUALITY_TASK_ID}, timeout=30)
        records = (response_data(response) or {}).get("runRecords") or []
        observed = [item for item in records if str(item.get("id")) not in before]
        if observed and all(str(item.get("status") or "").upper() in FINAL_STATUSES for item in observed):
            return observed
        time.sleep(2)
    return observed


def operation_stats(items: list[dict[str, Any]]) -> dict[str, Any]:
    stats = {
        "total": len(items),
        "success": 0,
        "unavailableOrBadRequest": 0,
        "unexpected": [],
        "dirty": [],
    }
    for item in items:
        response = item["response"]
        if is_ok(response):
            stats["success"] += 1
        elif expected_unavailable(response):
            stats["unavailableOrBadRequest"] += 1
        else:
            stats["unexpected"].append({"operation": item["operation"], "response": summarize(response)})
        if not clean_message(response):
            stats["dirty"].append({"operation": item["operation"], "response": summarize(response)})
    return stats


def build_service_operations(service: dict[str, Any], target: str, batch: str) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    for index in range(8):
        operations.append({"name": f"{target}-offline-{index:02d}", "target": target, "kind": "offline", "delay": index * 9})
        operations.append({"name": f"{target}-publish-{index:02d}", "target": target, "kind": "publish", "delay": index * 9 + 3})
        invoke: dict[str, Any] = {
            "name": f"{target}-invoke-{index:02d}",
            "target": target,
            "kind": "invoke",
            "delay": index * 9 + 6,
            "service": service,
        }
        if target == "ingestion-service":
            invoke["ingestId"] = f"LT-S11-ING-{batch}-{index:02d}"
        operations.append(invoke)
    return operations


def build_quality_operations(batch: str) -> list[dict[str, Any]]:
    operations: list[dict[str, Any]] = []
    for index in range(6):
        operations.append({"name": f"quality-online-{index:02d}", "target": "quality-task", "kind": "online", "delay": index * 12})
        operations.append({"name": f"quality-trigger-{index:02d}", "target": "quality-task", "kind": "trigger", "delay": index * 12 + 4, "batch": batch})
    return operations


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
    add_result(results, "S11-ENV-001", "运行态", "Nacos readiness=OK，Server/Worker health=UP", health, health_ok)
    if not token:
        add_result(results, "S11-AUTH-001", "认证", "admin 登录成功", summarize(login), False)
        print(json.dumps({"batch": batch, "health": health, "results": results}, ensure_ascii=False, indent=2, default=str))
        return 1

    service_detail = detail(client, token, "/data-services", DATA_SERVICE_ID)
    ingestion_detail = detail(client, token, "/data-ingestion-services", INGESTION_SERVICE_ID)
    quality_detail = detail(client, token, "/quality-tasks", QUALITY_TASK_ID)
    service = response_data(service_detail) or {}
    ingestion = response_data(ingestion_detail) or {}
    quality = response_data(quality_detail) or {}
    add_result(
        results,
        "S11-BASE-001",
        "长期对象基线",
        "S10 数据服务、接入服务、质量任务详情均可读取且 serviceKey 存在",
        {
            "dataService": summarize(service_detail),
            "ingestionService": summarize(ingestion_detail),
            "qualityTask": summarize(quality_detail),
        },
        is_ok(service_detail)
        and is_ok(ingestion_detail)
        and is_ok(quality_detail)
        and bool(service.get("serviceKey"))
        and bool(ingestion.get("serviceKey")),
    )

    try:
        client.request("POST", f"/data-services/{DATA_SERVICE_ID}/publish", token=token, timeout=60)
        client.request("POST", f"/data-ingestion-services/{INGESTION_SERVICE_ID}/publish", token=token, timeout=60)
        client.request("POST", f"/quality-tasks/{QUALITY_TASK_ID}/online", token=token, timeout=60)

        offline_service = client.request("POST", f"/data-services/{DATA_SERVICE_ID}/offline", token=token, timeout=60)
        service_offline_call = data_service_open_call(client, service)
        service_republish = client.request("POST", f"/data-services/{DATA_SERVICE_ID}/publish", token=token, timeout=60)
        add_result(
            results,
            "S11-SVC-001",
            "数据服务/下线开放调用",
            "下线后开放 API 不可调用，错误可理解且最终可重新发布",
            {
                "offline": summarize(offline_service),
                "openCall": summarize(service_offline_call),
                "republish": summarize(service_republish),
            },
            is_ok(offline_service)
            and expected_unavailable(service_offline_call)
            and clean_message(service_offline_call)
            and is_ok(service_republish),
            need_fix=is_ok(service_offline_call) or not clean_message(service_offline_call),
        )

        offline_ingestion = client.request("POST", f"/data-ingestion-services/{INGESTION_SERVICE_ID}/offline", token=token, timeout=60)
        offline_ingest_id = f"LT-S11-OFFLINE-{batch}"
        rows_before_offline = ingestion_row_count(offline_ingest_id)
        ingestion_offline_call = ingestion_open_call(client, ingestion, offline_ingest_id)
        rows_after_offline = ingestion_row_count(offline_ingest_id)
        ingestion_republish = client.request("POST", f"/data-ingestion-services/{INGESTION_SERVICE_ID}/publish", token=token, timeout=60)
        add_result(
            results,
            "S11-ING-001",
            "数据接入/下线开放调用",
            "下线后开放 API 不可写入，目标表不新增半成品，错误可理解且最终可重新发布",
            {
                "offline": summarize(offline_ingestion),
                "openCall": summarize(ingestion_offline_call),
                "rowsBefore": rows_before_offline,
                "rowsAfter": rows_after_offline,
                "republish": summarize(ingestion_republish),
            },
            is_ok(offline_ingestion)
            and expected_unavailable(ingestion_offline_call)
            and rows_before_offline == rows_after_offline
            and clean_message(ingestion_offline_call)
            and is_ok(ingestion_republish),
            need_fix=is_ok(ingestion_offline_call) or rows_before_offline != rows_after_offline or not clean_message(ingestion_offline_call),
        )

        service_ops = build_service_operations(service, "data-service", batch)
        service_race = run_concurrent(token, service_ops, max_workers=12)
        service_stats = operation_stats(service_race)
        service_final_publish = client.request("POST", f"/data-services/{DATA_SERVICE_ID}/publish", token=token, timeout=60)
        service_final_call = data_service_open_call(client, service)
        add_result(
            results,
            "S11-SVC-002",
            "数据服务/发布下线调用并发",
            "发布/下线/开放调用交错时无 500、无内部错误泄露，最终 ONLINE 且可调用",
            {
                "stats": service_stats,
                "finalPublish": summarize(service_final_publish),
                "finalCall": summarize(service_final_call),
            },
            not service_stats["unexpected"]
            and not service_stats["dirty"]
            and is_ok(service_final_publish)
            and is_ok(service_final_call),
            need_fix=bool(service_stats["unexpected"] or service_stats["dirty"]) or not is_ok(service_final_publish) or not is_ok(service_final_call),
        )

        ingestion_prefix = f"LT-S11-ING-{batch}-"
        rows_before_race = ingestion_row_count(ingestion_prefix)
        ingestion_ops = build_service_operations(ingestion, "ingestion-service", batch)
        ingestion_race = run_concurrent(token, ingestion_ops, max_workers=12)
        ingestion_stats = operation_stats(ingestion_race)
        ingestion_success_ids = [
            item["operation"].get("ingestId")
            for item in ingestion_race
            if item["operation"].get("kind") == "invoke" and is_ok(item["response"])
        ]
        rows_after_race = ingestion_row_count(ingestion_prefix)
        ingestion_final_publish = client.request("POST", f"/data-ingestion-services/{INGESTION_SERVICE_ID}/publish", token=token, timeout=60)
        ingestion_final_id = f"LT-S11-FINAL-{batch}"
        ingestion_final_call = ingestion_open_call(client, ingestion, ingestion_final_id)
        add_result(
            results,
            "S11-ING-002",
            "数据接入/发布下线写入并发",
            "发布/下线/开放写入交错时无 500、无内部错误泄露，成功写入数与目标表一致，最终可写入",
            {
                "stats": ingestion_stats,
                "successIds": ingestion_success_ids,
                "rowsBefore": rows_before_race,
                "rowsAfter": rows_after_race,
                "finalPublish": summarize(ingestion_final_publish),
                "finalCall": summarize(ingestion_final_call),
            },
            not ingestion_stats["unexpected"]
            and not ingestion_stats["dirty"]
            and rows_after_race - rows_before_race == len(ingestion_success_ids)
            and is_ok(ingestion_final_publish)
            and is_ok(ingestion_final_call),
            need_fix=bool(ingestion_stats["unexpected"] or ingestion_stats["dirty"])
            or rows_after_race - rows_before_race != len(ingestion_success_ids)
            or not is_ok(ingestion_final_publish)
            or not is_ok(ingestion_final_call),
        )

        before_quality = run_ids(client, token, {"qualityTaskId": QUALITY_TASK_ID})
        quality_ops = build_quality_operations(batch)
        quality_race = run_concurrent(token, quality_ops, max_workers=8)
        quality_stats = operation_stats(quality_race)
        quality_runs = wait_quality_runs(client, token, before_quality, timeout=90)
        quality_final_online = client.request("POST", f"/quality-tasks/{QUALITY_TASK_ID}/online", token=token, timeout=60)
        quality_trigger_success = sum(1 for item in quality_race if item["operation"]["kind"] == "trigger" and is_ok(item["response"]))
        clean_runs = clean_message(quality_runs)
        final_runs = all(str(item.get("status") or "").upper() in FINAL_STATUSES for item in quality_runs)
        add_result(
            results,
            "S11-QLT-001",
            "数据质量/上线触发并发",
            "质量任务上线/触发交错时无 500、无内部错误泄露，新运行最终收敛且数量不超过成功触发数",
            {
                "stats": quality_stats,
                "triggerSuccess": quality_trigger_success,
                "newRuns": quality_runs,
                "finalOnline": summarize(quality_final_online),
            },
            not quality_stats["unexpected"]
            and not quality_stats["dirty"]
            and clean_runs
            and final_runs
            and len(quality_runs) <= quality_trigger_success
            and is_ok(quality_final_online),
            need_fix=bool(quality_stats["unexpected"] or quality_stats["dirty"])
            or not clean_runs
            or not final_runs
            or len(quality_runs) > quality_trigger_success
            or not is_ok(quality_final_online),
        )
    finally:
        if token:
            client.request("POST", f"/data-services/{DATA_SERVICE_ID}/publish", token=token, timeout=60)
            client.request("POST", f"/data-ingestion-services/{INGESTION_SERVICE_ID}/publish", token=token, timeout=60)
            client.request("POST", f"/quality-tasks/{QUALITY_TASK_ID}/online", token=token, timeout=60)

    payload = {
        "batch": batch,
        "fixtures": {
            "projectId": LONG_PROJECT_ID,
            "dataServiceId": DATA_SERVICE_ID,
            "ingestionServiceId": INGESTION_SERVICE_ID,
            "qualityTaskId": QUALITY_TASK_ID,
            "ingestionTable": INGESTION_TABLE,
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

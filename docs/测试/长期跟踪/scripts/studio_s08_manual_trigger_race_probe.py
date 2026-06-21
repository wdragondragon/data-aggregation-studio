#!/usr/bin/env python
"""Studio S08 manual trigger race probe.

The probe is data-preserving. It reuses long-term collection, quality, and
workflow objects, fires concurrent manual trigger requests, and reconciles the
result with Studio metadata tables. Historical runs are never deleted.
"""

from __future__ import annotations

import concurrent.futures
import json
import os
import sys
import threading
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
THREADS = int(os.getenv("STUDIO_S08_THREADS", "8"))

NACOS_READINESS = os.getenv(
    "STUDIO_NACOS_READINESS",
    "http://127.0.0.1:8848/nacos/v1/console/health/readiness",
)
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")

META_MYSQL_HOST = os.getenv("STUDIO_META_MYSQL_HOST", "8.140.247.113")
META_MYSQL_PORT = int(os.getenv("STUDIO_META_MYSQL_PORT", "13309"))
META_MYSQL_USER = os.getenv("STUDIO_META_MYSQL_USER", "root")
META_MYSQL_PASSWORD = os.getenv("STUDIO_META_MYSQL_PASSWORD", "951753")
META_MYSQL_DATABASE = os.getenv("STUDIO_META_MYSQL_DATABASE", "data_aggregation_studio")

COLLECTION_INCREMENTAL_ID = os.getenv("STUDIO_COLLECTION_INCREMENTAL_ID", "2068153332268711937")
QUALITY_ROW_TASK_ID = os.getenv("STUDIO_QUALITY_ROW_TASK_ID", "2068278671531704322")
WORKFLOW_ID = os.getenv("STUDIO_WORKFLOW_ID", "2068289624298184705")

TARGETS = [
    {
        "kind": "collection",
        "case": "S08-COLL-001",
        "module": "数据采集/手动触发并发",
        "name": "长期回归-客户订单增量采集任务",
        "path": f"/collection-tasks/{COLLECTION_INCREMENTAL_ID}/trigger",
        "scopeColumn": "collection_task_id",
        "scopeId": COLLECTION_INCREMENTAL_ID,
        "workflow": False,
    },
    {
        "kind": "quality",
        "case": "S08-QUALITY-001",
        "module": "数据质量/手动触发并发",
        "name": "长期回归-客户质量行数校验任务",
        "path": f"/quality-tasks/{QUALITY_ROW_TASK_ID}/trigger",
        "scopeColumn": "quality_task_id",
        "scopeId": QUALITY_ROW_TASK_ID,
        "workflow": False,
    },
    {
        "kind": "workflow",
        "case": "S08-WF-001",
        "module": "工作流/手动触发并发",
        "name": "长期回归-客户经营数据日处理流程",
        "path": f"/workflows/{WORKFLOW_ID}/trigger",
        "scopeColumn": "workflow_definition_id",
        "scopeId": WORKFLOW_ID,
        "workflow": True,
    },
]


class StudioClient:
    def __init__(self) -> None:
        self.session = requests.Session()

    def request(
        self,
        method: str,
        path: str,
        token: str | None = None,
        project_id: str | None = LONG_PROJECT_ID,
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


def summarize_response(response: dict[str, Any]) -> dict[str, Any]:
    body = response.get("body")
    if isinstance(body, dict):
        return {
            "status": response.get("status"),
            "success": body.get("success"),
            "code": body.get("code"),
            "message": body.get("message"),
        }
    return {"status": response.get("status"), "body": str(body)[:300]}


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


def health_result(health: dict[str, Any]) -> dict[str, Any]:
    passed = (
        health.get("nacos") == "OK"
        and isinstance(health.get("server"), dict)
        and health["server"].get("status") == "UP"
        and isinstance(health.get("worker"), dict)
        and health["worker"].get("status") == "UP"
    )
    return {
        "case": "S08-ENV-001",
        "module": "运行态",
        "expected": "Nacos readiness=OK，Server/Worker health=UP",
        "actual": health,
        "result": "PASS" if passed else "FAIL",
        "needFix": not passed,
    }


def meta_connection():
    return pymysql.connect(
        host=META_MYSQL_HOST,
        port=META_MYSQL_PORT,
        user=META_MYSQL_USER,
        password=META_MYSQL_PASSWORD,
        database=META_MYSQL_DATABASE,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )


def active_counts(target: dict[str, Any]) -> dict[str, int]:
    scope_column = target["scopeColumn"]
    scope_id = target["scopeId"]
    with meta_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                f"""
                select count(*) as cnt
                from dispatch_task
                where tenant_id = %s
                  and project_id = %s
                  and {scope_column} = %s
                  and status in ('QUEUED', 'RUNNING')
                """,
                (TENANT_ID, LONG_PROJECT_ID, scope_id),
            )
            dispatch_count = int((cursor.fetchone() or {}).get("cnt") or 0)
            cursor.execute(
                f"""
                select count(*) as cnt
                from run_record
                where tenant_id = %s
                  and project_id = %s
                  and {scope_column} = %s
                  and status = 'RUNNING'
                """,
                (TENANT_ID, LONG_PROJECT_ID, scope_id),
            )
            run_count = int((cursor.fetchone() or {}).get("cnt") or 0)
    return {"dispatch": dispatch_count, "run": run_count, "total": dispatch_count + run_count}


def wait_until_idle(target: dict[str, Any], timeout_seconds: int = 240) -> dict[str, Any]:
    deadline = time.time() + timeout_seconds
    last = active_counts(target)
    while time.time() < deadline:
        if last["total"] == 0:
            return {"idle": True, "active": last}
        time.sleep(3)
        last = active_counts(target)
    return {"idle": False, "active": last}


def trigger_metrics(target: dict[str, Any], started_at: str) -> dict[str, Any]:
    scope_column = target["scopeColumn"]
    scope_id = target["scopeId"]
    with meta_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                f"""
                select count(*) as dispatch_count,
                       count(distinct coalesce(workflow_run_id, id)) as dispatch_unit_count,
                       min(created_at) as first_created_at,
                       max(created_at) as last_created_at
                from dispatch_task
                where tenant_id = %s
                  and project_id = %s
                  and {scope_column} = %s
                  and created_at >= %s
                """,
                (TENANT_ID, LONG_PROJECT_ID, scope_id, started_at),
            )
            dispatch = cursor.fetchone() or {}
            if target["workflow"]:
                cursor.execute(
                    f"""
                    select count(*) as run_count,
                           count(distinct workflow_run_id) as run_unit_count,
                           min(created_at) as first_created_at,
                           max(created_at) as last_created_at
                    from run_record
                    where tenant_id = %s
                      and project_id = %s
                      and {scope_column} = %s
                      and created_at >= %s
                    """,
                    (TENANT_ID, LONG_PROJECT_ID, scope_id, started_at),
                )
            else:
                cursor.execute(
                    f"""
                    select count(*) as run_count,
                           count(*) as run_unit_count,
                           min(created_at) as first_created_at,
                           max(created_at) as last_created_at
                    from run_record
                    where tenant_id = %s
                      and project_id = %s
                      and {scope_column} = %s
                      and created_at >= %s
                    """,
                    (TENANT_ID, LONG_PROJECT_ID, scope_id, started_at),
                )
            run = cursor.fetchone() or {}
    return {"dispatch": dispatch, "run": run}


def concurrent_trigger(path: str, token: str) -> list[dict[str, Any]]:
    barrier = threading.Barrier(THREADS)

    def worker(index: int) -> dict[str, Any]:
        session = requests.Session()
        headers = {
            "X-Tenant-Id": TENANT_ID,
            "X-Project-Id": str(LONG_PROJECT_ID),
            "Authorization": "Bearer " + token,
        }
        barrier.wait()
        started = time.perf_counter()
        try:
            response = session.post(BASE_URL + path, headers=headers, timeout=30)
            try:
                body: Any = response.json()
            except Exception:
                body = response.text
            result = {"status": response.status_code, "body": body}
        except Exception as exc:
            result = {"status": "EXCEPTION", "body": {"message": str(exc)}}
        return {
            "index": index,
            "elapsedMs": round((time.perf_counter() - started) * 1000, 2),
            "response": summarize_response(result),
        }

    with concurrent.futures.ThreadPoolExecutor(max_workers=THREADS) as executor:
        return list(executor.map(worker, range(THREADS)))


def probe_target(target: dict[str, Any], token: str) -> dict[str, Any]:
    idle = wait_until_idle(target)
    if not idle["idle"]:
        return {
            "case": target["case"],
            "module": target["module"],
            "targetName": target["name"],
            "expected": "触发前目标对象无活跃调度，避免把历史运行误判为本轮并发缺陷",
            "actual": idle,
            "result": "BLOCKED",
            "needFix": False,
        }

    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")
    responses = concurrent_trigger(target["path"], token)
    time.sleep(3)
    metrics = trigger_metrics(target, started_at)
    ok_count = sum(1 for item in responses if item["response"].get("status") == 200 and item["response"].get("success") is True)
    bad_request_count = sum(1 for item in responses if item["response"].get("status") == 400)
    dispatch_units = int(metrics["dispatch"].get("dispatch_unit_count") or 0)
    run_units = int(metrics["run"].get("run_unit_count") or 0)
    passed = ok_count == 1 and bad_request_count == THREADS - 1 and dispatch_units == 1 and run_units <= 1

    return {
        "case": target["case"],
        "module": target["module"],
        "targetName": target["name"],
        "expected": "同一对象并发手动触发时最多 1 个请求成功，其余返回 BAD_REQUEST，元数据库只产生 1 个调度单元",
        "actual": {
            "threadCount": THREADS,
            "okCount": ok_count,
            "badRequestCount": bad_request_count,
            "dispatchUnitCount": dispatch_units,
            "runUnitCount": run_units,
            "metrics": metrics,
            "responses": responses,
        },
        "result": "PASS" if passed else "FAIL",
        "needFix": not passed,
    }


def main() -> int:
    client = StudioClient()
    health = read_health()
    token, login_response = client.login()
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    results: list[dict[str, Any]] = [health_result(health)]

    if not token:
        results.append(
            {
                "case": "S08-AUTH-001",
                "module": "认证",
                "expected": "admin/admin123 登录成功",
                "actual": summarize_response(login_response),
                "result": "FAIL",
                "needFix": True,
            }
        )
    else:
        for target in TARGETS:
            results.append(probe_target(target, token))

    payload = {
        "batch": batch,
        "threadCount": THREADS,
        "fixtures": {
            "projectId": LONG_PROJECT_ID,
            "collectionTaskId": COLLECTION_INCREMENTAL_ID,
            "qualityTaskId": QUALITY_ROW_TASK_ID,
            "workflowId": WORKFLOW_ID,
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

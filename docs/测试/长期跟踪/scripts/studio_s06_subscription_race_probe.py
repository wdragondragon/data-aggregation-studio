#!/usr/bin/env python
"""Studio S06 subscription concurrency race probe.

The probe is data-preserving. It creates long-term subscription fixtures with
meaningful Chinese names and never deletes historical test data.
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

import requests


BASE_URL = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")
THREADS = int(os.getenv("STUDIO_S06_THREADS", "16"))

NACOS_READINESS = os.getenv(
    "STUDIO_NACOS_READINESS",
    "http://127.0.0.1:8848/nacos/v1/console/health/readiness",
)
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")

TARGETS = [
    {
        "kind": "data-service",
        "module": "数据服务/订阅",
        "basePath": "/data-services",
        "serviceId": os.getenv("STUDIO_DATA_SERVICE_ORDER_ID", "2068117382251651073"),
        "namePrefix": "长期回归-S06数据服务并发订阅创建",
        "expectedMode": "idempotent-single-active",
    },
    {
        "kind": "data-ingestion",
        "module": "数据接入/订阅",
        "basePath": "/data-ingestion-services",
        "serviceId": os.getenv("STUDIO_INGESTION_SERVICE_ID", "2068130320853168129"),
        "namePrefix": "长期回归-S06接入服务并发订阅创建",
        "expectedMode": "reject-duplicates",
    },
    {
        "kind": "protocol-conversion",
        "module": "协议转换/订阅",
        "basePath": "/protocol-conversions",
        "serviceId": os.getenv("STUDIO_PROTOCOL_CONVERSION_ID", "2068145027555180546"),
        "namePrefix": "长期回归-S06协议转换并发订阅创建",
        "expectedMode": "reject-duplicates",
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


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def is_ok(response: dict[str, Any]) -> bool:
    return response.get("status") == 200 and isinstance(response.get("body"), dict) and response["body"].get("success") is True


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
        "case": "S06-ENV-001",
        "module": "运行态",
        "expected": "Nacos readiness=OK，Server/Worker health=UP",
        "actual": health,
        "result": "PASS" if passed else "FAIL",
        "needFix": not passed,
    }


def concurrent_create(target: dict[str, str], token: str, subscription_name: str) -> list[dict[str, Any]]:
    barrier = threading.Barrier(THREADS)

    def worker(index: int) -> dict[str, Any]:
        client = StudioClient()
        barrier.wait()
        started = time.perf_counter()
        response = client.request(
            "POST",
            f"{target['basePath']}/{target['serviceId']}/subscriptions",
            token=token,
            json_body={"subscriptionName": subscription_name},
            timeout=30,
        )
        return {
            "index": index,
            "elapsedMs": round((time.perf_counter() - started) * 1000, 2),
            "response": summarize_response(response),
        }

    with concurrent.futures.ThreadPoolExecutor(max_workers=THREADS) as executor:
        return list(executor.map(worker, range(THREADS)))


def probe_target(client: StudioClient, token: str, target: dict[str, str], batch: str) -> dict[str, Any]:
    subscription_name = f"{target['namePrefix']}-{batch}"
    responses = concurrent_create(target, token, subscription_name)
    list_response = client.request("GET", f"{target['basePath']}/{target['serviceId']}/subscriptions", token=token)
    subscriptions = response_data(list_response) or []
    matching = [item for item in subscriptions if item.get("subscriptionName") == subscription_name]
    enabled = [item for item in matching if item.get("enabled") in (1, True)]
    ok_count = sum(1 for item in responses if item["response"].get("status") == 200 and item["response"].get("success") is True)
    bad_request_count = sum(1 for item in responses if item["response"].get("status") == 400)
    unique_rejections = sum(1 for item in responses if item["response"].get("code") == "BAD_REQUEST")

    if target["expectedMode"] == "idempotent-single-active":
        passed = ok_count >= 1 and len(matching) == 1 and len(enabled) == 1
        expected = "并发创建至少 1 个请求成功，重复请求可幂等轮换或返回 BAD_REQUEST，但最终只能保留 1 条同名订阅且 1 条启用"
    else:
        passed = ok_count == 1 and bad_request_count == THREADS - 1 and len(matching) == 1 and len(enabled) == 1
        expected = "并发创建只有 1 个请求成功，其余同名启用订阅请求返回 BAD_REQUEST"

    return {
        "case": "S06-SUB-CREATE-" + target["kind"].upper(),
        "module": target["module"],
        "subscriptionName": subscription_name,
        "expected": expected,
        "actual": {
            "okCount": ok_count,
            "badRequestCount": bad_request_count,
            "uniqueRejectionCount": unique_rejections,
            "matchingCount": len(matching),
            "enabledCount": len(enabled),
            "matchingIds": [item.get("id") for item in matching],
            "responses": responses,
        },
        "result": "PASS" if passed else "FAIL",
        "needFix": not passed,
    }


def main() -> int:
    client = StudioClient()
    health = read_health()
    token, login_response = client.login()
    results: list[dict[str, Any]] = []

    if not token:
        payload = {
            "batch": None,
            "health": health,
            "login": summarize_response(login_response),
            "results": [
                {
                    "case": "S06-AUTH-001",
                    "module": "认证",
                    "result": "FAIL",
                    "needFix": True,
                    "actual": summarize_response(login_response),
                }
            ],
        }
        print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
        return 1

    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    results.append(health_result(health))
    for target in TARGETS:
        results.append(probe_target(client, token, target, batch))

    payload = {"batch": batch, "threadCount": THREADS, "health": health, "results": results}
    print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    return 1 if any(item["result"] == "FAIL" and item.get("needFix") for item in results) else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python
"""Studio S07 subscription token lifecycle probe.

The probe is data-preserving. It creates long-term subscription fixtures with
meaningful Chinese names and keeps the resulting subscriptions and invocation
logs for later regression comparison.
"""

from __future__ import annotations

import json
import os
from datetime import datetime
from typing import Any

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

TARGETS = [
    {
        "kind": "data-service",
        "module": "数据服务/Token生命周期",
        "basePath": "/data-services",
        "serviceId": os.getenv("STUDIO_DATA_SERVICE_ORDER_ID", "2068117382251651073"),
        "tokenHeader": "X-Data-Service-Token",
        "namePrefix": "长期回归-S07客户订单服务Token生命周期",
    },
    {
        "kind": "data-ingestion",
        "module": "数据接入/Token生命周期",
        "basePath": "/data-ingestion-services",
        "serviceId": os.getenv("STUDIO_INGESTION_SERVICE_ID", "2068130320853168129"),
        "tokenHeader": "X-Data-Ingestion-Token",
        "namePrefix": "长期回归-S07客户标签接入Token生命周期",
    },
    {
        "kind": "protocol-conversion",
        "module": "协议转换/Token生命周期",
        "basePath": "/protocol-conversions",
        "serviceId": os.getenv("STUDIO_PROTOCOL_CONVERSION_ID", "2068145027555180546"),
        "tokenHeader": "X-Protocol-Conversion-Token",
        "namePrefix": "长期回归-S07健康检查协议转换Token生命周期",
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
        json_body: Any | None = None,
        timeout: int = 30,
    ) -> dict[str, Any]:
        headers = {"X-Tenant-Id": TENANT_ID, "X-Project-Id": str(LONG_PROJECT_ID)}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        try:
            response = self.session.request(method, BASE_URL + path, headers=headers, json=json_body, timeout=timeout)
            return parse_response(response)
        except Exception as exc:
            return {"status": "EXCEPTION", "body": {"message": str(exc)}}

    def open_request(
        self,
        method: str,
        path: str,
        token_header: str,
        token: str | None,
        json_body: Any | None = None,
        timeout: int = 60,
    ) -> dict[str, Any]:
        headers: dict[str, str] = {}
        if token:
            headers[token_header] = token
        try:
            response = self.session.request(method, OPEN_BASE_URL + path, headers=headers, json=json_body, timeout=timeout)
            return parse_response(response)
        except Exception as exc:
            return {"status": "EXCEPTION", "body": {"message": str(exc)}}

    def login(self) -> tuple[str | None, dict[str, Any]]:
        response = self.request("POST", "/auth/login", json_body={"username": ADMIN_USER, "password": ADMIN_PASSWORD}, timeout=20)
        token = None
        if isinstance(response.get("body"), dict):
            token = (response["body"].get("data") or {}).get("token")
        return token, response


def parse_response(response: requests.Response) -> dict[str, Any]:
    try:
        body: Any = response.json()
    except Exception:
        body = response.text
    return {"status": response.status_code, "body": body}


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def is_ok(response: dict[str, Any]) -> bool:
    return response.get("status") == 200 and isinstance(response.get("body"), dict) and response["body"].get("success") is True


def is_unauthorized(response: dict[str, Any]) -> bool:
    body = response.get("body")
    return response.get("status") == 401 and isinstance(body, dict) and body.get("code") == "UNAUTHORIZED"


def summarize(response: dict[str, Any]) -> dict[str, Any]:
    body = response.get("body")
    if isinstance(body, dict):
        summary: dict[str, Any] = {
            "status": response.get("status"),
            "success": body.get("success"),
            "code": body.get("code"),
            "message": body.get("message"),
        }
        data = body.get("data")
        if isinstance(data, dict):
            summary["dataKeys"] = list(data.keys())[:8]
            for key in ("id", "serviceName", "endpointPath", "enabled", "tokenMasked"):
                if key in data:
                    summary[key] = data.get(key)
        elif "status" in body:
            summary["payloadStatus"] = body.get("status")
        return summary
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
        "case": "S07-ENV-001",
        "module": "运行态",
        "expected": "Nacos readiness=OK，Server/Worker health=UP",
        "actual": health,
        "result": "PASS" if passed else "FAIL",
        "needFix": False,
    }


def invoke_payload(kind: str, batch: str, step: str) -> dict[str, Any]:
    if kind == "data-service":
        return {"customerId": "CUST-LT-001", "orderStatus": "PAID", "pageNum": 1, "pageSize": 10}
    if kind == "data-ingestion":
        step_codes = {
            "初始调用": "INIT",
            "旧Token轮换后调用": "OLDROT",
            "新Token轮换后调用": "NEWROT",
            "禁用后调用": "DIS",
            "重新启用后调用": "EN",
        }
        return {
            "ingestId": f"LT-S07-{batch[-8:]}-{step_codes.get(step, 'STEP')}",
            "customerId": "CUST-LT-001",
            "tagName": "S07订阅Token生命周期客户标签",
            "tagValue": f"订阅状态校验-{step}",
            "rawPayload": {"业务含义": "订阅Token生命周期探针", "测试批次": batch, "步骤": step},
        }
    return {"业务含义": "订阅Token生命周期探针", "测试批次": batch, "步骤": step}


def invoke_success(kind: str, response: dict[str, Any]) -> bool:
    if response.get("status") != 200 or not isinstance(response.get("body"), dict):
        return False
    body = response["body"]
    if kind == "protocol-conversion":
        return body.get("status") == "UP"
    if kind == "data-ingestion":
        return body.get("status") == "SUCCESS" or body.get("success") is True or body.get("successCount") == 1 or body.get("failedCount") == 0
    return body.get("success") is True


def probe_target(client: StudioClient, admin_token: str, target: dict[str, str], batch: str) -> dict[str, Any]:
    detail_response = client.request("GET", f"{target['basePath']}/{target['serviceId']}", token=admin_token)
    detail = response_data(detail_response) or {}
    endpoint_path = detail.get("endpointPath")
    subscription_name = f"{target['namePrefix']}-{batch}"
    create_response = client.request(
        "POST",
        f"{target['basePath']}/{target['serviceId']}/subscriptions",
        token=admin_token,
        json_body={"subscriptionName": subscription_name},
    )
    created = response_data(create_response) or {}
    subscription_id = created.get("id")
    token_one = created.get("token")

    initial = client.open_request(
        "POST",
        str(endpoint_path),
        target["tokenHeader"],
        token_one,
        json_body=invoke_payload(target["kind"], batch, "初始调用"),
    ) if endpoint_path and token_one else {"status": "SKIP", "body": "missing endpoint or token"}

    rotate_response = client.request(
        "POST",
        f"{target['basePath']}/{target['serviceId']}/subscriptions/{subscription_id}/rotate",
        token=admin_token,
    ) if subscription_id else {"status": "SKIP", "body": "missing subscription id"}
    rotated = response_data(rotate_response) or {}
    token_two = rotated.get("token")

    old_after_rotate = client.open_request(
        "POST",
        str(endpoint_path),
        target["tokenHeader"],
        token_one,
        json_body=invoke_payload(target["kind"], batch, "旧Token轮换后调用"),
    ) if endpoint_path and token_one else {"status": "SKIP", "body": "missing endpoint or old token"}
    new_after_rotate = client.open_request(
        "POST",
        str(endpoint_path),
        target["tokenHeader"],
        token_two,
        json_body=invoke_payload(target["kind"], batch, "新Token轮换后调用"),
    ) if endpoint_path and token_two else {"status": "SKIP", "body": "missing endpoint or new token"}

    disable_response = client.request(
        "POST",
        f"{target['basePath']}/{target['serviceId']}/subscriptions/{subscription_id}/disable",
        token=admin_token,
    ) if subscription_id else {"status": "SKIP", "body": "missing subscription id"}
    after_disable = client.open_request(
        "POST",
        str(endpoint_path),
        target["tokenHeader"],
        token_two,
        json_body=invoke_payload(target["kind"], batch, "禁用后调用"),
    ) if endpoint_path and token_two else {"status": "SKIP", "body": "missing endpoint or new token"}

    enable_response = client.request(
        "POST",
        f"{target['basePath']}/{target['serviceId']}/subscriptions/{subscription_id}/enable",
        token=admin_token,
    ) if subscription_id else {"status": "SKIP", "body": "missing subscription id"}
    after_enable = client.open_request(
        "POST",
        str(endpoint_path),
        target["tokenHeader"],
        token_two,
        json_body=invoke_payload(target["kind"], batch, "重新启用后调用"),
    ) if endpoint_path and token_two else {"status": "SKIP", "body": "missing endpoint or new token"}

    passed = (
        is_ok(create_response)
        and invoke_success(target["kind"], initial)
        and is_ok(rotate_response)
        and is_unauthorized(old_after_rotate)
        and invoke_success(target["kind"], new_after_rotate)
        and is_ok(disable_response)
        and is_unauthorized(after_disable)
        and is_ok(enable_response)
        and invoke_success(target["kind"], after_enable)
    )
    return {
        "case": "S07-TOKEN-" + target["kind"].upper(),
        "module": target["module"],
        "subscriptionName": subscription_name,
        "serviceId": target["serviceId"],
        "subscriptionId": subscription_id,
        "endpointPath": endpoint_path,
        "expected": "创建 token 可调用；轮换后旧 token 401、新 token 可调用；禁用后 401；重新启用后新 token 恢复可调用",
        "actual": {
            "create": summarize(create_response),
            "initial": summarize(initial),
            "rotate": summarize(rotate_response),
            "oldAfterRotate": summarize(old_after_rotate),
            "newAfterRotate": summarize(new_after_rotate),
            "disable": summarize(disable_response),
            "afterDisable": summarize(after_disable),
            "enable": summarize(enable_response),
            "afterEnable": summarize(after_enable),
        },
        "result": "PASS" if passed else "FAIL",
        "needFix": not passed,
    }


def main() -> int:
    client = StudioClient()
    health = read_health()
    admin_token, login_response = client.login()
    if not admin_token:
        print(json.dumps({"health": health, "login": summarize(login_response)}, ensure_ascii=False, indent=2))
        return 1

    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    results = [health_result(health)]
    for target in TARGETS:
        results.append(probe_target(client, admin_token, target, batch))

    payload = {"batch": batch, "health": health, "results": results}
    print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    return 1 if any(item["result"] == "FAIL" and item.get("needFix") for item in results) else 0


if __name__ == "__main__":
    raise SystemExit(main())

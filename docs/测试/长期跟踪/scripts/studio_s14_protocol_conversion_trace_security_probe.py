#!/usr/bin/env python
"""Studio S14 protocol conversion trace and log masking probe.

The probe preserves long-term data. It creates a traceable S14 subscription for
the existing long-term protocol conversion service, invokes debug/open paths
with sensitive header/query/form/body values, then validates trace, full log,
download concurrency, and cross-project isolation.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from typing import Any

import requests


API_BASE = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
OPEN_BASE = os.getenv("STUDIO_OPEN_BASE_URL", "http://127.0.0.1:18080/openapi").rstrip("/")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
RECEIVER_PROJECT_ID = os.getenv("STUDIO_RECEIVER_PROJECT_ID", "2068301893337849857")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")

NACOS_READINESS = os.getenv(
    "STUDIO_NACOS_READINESS",
    "http://127.0.0.1:8848/nacos/v1/console/health/readiness",
)
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")

PROTOCOL_CONVERSION_ID = os.getenv("STUDIO_S14_PROTOCOL_CONVERSION_ID", "2068145027555180546")
FORBIDDEN_INTERNAL_MARKERS = ("java.", "com.jdragon", "Traceback", ".java:", "\tat ")


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
        headers: dict[str, str] | None = None,
        params: dict[str, Any] | None = None,
        json_body: Any | None = None,
        timeout: int = 90,
    ) -> dict[str, Any]:
        request_headers = {"X-Tenant-Id": TENANT_ID}
        if headers:
            request_headers.update(headers)
        try:
            response = self.session.request(
                method,
                OPEN_BASE + path,
                headers=request_headers,
                params=params,
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
            for key in (
                "id",
                "serviceCode",
                "serviceName",
                "status",
                "requestId",
                "pageNo",
                "totalPages",
                "pageSizeBytes",
                "sizeBytes",
                "downloadName",
                "historicalFallback",
                "paged",
            ):
                if key in payload:
                    summary[key] = payload.get(key)
            summary["keys"] = list(payload.keys())[:12]
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


def clean_message(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return not any(marker in text for marker in FORBIDDEN_INTERNAL_MARKERS)


def log_content(response: dict[str, Any]) -> str:
    data = response_data(response) or {}
    return str(data.get("content") or "") if isinstance(data, dict) else ""


def page_items(response: dict[str, Any]) -> list[dict[str, Any]]:
    data = response_data(response) or {}
    return data.get("items") or data.get("records") or []


def query_latest_access_log(client: StudioClient, token: str, service_id: str) -> dict[str, Any] | None:
    response = client.request(
        "POST",
        "/protocol-conversion-metrics/access-logs/query",
        token=token,
        json_body={"serviceId": str(service_id), "pageNo": 1, "pageSize": 10},
        timeout=30,
    )
    items = page_items(response)
    return items[0] if items else None


def wait_for_log_with_trace(client: StudioClient,
                            token: str,
                            service_id: str,
                            trace: str,
                            attempts: int = 10) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    latest: dict[str, Any] | None = None
    latest_download: dict[str, Any] | None = None
    for _ in range(attempts):
        latest = query_latest_access_log(client, token, service_id)
        if latest and latest.get("id"):
            latest_download = client.request(
                "GET",
                f"/invocation-logs/protocol-conversions/{latest.get('id')}/download",
                token=token,
                timeout=60,
            )
            if trace in log_content(latest_download):
                return latest, latest_download
        time.sleep(1)
    return latest, latest_download


def secret_values(batch: str, subscription_token: str | None = None) -> list[str]:
    values = [
        f"LT-S14-RAW-HEADER-{batch}",
        f"LT-S14-RAW-QUERY-{batch}",
        f"LT-S14-RAW-FORM-{batch}",
        f"LT-S14-RAW-BODY-{batch}",
        f"LT-S14-RAW-CLIENT-SECRET-{batch}",
        f"LT-S14-RAW-API-KEY-{batch}",
    ]
    if subscription_token:
        values.append(subscription_token)
    return values


def assert_no_secret(content: str, batch: str, subscription_token: str | None = None) -> tuple[bool, list[str]]:
    leaked = [value for value in secret_values(batch, subscription_token) if value and value in content]
    return len(leaked) == 0, leaked


def concurrent_download_hashes(client: StudioClient,
                               token: str,
                               path: str,
                               workers: int = 8) -> list[dict[str, Any]]:
    def run_once(_: int) -> dict[str, Any]:
        response = client.request("GET", path, token=token, timeout=90)
        content = log_content(response)
        return {
            "status": response.get("status"),
            "ok": is_result_ok(response),
            "length": len(content),
            "sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
            "clean": clean_message(response),
        }

    with ThreadPoolExecutor(max_workers=workers) as executor:
        return list(executor.map(run_once, range(workers)))


def debug_payload(batch: str, trace: str) -> dict[str, Any]:
    return {
        "headers": {
            "X-S14-Secret-Token": f"LT-S14-RAW-HEADER-{batch}",
            "X-S14-Trace": trace,
        },
        "query": {
            "api_key": f"LT-S14-RAW-QUERY-{batch}",
            "businessTrace": "长期回归S14查询追踪",
        },
        "form": {
            "clientSecret": f"LT-S14-RAW-FORM-{batch}",
            "formTrace": "长期回归S14表单追踪",
        },
        "body": {
            "password": f"LT-S14-RAW-BODY-{batch}",
            "clientSecret": f"LT-S14-RAW-CLIENT-SECRET-{batch}",
            "api_key": f"LT-S14-RAW-API-KEY-{batch}",
            "traceId": trace,
            "customerName": "长期回归S14协议转换调试客户",
        },
    }


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    trace = f"LT-S14-TRACE-{batch}"
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
    add_result(results, "S14-ENV-001", "运行态", "Nacos readiness=OK，Server/Worker health=UP", health, health_ok)
    if not token:
        add_result(results, "S14-AUTH-001", "认证", "admin 登录成功", summarize(login), False)
        print(json.dumps({"batch": batch, "trace": trace, "health": health, "results": results}, ensure_ascii=False, indent=2, default=str))
        return 1

    service_detail = client.request("GET", f"/protocol-conversions/{PROTOCOL_CONVERSION_ID}", token=token, timeout=30)
    service = response_data(service_detail) or {}
    subscription_response = client.request(
        "POST",
        f"/protocol-conversions/{PROTOCOL_CONVERSION_ID}/subscriptions",
        token=token,
        json_body={"subscriptionName": f"长期回归-S14协议转换Trace日志脱敏订阅-{batch}"},
        timeout=30,
    )
    subscription = response_data(subscription_response) or {}
    subscription_token = subscription.get("token")
    add_result(
        results,
        "S14-BASE-001",
        "长期对象基线",
        "长期协议转换服务在线，新增 S14 中文命名订阅并保留",
        {
            "service": summarize(service_detail),
            "subscription": summarize(subscription_response),
            "subscriptionId": subscription.get("id"),
            "tokenPresent": bool(subscription_token),
        },
        service.get("status") == "ONLINE"
        and bool(service.get("serviceCode"))
        and bool(service.get("serviceKey"))
        and is_result_ok(subscription_response)
        and bool(subscription_token),
    )

    debug_response = client.request(
        "POST",
        f"/protocol-conversions/{PROTOCOL_CONVERSION_ID}/debug",
        token=token,
        json_body=debug_payload(batch, trace),
        timeout=60,
    )
    debug_text = json.dumps(debug_response, ensure_ascii=False, default=str)
    debug_leaked = [value for value in secret_values(batch) if value in debug_text]
    debug_trace = ((response_data(debug_response) or {}).get("conversionTrace") or {}).get("sourceRequest") or {}
    add_result(
        results,
        "S14-DBG-001",
        "协议转换调试Trace脱敏",
        "debug Trace 中 Header/Query/Form/Body 敏感字段均遮蔽，非敏感中文业务字段保留",
        {
            "debug": summarize(debug_response),
            "leaked": debug_leaked,
            "sourceRequest": debug_trace,
        },
        is_result_ok(debug_response)
        and not debug_leaked
        and (debug_trace.get("query") or {}).get("api_key") == "******"
        and (debug_trace.get("form") or {}).get("clientSecret") == "******"
        and "长期回归S14协议转换调试客户" in str(debug_trace.get("bodyPreview")),
    )

    open_trace = f"{trace}-OPEN"
    open_body = {
        "traceId": open_trace,
        "password": f"LT-S14-RAW-BODY-{batch}",
        "clientSecret": f"LT-S14-RAW-CLIENT-SECRET-{batch}",
        "api_key": f"LT-S14-RAW-API-KEY-{batch}",
        "customerName": "长期回归S14协议转换开放调用客户",
    }
    open_headers = {
        "X-Protocol-Conversion-Token": str(subscription_token or ""),
        "X-S14-Secret-Token": f"LT-S14-RAW-HEADER-{batch}",
        "X-S14-Trace": open_trace,
        "User-Agent": f"studio-s14-protocol-probe/{batch}",
    }
    open_response = client.open_request(
        "POST",
        f"/protocol-conversions/{service.get('serviceCode')}/{service.get('serviceKey')}",
        headers=open_headers,
        params={"api_key": f"LT-S14-RAW-QUERY-{batch}", "businessTrace": "长期回归S14开放查询追踪"},
        json_body=open_body,
        timeout=120,
    )
    access_log, download = wait_for_log_with_trace(client, token, PROTOCOL_CONVERSION_ID, open_trace)
    log_id = (access_log or {}).get("id")
    download_content = log_content(download or {})
    log_no_secret, log_leaked = assert_no_secret(download_content, batch, subscription_token)
    trace_response = client.request(
        "GET",
        f"/protocol-conversion-metrics/access-logs/{log_id}/trace",
        token=token,
        timeout=60,
    ) if log_id else {"status": "SKIP", "body": {}}
    trace_text = json.dumps(trace_response, ensure_ascii=False, default=str)
    trace_leaked = [value for value in secret_values(batch, subscription_token) if value and value in trace_text]
    trace_source = ((response_data(trace_response) or {}).get("sourceRequest") or {})
    add_result(
        results,
        "S14-OPEN-001",
        "协议转换开放调用Trace/访问日志",
        "开放调用成功，访问日志 Trace 可读取且 Header/Query/Body 敏感值均不泄露",
        {
            "invoke": summarize(open_response),
            "accessLogId": log_id,
            "trace": summarize(trace_response),
            "traceLeaked": trace_leaked,
            "traceSource": trace_source,
            "download": summarize(download),
            "logLeaked": log_leaked,
            "containsTrace": open_trace in download_content,
            "maskedCount": download_content.count("******"),
        },
        open_response.get("status") == 200
        and log_id is not None
        and is_result_ok(trace_response)
        and not trace_leaked
        and not log_leaked
        and open_trace in download_content
        and (trace_source.get("query") or {}).get("api_key") == "******",
    )

    log_page_1 = client.request(
        "GET",
        f"/invocation-logs/protocol-conversions/{log_id}",
        token=token,
        params={"pageNo": 1, "pageSizeBytes": 512},
        timeout=60,
    ) if log_id else {"status": "SKIP", "body": {}}
    log_page_2 = client.request(
        "GET",
        f"/invocation-logs/protocol-conversions/{log_id}",
        token=token,
        params={"pageNo": 2, "pageSizeBytes": 512},
        timeout=60,
    ) if log_id else {"status": "SKIP", "body": {}}
    page_1_content = log_content(log_page_1)
    page_2_content = log_content(log_page_2)
    page_1_data = response_data(log_page_1) or {}
    add_result(
        results,
        "S14-LOG-001",
        "协议转换完整日志分页/脱敏",
        "完整日志分页可读，包含业务追踪，不含敏感原文和内部异常标记",
        {
            "page1": summarize(log_page_1),
            "page2": summarize(log_page_2),
            "download": summarize(download),
            "page1Length": len(page_1_content),
            "page2Length": len(page_2_content),
            "clean": clean_message(log_page_1) and clean_message(log_page_2) and clean_message(download),
        },
        is_result_ok(log_page_1)
        and is_result_ok(log_page_2)
        and int(page_1_data.get("totalPages") or 1) > 1
        and page_1_content != page_2_content
        and log_no_secret
        and clean_message(log_page_1)
        and clean_message(log_page_2)
        and clean_message(download),
        need_fix=not (log_no_secret and clean_message(log_page_1) and clean_message(log_page_2) and clean_message(download)),
    )

    downloads = concurrent_download_hashes(
        client,
        token,
        f"/invocation-logs/protocol-conversions/{log_id}/download",
    ) if log_id else []
    hashes = {item.get("sha256") for item in downloads if item.get("ok")}
    add_result(
        results,
        "S14-LOG-002",
        "协议转换完整日志并发下载",
        "8 路并发下载同一协议转换完整日志均成功且内容 hash 一致",
        {"downloads": downloads, "hashCount": len(hashes)},
        len(downloads) == 8 and all(item.get("ok") for item in downloads) and len(hashes) == 1,
    )

    cross_project_trace = client.request(
        "GET",
        f"/protocol-conversion-metrics/access-logs/{log_id}/trace",
        token=token,
        project_id=RECEIVER_PROJECT_ID,
        timeout=30,
    ) if log_id else {"status": "SKIP", "body": {}}
    cross_project_log = client.request(
        "GET",
        f"/invocation-logs/protocol-conversions/{log_id}/download",
        token=token,
        project_id=RECEIVER_PROJECT_ID,
        timeout=30,
    ) if log_id else {"status": "SKIP", "body": {}}
    receiver_query = client.request(
        "POST",
        "/protocol-conversion-metrics/access-logs/query",
        token=token,
        project_id=RECEIVER_PROJECT_ID,
        json_body={"serviceId": str(PROTOCOL_CONVERSION_ID), "pageNo": 1, "pageSize": 10},
        timeout=30,
    )
    receiver_items = page_items(receiver_query)
    add_result(
        results,
        "S14-ACL-001",
        "协议转换日志跨项目隔离",
        "接收项目不能读取长期项目协议转换 Trace/完整日志，列表查询不返回源项目日志",
        {
            "crossProjectTrace": summarize(cross_project_trace),
            "crossProjectLog": summarize(cross_project_log),
            "receiverQuery": summarize(receiver_query),
            "receiverItemCount": len(receiver_items),
            "clean": clean_message(cross_project_trace) and clean_message(cross_project_log) and clean_message(receiver_query),
        },
        cross_project_trace.get("status") == 404
        and cross_project_log.get("status") == 404
        and is_result_ok(receiver_query)
        and len(receiver_items) == 0
        and clean_message(cross_project_trace)
        and clean_message(cross_project_log)
        and clean_message(receiver_query),
        need_fix=not (clean_message(cross_project_trace) and clean_message(cross_project_log) and clean_message(receiver_query)),
    )

    payload = {
        "batch": batch,
        "trace": trace,
        "fixtures": {
            "projectId": LONG_PROJECT_ID,
            "receiverProjectId": RECEIVER_PROJECT_ID,
            "protocolConversionId": PROTOCOL_CONVERSION_ID,
            "subscriptionId": subscription.get("id"),
            "accessLogId": log_id,
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

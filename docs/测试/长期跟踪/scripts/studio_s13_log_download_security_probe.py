#!/usr/bin/env python
"""Studio S13 log download, paging and sensitive masking probe.

The probe preserves long-term data. It creates new invocation logs by calling
existing S12 services, then validates log masking, paging, concurrent download
consistency, and cross-project access isolation.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from decimal import Decimal
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

DATA_SERVICE_ID = os.getenv("STUDIO_S12_DATA_SERVICE_ID", "2068717536226799617")
INGESTION_SERVICE_ID = os.getenv("STUDIO_S12_INGESTION_SERVICE_ID", "2068717572302008321")

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


def query_latest_access_log(client: StudioClient, token: str, endpoint: str, service_id: str) -> dict[str, Any] | None:
    response = client.request(
        "POST",
        endpoint,
        token=token,
        json_body={"serviceId": str(service_id), "pageNo": 1, "pageSize": 10},
        timeout=30,
    )
    items = page_items(response)
    return items[0] if items else None


def wait_for_log_with_trace(client: StudioClient,
                            token: str,
                            domain: str,
                            endpoint: str,
                            service_id: str,
                            trace: str,
                            attempts: int = 8) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    latest: dict[str, Any] | None = None
    latest_download: dict[str, Any] | None = None
    for _ in range(attempts):
        latest = query_latest_access_log(client, token, endpoint, service_id)
        if latest and latest.get("id"):
            latest_download = client.request("GET", f"/invocation-logs/{domain}/{latest.get('id')}/download", token=token, timeout=60)
            if trace in log_content(latest_download):
                return latest, latest_download
        time.sleep(1)
    return latest, latest_download


def secret_values(batch: str) -> list[str]:
    return [
        f"LT-S13-RAW-PASSWORD-{batch}",
        f"LT-S13-RAW-TOKEN-{batch}",
        f"LT-S13-RAW-SECRET-TOKEN-{batch}",
        f"LT-S13-RAW-CLIENT-SECRET-{batch}",
        f"LT-S13-RAW-API-KEY-{batch}",
        f"LT-S13-RAW-ACCESS-KEY-{batch}",
        f"LT-S13-RAW-CREDENTIAL-{batch}",
        f"LT-S13-HEADER-API-KEY-{batch}",
        f"LT-S13-HEADER-AUTH-{batch}",
    ]


def body_with_secrets(batch: str, trace: str, page_size: int = 1000) -> dict[str, Any]:
    return {
        "pageNum": 1,
        "pageSize": page_size,
        "nonSensitiveTrace": trace,
        "password": f"LT-S13-RAW-PASSWORD-{batch}",
        "token": f"LT-S13-RAW-TOKEN-{batch}",
        "secretToken": f"LT-S13-RAW-SECRET-TOKEN-{batch}",
        "clientSecret": f"LT-S13-RAW-CLIENT-SECRET-{batch}",
        "api_key": f"LT-S13-RAW-API-KEY-{batch}",
        "access-key": f"LT-S13-RAW-ACCESS-KEY-{batch}",
        "credentialValue": f"LT-S13-RAW-CREDENTIAL-{batch}",
    }


def headers_with_secrets(batch: str, trace: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer LT-S13-HEADER-AUTH-{batch}",
        "X-S13-Api-Key": f"LT-S13-HEADER-API-KEY-{batch}",
        "X-S13-Trace": trace,
        "User-Agent": f"studio-s13-log-probe/{batch}",
    }


def assert_no_secret(content: str, batch: str) -> tuple[bool, list[str]]:
    leaked = [value for value in secret_values(batch) if value in content]
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


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    trace = f"LT-S13-TRACE-{batch}"
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
    add_result(results, "S13-ENV-001", "运行态", "Nacos readiness=OK，Server/Worker health=UP", health, health_ok)
    if not token:
        add_result(results, "S13-AUTH-001", "认证", "admin 登录成功", summarize(login), False)
        print(json.dumps({"batch": batch, "trace": trace, "health": health, "results": results}, ensure_ascii=False, indent=2, default=str))
        return 1

    data_service_detail = client.request("GET", f"/data-services/{DATA_SERVICE_ID}", token=token, timeout=30)
    ingestion_service_detail = client.request("GET", f"/data-ingestion-services/{INGESTION_SERVICE_ID}", token=token, timeout=30)
    data_service = response_data(data_service_detail) or {}
    ingestion_service = response_data(ingestion_service_detail) or {}
    add_result(
        results,
        "S13-BASE-001",
        "长期对象基线",
        "复用 S12 数据服务和数据接入服务，且服务 key 存在",
        {"dataService": summarize(data_service_detail), "ingestionService": summarize(ingestion_service_detail)},
        bool(data_service.get("serviceKey")) and bool(ingestion_service.get("serviceKey")),
    )

    data_body = body_with_secrets(batch, trace, 1000)
    data_invoke = client.open_request(
        "POST",
        f"/data-services/{data_service.get('serviceCode')}/{data_service.get('serviceKey')}",
        headers=headers_with_secrets(batch, trace),
        json_body=data_body,
        timeout=120,
    )
    data_log, data_download = wait_for_log_with_trace(
        client,
        token,
        "data-services",
        "/data-service-metrics/access-logs/query",
        DATA_SERVICE_ID,
        trace,
    )
    data_content = log_content(data_download or {})
    data_no_secret, data_leaked = assert_no_secret(data_content, batch)
    add_result(
        results,
        "S13-INV-001",
        "数据服务调用日志/敏感字段脱敏",
        "完整调用日志保留非敏感追踪字段，Header/Body 常见敏感字段变体均脱敏",
        {
            "invoke": summarize(data_invoke),
            "accessLogId": (data_log or {}).get("id"),
            "download": summarize(data_download),
            "containsTrace": trace in data_content,
            "leaked": data_leaked,
            "maskedCount": data_content.count("******"),
            "contentLength": len(data_content),
        },
        is_result_ok(data_invoke)
        and data_download is not None
        and is_result_ok(data_download)
        and trace in data_content
        and data_no_secret
        and data_content.count("******") >= 7,
    )

    data_log_id = (data_log or {}).get("id")
    data_page_1 = client.request(
        "GET",
        f"/invocation-logs/data-services/{data_log_id}",
        token=token,
        params={"pageNo": 1, "pageSizeBytes": 512},
        timeout=60,
    ) if data_log_id else {"status": "SKIP", "body": {}}
    data_page_2 = client.request(
        "GET",
        f"/invocation-logs/data-services/{data_log_id}",
        token=token,
        params={"pageNo": 2, "pageSizeBytes": 512},
        timeout=60,
    ) if data_log_id else {"status": "SKIP", "body": {}}
    data_page_1_content = log_content(data_page_1)
    data_page_2_content = log_content(data_page_2)
    data_page_1_data = response_data(data_page_1) or {}
    add_result(
        results,
        "S13-INV-002",
        "数据服务调用日志/对象分页",
        "小分页读取多页对象日志，UTF-8 内容不截断出替换字符，分页元数据正确",
        {
            "page1": summarize(data_page_1),
            "page2": summarize(data_page_2),
            "page1Length": len(data_page_1_content),
            "page2Length": len(data_page_2_content),
            "page1Replacement": "\ufffd" in data_page_1_content,
            "page2Replacement": "\ufffd" in data_page_2_content,
        },
        is_result_ok(data_page_1)
        and is_result_ok(data_page_2)
        and int(data_page_1_data.get("totalPages") or 1) > 1
        and "\ufffd" not in data_page_1_content
        and "\ufffd" not in data_page_2_content
        and data_page_1_content != data_page_2_content,
    )

    data_downloads = concurrent_download_hashes(client, token, f"/invocation-logs/data-services/{data_log_id}/download") if data_log_id else []
    data_hashes = {item.get("sha256") for item in data_downloads if item.get("ok")}
    add_result(
        results,
        "S13-INV-003",
        "数据服务调用日志/并发下载",
        "8 路并发下载同一对象日志均成功且内容 hash 一致",
        {"downloads": data_downloads, "hashCount": len(data_hashes)},
        len(data_downloads) == 8 and all(item.get("ok") for item in data_downloads) and len(data_hashes) == 1,
    )

    ingest_trace = f"{trace}-ING"
    ingest_id = f"LT-S13-LOG-{batch}"
    ingestion_row = {
        "ingestId": ingest_id,
        "customerName": "长期回归S13日志脱敏客户",
        "boundaryAmount": str(Decimal("1300.1313")),
        "businessDate": "2026-06-22",
        "eventTime": "2026-06-22 00:13:00",
        "nullableNote": None,
        "emptyNote": "",
        "unicodeNote": f"日志脱敏追踪-{ingest_trace}",
        "longNote": "S13日志下载与脱敏专项",
        "batchCode": f"S13-LOG-{batch}",
        **body_with_secrets(batch, ingest_trace, 1),
    }
    ingestion_invoke = client.open_request(
        "POST",
        f"/data-ingestion-services/{ingestion_service.get('serviceCode')}/{ingestion_service.get('serviceKey')}",
        headers=headers_with_secrets(batch, ingest_trace),
        json_body=[ingestion_row],
        timeout=120,
    )
    ingestion_log, ingestion_download = wait_for_log_with_trace(
        client,
        token,
        "data-ingestion-services",
        "/data-ingestion-metrics/access-logs/query",
        INGESTION_SERVICE_ID,
        ingest_trace,
    )
    ingestion_content = log_content(ingestion_download or {})
    ingestion_no_secret, ingestion_leaked = assert_no_secret(ingestion_content, batch)
    add_result(
        results,
        "S13-ING-001",
        "数据接入调用日志/敏感字段脱敏",
        "接入调用完整日志保留业务追踪字段，额外敏感 Body/Header 字段均脱敏",
        {
            "invoke": summarize(ingestion_invoke),
            "accessLogId": (ingestion_log or {}).get("id"),
            "download": summarize(ingestion_download),
            "containsTrace": ingest_trace in ingestion_content,
            "leaked": ingestion_leaked,
            "maskedCount": ingestion_content.count("******"),
            "contentLength": len(ingestion_content),
        },
        is_ingestion_ok(ingestion_invoke)
        and ingestion_download is not None
        and is_result_ok(ingestion_download)
        and ingest_trace in ingestion_content
        and ingestion_no_secret
        and ingestion_content.count("******") >= 7,
    )

    runs_response = client.request("GET", "/runs", token=token, params={"includeRunRecords": "true"}, timeout=60)
    run_records = (response_data(runs_response) or {}).get("runRecords") or []
    object_run = next((item for item in run_records if item.get("logStorageType") == "OBJECT_STORAGE" and item.get("logStatus") == "AVAILABLE"), None)
    run_id = (object_run or {}).get("id")
    run_page = client.request(
        "GET",
        f"/runs/{run_id}/log",
        token=token,
        params={"pageNo": 1, "pageSizeBytes": 256},
        timeout=60,
    ) if run_id else {"status": "SKIP", "body": {}}
    run_download = client.request("GET", f"/runs/{run_id}/log/download", token=token, timeout=60) if run_id else {"status": "SKIP", "body": {}}
    run_content = log_content(run_download)
    add_result(
        results,
        "S13-RUN-001",
        "运行记录日志/分页下载",
        "长期运行记录对象日志可分页查看和完整下载，无内部错误泄露",
        {
            "runs": summarize(runs_response),
            "runId": run_id,
            "page": summarize(run_page),
            "download": summarize(run_download),
            "contentLength": len(run_content),
            "clean": clean_message(run_download),
        },
        run_id is not None
        and is_result_ok(run_page)
        and is_result_ok(run_download)
        and len(run_content) > 0
        and clean_message(run_download),
        need_fix=not clean_message(run_download),
    )

    run_downloads = concurrent_download_hashes(client, token, f"/runs/{run_id}/log/download") if run_id else []
    run_hashes = {item.get("sha256") for item in run_downloads if item.get("ok")}
    add_result(
        results,
        "S13-RUN-002",
        "运行记录日志/并发下载",
        "8 路并发下载同一运行日志均成功且内容 hash 一致",
        {"downloads": run_downloads, "hashCount": len(run_hashes)},
        len(run_downloads) == 8 and all(item.get("ok") for item in run_downloads) and len(run_hashes) == 1,
    )

    invalid_domain = client.request("GET", f"/invocation-logs/unknown-domain/{data_log_id}", token=token, timeout=30) if data_log_id else {
        "status": "SKIP",
        "body": {},
    }
    cross_project_log = client.request(
        "GET",
        f"/invocation-logs/data-services/{data_log_id}/download",
        token=token,
        project_id=RECEIVER_PROJECT_ID,
        timeout=30,
    ) if data_log_id else {"status": "SKIP", "body": {}}
    cross_project_run = client.request(
        "GET",
        f"/runs/{run_id}/log/download",
        token=token,
        project_id=RECEIVER_PROJECT_ID,
        timeout=30,
    ) if run_id else {"status": "SKIP", "body": {}}
    add_result(
        results,
        "S13-ACL-001",
        "日志接口/隔离与错误提示",
        "非法 domain 返回 400；接收项目不能下载长期项目调用日志和运行日志；错误无内部泄露",
        {
            "invalidDomain": summarize(invalid_domain),
            "crossProjectInvocationLog": summarize(cross_project_log),
            "crossProjectRunLog": summarize(cross_project_run),
            "clean": clean_message(invalid_domain) and clean_message(cross_project_log) and clean_message(cross_project_run),
        },
        invalid_domain.get("status") == 400
        and cross_project_log.get("status") == 404
        and cross_project_run.get("status") == 404
        and clean_message(invalid_domain)
        and clean_message(cross_project_log)
        and clean_message(cross_project_run),
        need_fix=not (clean_message(invalid_domain) and clean_message(cross_project_log) and clean_message(cross_project_run)),
    )

    payload = {
        "batch": batch,
        "trace": trace,
        "fixtures": {
            "projectId": LONG_PROJECT_ID,
            "receiverProjectId": RECEIVER_PROJECT_ID,
            "dataServiceId": DATA_SERVICE_ID,
            "dataServiceAccessLogId": data_log_id,
            "ingestionServiceId": INGESTION_SERVICE_ID,
            "ingestionAccessLogId": (ingestion_log or {}).get("id"),
            "runRecordId": run_id,
            "ingestId": ingest_id,
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

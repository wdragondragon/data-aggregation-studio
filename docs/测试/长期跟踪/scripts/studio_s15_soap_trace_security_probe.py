#!/usr/bin/env python
"""Studio S15 SOAP protocol conversion trace and log masking probe.

The probe preserves long-term data. It creates or updates a traceable SOAP
source protocol conversion service, invokes WSDL and SOAP open endpoints with
sensitive XML element/attribute values, then verifies Trace and full invocation
logs do not expose raw secrets.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime
from typing import Any

import requests


API_BASE = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
OPEN_BASE = os.getenv("STUDIO_OPEN_BASE_URL", "http://127.0.0.1:18080/openapi").rstrip("/")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")
TARGET_DATASOURCE_ID = os.getenv("STUDIO_S15_HTTP_DATASOURCE_ID", "2068145017434324994")

NACOS_READINESS = os.getenv(
    "STUDIO_NACOS_READINESS",
    "http://127.0.0.1:8848/nacos/v1/console/health/readiness",
)
SERVER_HEALTH = os.getenv("STUDIO_SERVER_HEALTH", "http://127.0.0.1:18080/actuator/health")
WORKER_HEALTH = os.getenv("STUDIO_WORKER_HEALTH", "http://127.0.0.1:18081/actuator/health")

SERVICE_CODE = os.getenv("STUDIO_S15_SERVICE_CODE", "lt_reg_s15_customer_soap_trace")
SERVICE_NAME = os.getenv("STUDIO_S15_SERVICE_NAME", "长期回归-S15客户SOAP协议转换脱敏服务")
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

    def open_soap(
        self,
        service_code: str,
        service_key: str,
        envelope: str,
        batch: str,
        timeout: int = 60,
    ) -> dict[str, Any]:
        headers = {
            "X-Tenant-Id": TENANT_ID,
            "Content-Type": "text/xml;charset=UTF-8",
            "SOAPAction": "submitCustomerTrace",
            "User-Agent": "studio-s15-soap-probe/" + batch,
            "X-S15-Secret-Token": "LT-S15-RAW-HTTP-HEADER-" + batch,
        }
        try:
            response = self.session.post(
                f"{OPEN_BASE}/ws/protocol-conversions/{service_code}/{service_key}",
                headers=headers,
                data=envelope.encode("utf-8"),
                timeout=timeout,
            )
            return normalize_response(response)
        except Exception as exc:
            return {"status": "EXCEPTION", "body": {"message": str(exc)}}

    def wsdl(self, service_code: str, service_key: str) -> dict[str, Any]:
        try:
            response = self.session.get(
                f"{OPEN_BASE}/ws/protocol-conversions/{service_code}/{service_key}",
                headers={"X-Tenant-Id": TENANT_ID},
                params={"wsdl": ""},
                timeout=30,
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
            for key in ("id", "serviceCode", "serviceName", "status", "requestId", "downloadName"):
                if key in payload:
                    summary[key] = payload.get(key)
        return summary
    text = str(body)
    return {"httpStatus": response.get("status"), "body": text[:240], "length": len(text)}


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


def find_service(client: StudioClient, token: str) -> dict[str, Any] | None:
    response = client.request(
        "GET",
        "/protocol-conversions",
        token=token,
        params={"pageNo": 1, "pageSize": 50, "keyword": SERVICE_CODE},
        timeout=30,
    )
    data = response_data(response) or {}
    items = data.get("items") or []
    for item in items:
        if item.get("serviceCode") == SERVICE_CODE:
            return item
    return None


def service_payload(service_id: str | None) -> dict[str, Any]:
    return {
        "id": service_id,
        "serviceCode": SERVICE_CODE,
        "serviceName": SERVICE_NAME,
        "tokenRequired": True,
        "defaultSubscriptionName": "长期回归-S15客户SOAP协议转换默认订阅方",
        "sourceProtocol": "SOAP_11",
        "sourceMethod": "POST",
        "webserviceConfig": {
            "enabled": True,
            "soapVersion": "SOAP_11",
            "namespaceUri": "http://studio.longterm.regression/s15/customer-soap",
            "operationName": "submitCustomerTrace",
            "soapAction": "submitCustomerTrace",
            "requestRootName": "submitCustomerTraceRequest",
            "responseRootName": "submitCustomerTraceResponse",
        },
        "conversionMode": "FIELD_MAPPING",
        "fieldMappings": [
            {
                "sortOrder": 1,
                "sourcePosition": "BODY",
                "sourceField": "traceId",
                "targetField": "traceId",
                "valueType": "STRING",
                "required": False,
                "defaultValue": "长期回归S15SOAP追踪",
            },
            {
                "sortOrder": 2,
                "sourcePosition": "BODY",
                "sourceField": "customerName",
                "targetField": "customerName",
                "valueType": "STRING",
                "required": False,
                "defaultValue": "长期回归S15客户",
            },
        ],
        "rawTransformers": [],
        "fixedFields": [
            {
                "targetField": "scenario",
                "value": "长期回归-S15客户SOAP协议转换脱敏",
                "valueType": "STRING",
            }
        ],
        "bodyBridgeOptions": {},
        "requestPassthrough": {"body": False, "query": False, "headers": False},
        "targetDatasourceId": TARGET_DATASOURCE_ID,
        "targetPath": "/actuator/health",
        "targetProtocol": "HTTP_JSON",
        "targetMethod": "GET",
        "targetHeaders": {"X-Studio-Test-Module": "S15"},
        "targetQuery": {"trace": "{{traceId}}", "customer": "{{customerName}}"},
        "targetWebserviceConfig": {
            "enabled": True,
            "soapVersion": "SOAP_11",
            "namespaceUri": "http://studio.longterm.regression/s15/target",
            "operationName": "targetHealth",
            "requestRootName": "targetHealthRequest",
            "responseRootName": "targetHealthResponse",
        },
        "payloadMode": "OBJECT",
        "batchSize": 1,
        "responseStatus": {"path": "status", "code": "UP"},
    }


def ensure_service(client: StudioClient, token: str) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any] | None]:
    existing = find_service(client, token)
    save = client.request(
        "POST",
        "/protocol-conversions",
        token=token,
        json_body=service_payload(existing.get("id") if existing else None),
        timeout=30,
    )
    service = response_data(save) or {}
    publish = None
    if service.get("status") != "ONLINE":
        publish = client.request("POST", f"/protocol-conversions/{service.get('id')}/publish", token=token, timeout=30)
        service = response_data(publish) or service
    return service, save, publish


def create_subscription(client: StudioClient, token: str, service_id: str, batch: str) -> dict[str, Any]:
    return client.request(
        "POST",
        f"/protocol-conversions/{service_id}/subscriptions",
        token=token,
        json_body={"subscriptionName": f"长期回归-S15 SOAP脱敏修复回归订阅-{batch}"},
        timeout=30,
    )


def soap_envelope(batch: str, trace: str, subscription_token: str | None) -> tuple[str, dict[str, str]]:
    secrets = {
        "soapHeaderSecret": f"LT-S15-RAW-SOAP-TOKEN-{batch}",
        "soapPassword": f"LT-S15-RAW-SOAP-PASSWORD-{batch}",
        "soapApiKey": f"LT-S15-RAW-SOAP-APIKEY-{batch}",
        "subscriptionToken": subscription_token or "",
        "httpHeaderSecret": f"LT-S15-RAW-HTTP-HEADER-{batch}",
    }
    envelope = f"""<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:tns=\"http://studio.longterm.regression/s15/customer-soap\" apiKey=\"{secrets['soapApiKey']}\">
  <soap:Header>
    <tns:protocolConversionToken>{subscription_token or ''}</tns:protocolConversionToken>
    <tns:clientSecret>{secrets['soapHeaderSecret']}</tns:clientSecret>
  </soap:Header>
  <soap:Body>
    <tns:submitCustomerTraceRequest>
      <traceId>{trace}</traceId>
      <customerName>长期回归S15客户SOAP脱敏修复回归</customerName>
      <password>{secrets['soapPassword']}</password>
      <apiKey>{secrets['soapApiKey']}</apiKey>
    </tns:submitCustomerTraceRequest>
  </soap:Body>
</soap:Envelope>"""
    return envelope, secrets


def query_latest_access_log(client: StudioClient, token: str, service_id: str) -> dict[str, Any] | None:
    response = client.request(
        "POST",
        "/protocol-conversion-metrics/access-logs/query",
        token=token,
        json_body={"serviceId": str(service_id), "pageNo": 1, "pageSize": 10},
        timeout=30,
    )
    data = response_data(response) or {}
    items = data.get("items") or []
    return items[0] if items else None


def log_content(response: dict[str, Any]) -> str:
    data = response_data(response) or {}
    return str(data.get("content") or "") if isinstance(data, dict) else ""


def wait_for_log(client: StudioClient,
                 token: str,
                 service_id: str,
                 trace: str,
                 attempts: int = 10) -> tuple[dict[str, Any] | None, dict[str, Any] | None, dict[str, Any] | None]:
    latest = None
    download = None
    trace_response = None
    for _ in range(attempts):
        latest = query_latest_access_log(client, token, service_id)
        if latest and latest.get("id"):
            download = client.request("GET", f"/invocation-logs/protocol-conversions/{latest.get('id')}/download",
                                      token=token, timeout=60)
            trace_response = client.request("GET", f"/protocol-conversion-metrics/access-logs/{latest.get('id')}/trace",
                                            token=token, timeout=60)
            if trace in log_content(download):
                return latest, download, trace_response
        time.sleep(1)
    return latest, download, trace_response


def leaked_labels(text: str, secrets: dict[str, str]) -> list[str]:
    return [key for key, value in secrets.items() if value and value in text]


def clean_message(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return not any(marker in text for marker in FORBIDDEN_INTERNAL_MARKERS)


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    trace = "LT-S15-SOAP-" + batch
    results: list[dict[str, Any]] = []
    client = StudioClient()

    health = read_health()
    health_ok = (
        health.get("nacos") == "OK"
        and isinstance(health.get("server"), dict)
        and health["server"].get("status") == "UP"
        and isinstance(health.get("worker"), dict)
        and health["worker"].get("status") == "UP"
    )
    add_result(results, "S15-ENV-001", "运行态", "Nacos readiness=OK，Server/Worker health=UP", health, health_ok)

    token, login = client.login()
    if not token:
        add_result(results, "S15-AUTH-001", "认证", "admin 登录成功", summarize(login), False)
        print(json.dumps({"batch": batch, "trace": trace, "results": results}, ensure_ascii=False, indent=2, default=str))
        return 1

    service, save, publish = ensure_service(client, token)
    subscription_response = create_subscription(client, token, str(service.get("id")), batch)
    subscription = response_data(subscription_response) or {}
    subscription_token = subscription.get("token")
    add_result(
        results,
        "S15-BASE-001",
        "长期对象基线",
        "SOAP 源协议转换服务在线，新增 S15 中文命名订阅并保留",
        {
            "save": summarize(save),
            "publish": summarize(publish),
            "subscription": summarize(subscription_response),
            "subscriptionId": subscription.get("id"),
            "tokenPresent": bool(subscription_token),
        },
        service.get("status") == "ONLINE" and is_result_ok(subscription_response) and bool(subscription_token),
    )

    wsdl_response = client.wsdl(str(service.get("serviceCode")), str(service.get("serviceKey")))
    wsdl_body = str(wsdl_response.get("body") or "")
    add_result(
        results,
        "S15-WSDL-001",
        "SOAP WSDL",
        "WSDL 可访问，包含业务 operation，不包含本批次敏感值",
        summarize(wsdl_response),
        wsdl_response.get("status") == 200
        and "wsdl:definitions" in wsdl_body
        and "submitCustomerTrace" in wsdl_body
        and "LT-S15-RAW" not in wsdl_body,
    )

    envelope, secrets = soap_envelope(batch, trace, subscription_token)
    invoke_response = client.open_soap(str(service.get("serviceCode")), str(service.get("serviceKey")), envelope, batch)
    access_log, download, trace_response = wait_for_log(client, token, str(service.get("id")), trace)
    download_text = log_content(download or {})
    trace_text = json.dumps(trace_response, ensure_ascii=False, default=str)
    download_leaks = leaked_labels(download_text, secrets)
    trace_leaks = leaked_labels(trace_text, secrets)
    add_result(
        results,
        "S15-SOAP-001",
        "SOAP Trace/完整日志脱敏",
        "SOAP OpenAPI 调用成功，Trace 和完整日志均不泄露 XML 元素/属性/订阅 Token 原文",
        {
            "invoke": summarize(invoke_response),
            "accessLogId": (access_log or {}).get("id"),
            "download": summarize(download),
            "trace": summarize(trace_response),
            "downloadLeakedLabels": download_leaks,
            "traceLeakedLabels": trace_leaks,
            "containsTrace": trace in download_text,
            "maskedCount": download_text.count("******"),
            "clean": clean_message(download) and clean_message(trace_response),
        },
        invoke_response.get("status") == 200
        and access_log is not None
        and not download_leaks
        and not trace_leaks
        and trace in download_text
        and clean_message(download)
        and clean_message(trace_response),
        need_fix=bool(download_leaks or trace_leaks) or not clean_message(download) or not clean_message(trace_response),
    )

    total = len(results)
    passed = len([item for item in results if item["result"] == "PASS"])
    failed = len([item for item in results if item["result"] == "FAIL"])
    need_fix = len([item for item in results if item.get("needFix")])
    summary = {
        "batch": batch,
        "trace": trace,
        "serviceId": service.get("id"),
        "subscriptionId": subscription.get("id"),
        "accessLogId": (access_log or {}).get("id"),
        "total": total,
        "pass": passed,
        "fail": failed,
        "blocked": 0,
        "needFix": need_fix,
        "results": results,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2, default=str))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

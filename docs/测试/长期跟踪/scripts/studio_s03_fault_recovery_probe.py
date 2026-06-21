#!/usr/bin/env python
"""Studio S03 fault injection and recovery probe.

The probe is data-preserving. It keeps long-term fixtures, briefly toggles the
long-term project worker binding to validate the no-worker path, and restores
the binding before leaving.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timedelta
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
INGESTION_SERVICE_ID = os.getenv("STUDIO_INGESTION_SERVICE_ID", "2068130320853168129")
COLLECTION_INCREMENTAL_ID = os.getenv("STUDIO_COLLECTION_INCREMENTAL_ID", "2068153332268711937")
DISABLED_USER = os.getenv("STUDIO_S01_DISABLED_USER", "lt_reg_s01_disabled_guard")
S01_PASSWORD = os.getenv("STUDIO_S01_PASSWORD", "LtRegS01!2026")

FINAL_STATUSES = {"SUCCESS", "FAILED", "CANCELLED", "TERMINATED"}
STACK_MARKERS = ("java.", "com.jdragon", "at ", ".java:", "Traceback", "Exception:")


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

    def login(self, username: str, password: str) -> tuple[str | None, dict[str, Any]]:
        response = self.request(
            "POST",
            "/auth/login",
            project_id=LONG_PROJECT_ID,
            json_body={"username": username, "password": password},
            timeout=20,
        )
        token = None
        if isinstance(response.get("body"), dict):
            token = (response["body"].get("data") or {}).get("token")
        return token, response


def response_data(response: dict[str, Any]) -> Any:
    body = response.get("body")
    return body.get("data") if isinstance(body, dict) else None


def response_code(response: dict[str, Any]) -> str | None:
    body = response.get("body")
    return body.get("code") if isinstance(body, dict) else None


def is_ok(response: dict[str, Any]) -> bool:
    return response.get("status") == 200 and isinstance(response.get("body"), dict) and response["body"].get("success") is True


def clean_message(value: Any) -> bool:
    text = json.dumps(value, ensure_ascii=False, default=str)
    return not any(marker in text for marker in STACK_MARKERS)


def summarize(response: Any) -> Any:
    if not isinstance(response, dict) or "body" not in response:
        return response
    body = response.get("body")
    if isinstance(body, dict):
        payload = body.get("data")
        result: dict[str, Any] = {
            "status": response.get("status"),
            "success": body.get("success"),
            "code": body.get("code"),
            "message": body.get("message"),
        }
        if isinstance(payload, list):
            result["dataCount"] = len(payload)
        elif isinstance(payload, dict):
            result["dataKeys"] = list(payload.keys())[:12]
            for key in (
                "id",
                "status",
                "success",
                "message",
                "durationMs",
                "connectionStatus",
                "lastConnectionTestMessage",
                "executionMs",
                "workflowRunId",
                "cacheEnabled",
                "cacheHit",
            ):
                if key in payload:
                    result[key] = payload.get(key)
        return result
    return {"status": response.get("status"), "body": str(body)[:800]}


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
    error = None
    try:
        passed = expectation(evidence)
    except Exception as exc:
        error = str(exc)
    payload = {
        "case": case_id,
        "module": module,
        "actual": summarize(evidence),
        "result": "PASS" if passed else "FAIL",
        "needFix": (not passed if need_fix is None else need_fix),
        "extra": extra or {},
    }
    if error:
        payload["extra"]["expectationError"] = error
    results.append(payload)


def add_blocked(results: list[dict[str, Any]], case_id: str, module: str, reason: str, extra: dict[str, Any] | None = None) -> None:
    results.append(
        {
            "case": case_id,
            "module": module,
            "actual": {"reason": reason},
            "result": "BLOCKED",
            "needFix": False,
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


def read_ingestion_row(ingest_id: str) -> dict[str, Any] | None:
    with mysql_connection() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                select ingest_id, customer_id, tag_name, tag_value, request_source, received_at
                from lt_reg_api_ingestion_result
                where ingest_id = %s
                """,
                (ingest_id,),
            )
            return cursor.fetchone()


def run_ids(client: StudioClient, token: str) -> set[str]:
    response = client.request(
        "GET",
        "/runs",
        token,
        LONG_PROJECT_ID,
        params={"collectionTaskId": COLLECTION_INCREMENTAL_ID, "includeRunRecords": "true"},
        timeout=20,
    )
    return {str(item.get("id")) for item in ((response_data(response) or {}).get("runRecords") or [])}


def wait_for_new_collection_run(client: StudioClient, token: str, before_ids: set[str], timeout_seconds: int = 120) -> dict[str, Any] | None:
    deadline = time.time() + timeout_seconds
    latest = None
    while time.time() < deadline:
        response = client.request(
            "GET",
            "/runs",
            token,
            LONG_PROJECT_ID,
            params={"collectionTaskId": COLLECTION_INCREMENTAL_ID, "includeRunRecords": "true"},
            timeout=20,
        )
        records = (response_data(response) or {}).get("runRecords") or []
        for record in records:
            record_id = str(record.get("id"))
            if record_id not in before_ids:
                latest = record
                if str(record.get("status")) in FINAL_STATUSES:
                    return record
        time.sleep(3)
    return latest


def save_worker_binding(client: StudioClient, token: str, binding: dict[str, Any], enabled: bool) -> dict[str, Any]:
    return client.request(
        "POST",
        "/system/project-workers",
        token,
        LONG_PROJECT_ID,
        json_body={
            "id": binding.get("id"),
            "projectId": int(LONG_PROJECT_ID),
            "workerGroupCode": binding.get("workerGroupCode"),
            "workerCode": binding.get("workerCode") or binding.get("workerGroupCode"),
            "enabled": 1 if enabled else 0,
        },
        timeout=20,
    )


def ensure_fault_datasource(client: StudioClient, token: str) -> tuple[str | None, dict[str, Any]]:
    name = "长期回归-S03不可达MySQL故障数据源"
    existing = None
    list_response = client.request("GET", "/datasources", token, LONG_PROJECT_ID, timeout=30)
    for item in response_data(list_response) or []:
        if item.get("name") == name:
            existing = item
            break
    payload = {
        "id": existing.get("id") if existing else None,
        "name": name,
        "typeCode": "mysql8",
        "enabled": True,
        "executable": True,
        "manualConnectionTestTimeoutSeconds": 3,
        "scheduledConnectionTestTimeoutSeconds": 3,
        "technicalMetadata": {
            "host": "127.0.0.1",
            "port": 9,
            "database": MYSQL_DATABASE,
            "userName": MYSQL_USER,
            "password": "长期回归S03故障注入密码",
            "usePool": False,
            "jdbcUrl": "jdbc:mysql://127.0.0.1:9/studio_longterm_regression?connectTimeout=1000&socketTimeout=1000&useSSL=false",
        },
        "businessMetadata": {
            "__metaModels": [],
            "测试用途": "S03 MySQL断连安全替代：不可达端口，不影响长期库",
        },
    }
    response = client.request("POST", "/datasources", token, LONG_PROJECT_ID, json_body=payload, timeout=30)
    saved = response_data(response) or {}
    return str(saved.get("id")) if saved.get("id") else None, response


def window_query() -> dict[str, Any]:
    now = datetime.now()
    return {
        "startTime": (now - timedelta(hours=24)).strftime("%Y-%m-%d %H:%M:%S"),
        "endTime": (now + timedelta(minutes=5)).strftime("%Y-%m-%d %H:%M:%S"),
        "executionType": "WORKFLOW_NODE",
        "status": "SUCCESS",
        "workerGroupCode": "studio-online-worker-01",
        "pageNo": 1,
        "pageSize": 10,
    }


def main() -> int:
    client = StudioClient()
    admin_token, login_response = client.login(ADMIN_USER, ADMIN_PASSWORD)
    if not admin_token:
        print(json.dumps({"fatal": "admin login failed", "login": summarize(login_response)}, ensure_ascii=False, indent=2))
        return 2

    results: list[dict[str, Any]] = []
    fixtures: dict[str, Any] = {}
    initial_health = read_health()
    add_result(
        results,
        "S03-ENV-001",
        "运行态健康",
        lambda value: value.get("nacos") == "OK"
        and value.get("server", {}).get("status") == "UP"
        and value.get("worker", {}).get("status") == "UP",
        initial_health,
        need_fix=False,
    )

    _, disabled_login = client.login(DISABLED_USER, S01_PASSWORD)
    add_result(
        results,
        "S03-AUTH-001",
        "禁用用户错误恢复",
        lambda response: response.get("status") in (401, 403) and response_code(response) == "UNAUTHORIZED",
        disabled_login,
        {"regression": "M09-FIX-03"},
    )

    worker_list = client.request("GET", "/system/project-workers", admin_token, LONG_PROJECT_ID, params={"projectId": LONG_PROJECT_ID}, timeout=20)
    online_binding = None
    for item in response_data(worker_list) or []:
        if item.get("workerGroupCode") == "studio-online-worker-01" and item.get("enabled") is True:
            online_binding = item
            break
    fixtures["onlineWorkerBindingBefore"] = online_binding
    if not online_binding:
        add_blocked(results, "S03-WORKER-001", "Worker下线/恢复", "长期项目未找到已启用的 studio-online-worker-01 绑定，无法安全注入")
    else:
        disable_response = None
        restore_response = None
        try:
            disable_response = save_worker_binding(client, admin_token, online_binding, False)
            trigger_without_worker = client.request("POST", f"/collection-tasks/{COLLECTION_INCREMENTAL_ID}/trigger", admin_token, LONG_PROJECT_ID, timeout=30)
            add_result(
                results,
                "S03-WORKER-001",
                "Worker下线提示",
                lambda response: response.get("status") == 400
                and response_code(response) == "BAD_REQUEST"
                and "No authorized online worker" in json.dumps(response.get("body"), ensure_ascii=False, default=str),
                trigger_without_worker,
                {"disableBinding": summarize(disable_response), "bindingId": online_binding.get("id")},
                need_fix=False,
            )
        finally:
            restore_response = save_worker_binding(client, admin_token, online_binding, True)
            fixtures["workerRestore"] = summarize(restore_response)

        after_restore_list = client.request("GET", "/system/project-workers", admin_token, LONG_PROJECT_ID, params={"projectId": LONG_PROJECT_ID}, timeout=20)
        restored_online = any(
            item.get("workerGroupCode") == "studio-online-worker-01"
            and item.get("enabled") is True
            and item.get("displayStatus") == "ONLINE"
            for item in (response_data(after_restore_list) or [])
        )
        before_runs = run_ids(client, admin_token)
        trigger_after_restore = client.request("POST", f"/collection-tasks/{COLLECTION_INCREMENTAL_ID}/trigger", admin_token, LONG_PROJECT_ID, timeout=30)
        new_run = wait_for_new_collection_run(client, admin_token, before_runs) if is_ok(trigger_after_restore) else None
        add_result(
            results,
            "S03-WORKER-002",
            "Worker恢复后采集运行",
            lambda response: restored_online and is_ok(response) and new_run is not None and new_run.get("status") == "SUCCESS",
            trigger_after_restore,
            {"restoredOnline": restored_online, "newRun": new_run, "restoreBinding": fixtures.get("workerRestore")},
        )

    fault_datasource_id, save_fault_ds = ensure_fault_datasource(client, admin_token)
    fixtures["faultDatasource"] = {"id": fault_datasource_id, "save": summarize(save_fault_ds)}
    if fault_datasource_id:
        test_fault_ds = client.request("POST", f"/datasources/{fault_datasource_id}/test", admin_token, LONG_PROJECT_ID, timeout=20)
        test_data = response_data(test_fault_ds) or {}
        add_result(
            results,
            "S03-MYSQL-001",
            "MySQL断连安全替代",
            lambda response: is_ok(response)
            and test_data.get("success") is False
            and str(test_data.get("status")) == "UNAVAILABLE"
            and clean_message(response),
            test_fault_ds,
            {"datasourceId": fault_datasource_id},
        )
        discover_fault_ds = client.request(
            "POST",
            f"/datasources/{fault_datasource_id}/discover",
            admin_token,
            LONG_PROJECT_ID,
            params={"keyword": "lt_reg_customer_order", "pageNo": 1, "pageSize": 5},
            timeout=20,
        )
        discover_data = response_data(discover_fault_ds) or {}
        discover_models = discover_data.get("models") or []
        add_result(
            results,
            "S03-MYSQL-002",
            "不可达数据源发现受控空结果",
            lambda response: response.get("status") == 200 and isinstance(discover_models, list) and len(discover_models) == 0 and clean_message(response),
            discover_fault_ds,
            {"datasourceId": fault_datasource_id, "modelCount": len(discover_models)},
            need_fix=discover_fault_ds.get("status") == 500 or not clean_message(discover_fault_ds),
        )
    else:
        add_result(results, "S03-MYSQL-001", "MySQL断连安全替代", lambda response: False, save_fault_ds)

    slow_sql = client.request(
        "POST",
        "/data-development/sql/execute",
        admin_token,
        LONG_PROJECT_ID,
        json_body={
            "datasourceId": int(DATASOURCE_ID),
            "scriptType": "SQL",
            "content": "select sleep(2) as slow_seconds, count(*) as order_count from lt_reg_customer_order",
            "maxRows": 5,
        },
        timeout=40,
    )
    slow_data = response_data(slow_sql) or {}
    add_result(
        results,
        "S03-MYSQL-003",
        "MySQL慢查询稳定性",
        lambda response: is_ok(response)
        and int(slow_data.get("executionMs") or 0) >= 1500
        and (slow_data.get("rows") or [{}])[0].get("order_count") is not None,
        slow_sql,
        {"executionMs": slow_data.get("executionMs"), "rows": slow_data.get("rows")},
    )

    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    ingest_id = f"LT-S03-{batch}"
    ingestion_body = {
        "ingestId": ingest_id,
        "customerId": "CUST-LT-002",
        "tagName": "S03故障恢复客户标签",
        "tagValue": "服务失败提示验证",
        "rawPayload": {"业务含义": "S03数据接入失败日志边界探针", "测试批次": batch},
    }
    ingestion_query = {"requestSource": "长期回归S03故障注入"}
    first_ingestion = client.request(
        "POST",
        f"/data-ingestion-services/{INGESTION_SERVICE_ID}/debug",
        admin_token,
        LONG_PROJECT_ID,
        json_body={"headers": {}, "query": ingestion_query, "form": {}, "body": ingestion_body},
        timeout=90,
    )
    duplicate_ingestion = client.request(
        "POST",
        f"/data-ingestion-services/{INGESTION_SERVICE_ID}/debug",
        admin_token,
        LONG_PROJECT_ID,
        json_body={"headers": {}, "query": ingestion_query, "form": {}, "body": dict(ingestion_body, tagValue="重复主键不应覆盖")},
        timeout=90,
    )
    inserted = read_ingestion_row(ingest_id)
    add_result(
        results,
        "S03-ING-001",
        "接入失败提示脱敏",
        lambda response: is_ok(first_ingestion)
        and not is_ok(response)
        and clean_message(response)
        and inserted is not None
        and inserted.get("tag_value") == "服务失败提示验证",
        duplicate_ingestion,
        {"firstWrite": summarize(first_ingestion), "ingestId": ingest_id, "inserted": inserted, "regression": "BUG-M05-002"},
        need_fix=False,
    )

    ops_query = window_query()
    overview = client.request("POST", "/ops-center/overview/query", admin_token, LONG_PROJECT_ID, json_body=ops_query, timeout=30)
    runs = client.request("POST", "/ops-center/runs/query", admin_token, LONG_PROJECT_ID, json_body=ops_query, timeout=30)
    service_events = client.request("POST", "/ops-center/service-events/query", admin_token, LONG_PROJECT_ID, json_body=ops_query, timeout=30)
    ingestion_events = client.request("POST", "/ops-center/ingestion-events/query", admin_token, LONG_PROJECT_ID, json_body=ops_query, timeout=30)
    overview_data = response_data(overview) or {}
    service_total = (response_data(service_events) or {}).get("total")
    ingestion_total = (response_data(ingestion_events) or {}).get("total")
    run_total = (response_data(runs) or {}).get("total")
    add_result(
        results,
        "S03-OPS-001",
        "运维运行域筛选隔离",
        lambda response: is_ok(response)
        and overview_data.get("healthStatus") == "HEALTHY"
        and int(service_total or 0) == 0
        and int(ingestion_total or 0) == 0
        and int(run_total or 0) >= 1
        and clean_message(runs)
        and clean_message(service_events)
        and clean_message(ingestion_events),
        overview,
        {
            "query": ops_query,
            "runTotal": run_total,
            "serviceEventTotal": service_total,
            "ingestionEventTotal": ingestion_total,
            "runs": summarize(runs),
            "serviceEvents": summarize(service_events),
            "ingestionEvents": summarize(ingestion_events),
            "regression": "FIX-M08-001",
        },
    )

    add_blocked(
        results,
        "S03-REDIS-001",
        "Redis不可用",
        "当前 Redis 是共享运行依赖，强停会影响 Server/Worker；代码已确认数据服务缓存有 Redis 异常本地内存降级，本轮仅记录为需隔离环境注入。",
        {"codeBoundary": "DataServiceResponseCacheService"},
    )
    add_blocked(
        results,
        "S03-NACOS-001",
        "Nacos短暂不可用",
        "当前 Server/Worker 通过 IDEA run configuration 使用本地 Nacos；停止 8848 会影响配置/服务注册，需专用隔离运行实例或可控 Nacos 容器。",
        {"nacos": initial_health.get("nacos")},
    )
    add_blocked(
        results,
        "S03-LOG-001",
        "对象存储日志失败",
        "当前 Server/Worker 使用 IDEA run configuration 注入的真实 OBJECT_STORAGE 配置；错误凭证或不可达端点注入需独立实例验证，避免影响长期日志基线。",
        {"codeBoundary": "RunLogStorageService.objectStorageAvailable"},
    )

    final_health = read_health()
    output = {
        "executedAt": datetime.now().isoformat(timespec="seconds"),
        "fixtures": fixtures,
        "results": results,
        "stats": {
            "PASS": sum(1 for item in results if item["result"] == "PASS"),
            "FAIL": sum(1 for item in results if item["result"] == "FAIL"),
            "BLOCKED": sum(1 for item in results if item["result"] == "BLOCKED"),
            "needFix": sum(1 for item in results if item.get("needFix")),
        },
        "health": {"initial": initial_health, "final": final_health},
    }
    print(json.dumps(output, ensure_ascii=False, indent=2, default=str))
    return 1 if output["stats"]["FAIL"] or output["stats"]["needFix"] else 0


if __name__ == "__main__":
    sys.exit(main())

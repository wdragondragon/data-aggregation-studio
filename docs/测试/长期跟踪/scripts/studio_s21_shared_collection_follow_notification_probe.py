#!/usr/bin/env python
"""Studio S21 shared collection-task follow notification probe.

This probe preserves long-term data. It creates batch-scoped MySQL tables and
a traceable collection task, shares it to the receiver project, lets a receiver
member follow the shared task, then verifies collection-run notification fanout
and notification click target project context.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime
from typing import Any, Callable

import pymysql
import requests


BASE_URL = os.getenv("STUDIO_BASE_URL", "http://127.0.0.1:18080/api/v1").rstrip("/")
TENANT_ID = os.getenv("STUDIO_TENANT_ID", "default")
LONG_PROJECT_ID = os.getenv("STUDIO_LONG_PROJECT_ID", "2068077680446365698")
RECEIVER_PROJECT_ID = os.getenv("STUDIO_RECEIVER_PROJECT_ID", "2068301893337849857")
ADMIN_USER = os.getenv("STUDIO_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("STUDIO_ADMIN_PASSWORD", "admin123")
RECEIVER_USER = os.getenv("STUDIO_RECEIVER_USER", "lt_reg_s01_receiver_member")
RECEIVER_PASSWORD = os.getenv("STUDIO_RECEIVER_PASSWORD", "LtRegS01!2026")

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
LONG_MYSQL_DB = os.getenv("STUDIO_MYSQL_DATABASE", "studio_longterm_regression")
META_MYSQL_DB = os.getenv("STUDIO_META_MYSQL_DB", "data_aggregation_studio")
DATASOURCE_ID = os.getenv("STUDIO_LONG_DATASOURCE_ID", "2068077811652583425")

FINAL_STATUSES = {"SUCCESS", "FAILED", "CANCELED", "CANCELLED", "TERMINATED", "SKIPPED"}


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
            headers["Authorization"] = "Bearer " + token
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

    def login(self, username: str, password: str, project_id: str) -> tuple[str | None, dict[str, Any]]:
        response = self.request(
            "POST",
            "/auth/login",
            project_id=project_id,
            json_body={"username": username, "password": password},
            timeout=20,
        )
        token = None
        if isinstance(response.get("body"), dict):
            token = (response["body"].get("data") or {}).get("token")
        return token, response


def is_ok(response: dict[str, Any] | None) -> bool:
    return (
        response is not None
        and response.get("status") == 200
        and isinstance(response.get("body"), dict)
        and response["body"].get("success") is True
    )


def response_code(response: dict[str, Any] | None) -> str | None:
    body = response.get("body") if response else None
    return body.get("code") if isinstance(body, dict) else None


def response_data(response: dict[str, Any] | None) -> Any:
    body = response.get("body") if response else None
    return body.get("data") if isinstance(body, dict) else None


def expect_ok(response: dict[str, Any]) -> bool:
    return is_ok(response)


def expect_not_found(response: dict[str, Any]) -> bool:
    return response.get("status") == 404 and response_code(response) == "NOT_FOUND"


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
        if isinstance(data, dict):
            for key in (
                "id",
                "code",
                "name",
                "status",
                "resourceType",
                "resourceId",
                "enabled",
                "targetProjectId",
                "following",
                "physicalLocator",
            ):
                if data.get(key) is not None:
                    summary["data." + key] = data.get(key)
            if "items" in data:
                summary["data.itemsCount"] = len(data.get("items") or [])
        elif isinstance(data, list):
            summary["dataCount"] = len(data)
        return summary
    return {"status": response.get("status"), "body": str(body)[:500]}


def add_case(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    expected: str,
    expectation: Callable[[dict[str, Any]], bool],
    response: dict[str, Any],
    need_fix: bool | None = None,
    evidence: dict[str, Any] | None = None,
) -> None:
    passed = expectation(response)
    results.append(
        {
            "case": case_id,
            "module": module,
            "expected": expected,
            "actual": summarize(response),
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
            "evidence": evidence or {},
        }
    )


def add_bool_case(
    results: list[dict[str, Any]],
    case_id: str,
    module: str,
    expected: str,
    passed: bool,
    actual: Any,
    need_fix: bool | None = None,
) -> None:
    results.append(
        {
            "case": case_id,
            "module": module,
            "expected": expected,
            "actual": actual,
            "result": "PASS" if passed else "FAIL",
            "needFix": (not passed if need_fix is None else need_fix),
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


def mysql_conn(database: str) -> pymysql.Connection:
    return pymysql.connect(
        host=MYSQL_HOST,
        port=MYSQL_PORT,
        user=MYSQL_USER,
        password=MYSQL_PASSWORD,
        database=database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=10,
        autocommit=True,
    )


def ensure_batch_tables(source_table: str, target_table: str, batch: str) -> dict[str, Any]:
    with mysql_conn(LONG_MYSQL_DB) as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                create table if not exists {source_table} (
                  order_id varchar(48) primary key comment '订单编号',
                  customer_name varchar(120) not null comment '客户名称',
                  order_amount decimal(12,2) not null comment '订单金额',
                  order_status varchar(32) not null comment '订单状态',
                  updated_at datetime not null comment '更新时间'
                ) comment='长期回归-S21共享采集通知源表'
                """
            )
            cur.execute(
                f"""
                create table if not exists {target_table} (
                  order_id varchar(48) primary key comment '订单编号',
                  customer_name varchar(120) not null comment '客户名称',
                  order_amount decimal(12,2) not null comment '订单金额',
                  order_status varchar(32) not null comment '订单状态',
                  updated_at datetime not null comment '更新时间'
                ) comment='长期回归-S21共享采集通知目标表'
                """
            )
            cur.execute(
                f"""
                replace into {source_table}
                  (order_id, customer_name, order_amount, order_status, updated_at)
                values
                  (%s, '华东重点客户事业部', 12880.50, '待复核', '2026-06-22 10:20:00'),
                  (%s, '西南渠道协同中心', 9812.00, '已确认', '2026-06-22 10:25:00')
                """,
                (f"S21-NOTICE-{batch}-001", f"S21-NOTICE-{batch}-002"),
            )
    return {"sourceTable": source_table, "targetTable": target_table, "sourceRows": 2}


def user_id_by_username(username: str) -> str | None:
    with mysql_conn(META_MYSQL_DB) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "select id from sys_user where tenant_id=%s and username=%s and deleted=0 limit 1",
                (TENANT_ID, username),
            )
            row = cur.fetchone()
    return str(row["id"]) if row else None


def sync_selected_models(client: StudioClient, token: str, locators: list[str]) -> dict[str, Any]:
    return client.request(
        "POST",
        f"/models/datasource/{DATASOURCE_ID}/sync-selected",
        token=token,
        project_id=LONG_PROJECT_ID,
        json_body={"physicalLocators": locators},
        timeout=90,
    )


def list_models(client: StudioClient, token: str) -> list[dict[str, Any]]:
    response = client.request(
        "GET",
        f"/models/datasource/{DATASOURCE_ID}",
        token=token,
        project_id=LONG_PROJECT_ID,
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


def create_task_payload(batch: str, source_model: dict[str, Any], target_model: dict[str, Any]) -> dict[str, Any]:
    return {
        "name": "长期回归-S21共享采集通知链路-" + batch,
        "sourceBindings": [
            {
                "sourceAlias": "s21_order",
                "datasourceId": DATASOURCE_ID,
                "modelId": source_model["id"],
                "readerOptions": {},
            }
        ],
        "targetBinding": {
            "datasourceId": DATASOURCE_ID,
            "modelId": target_model["id"],
            "writerOptions": {"writeMode": "insert"},
        },
        "fieldMappings": [
            {"sourceAlias": "s21_order", "sourceField": "order_id", "targetField": "order_id", "transformers": []},
            {"sourceAlias": "s21_order", "sourceField": "customer_name", "targetField": "customer_name", "transformers": []},
            {"sourceAlias": "s21_order", "sourceField": "order_amount", "targetField": "order_amount", "transformers": []},
            {"sourceAlias": "s21_order", "sourceField": "order_status", "targetField": "order_status", "transformers": []},
            {"sourceAlias": "s21_order", "sourceField": "updated_at", "targetField": "updated_at", "transformers": []},
        ],
        "executionOptions": {},
        "schedule": None,
    }


def latest_run_after(task_id: str, started_at: str) -> dict[str, Any] | None:
    with mysql_conn(META_MYSQL_DB) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                select id, collection_task_id, status, message, started_at, ended_at,
                       collected_records, write_succeed_records, write_failed_records, failed_records
                from run_record
                where tenant_id=%s and project_id=%s and collection_task_id=%s and created_at >= %s
                order by created_at desc, id desc
                limit 1
                """,
                (TENANT_ID, LONG_PROJECT_ID, task_id, started_at),
            )
            return cur.fetchone()


def active_counts(task_id: str) -> dict[str, int]:
    with mysql_conn(META_MYSQL_DB) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                select count(*) as cnt
                from dispatch_task
                where tenant_id=%s and project_id=%s and collection_task_id=%s and status in ('QUEUED','RUNNING')
                """,
                (TENANT_ID, LONG_PROJECT_ID, task_id),
            )
            dispatch_count = int((cur.fetchone() or {}).get("cnt") or 0)
            cur.execute(
                """
                select count(*) as cnt
                from run_record
                where tenant_id=%s and project_id=%s and collection_task_id=%s and status='RUNNING'
                """,
                (TENANT_ID, LONG_PROJECT_ID, task_id),
            )
            run_count = int((cur.fetchone() or {}).get("cnt") or 0)
    return {"dispatch": dispatch_count, "run": run_count, "total": dispatch_count + run_count}


def wait_idle(task_id: str, timeout_seconds: int = 180) -> bool:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        if active_counts(task_id)["total"] == 0:
            return True
        time.sleep(2)
    return False


def trigger_and_wait(client: StudioClient, token: str, task_id: str, timeout_seconds: int = 240) -> tuple[dict[str, Any], dict[str, Any] | None]:
    wait_idle(task_id)
    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")
    trigger_response = client.request("POST", f"/collection-tasks/{task_id}/trigger", token=token, timeout=45)
    deadline = time.time() + timeout_seconds
    latest = None
    while time.time() < deadline:
        latest = latest_run_after(task_id, started_at)
        if latest and str(latest.get("status")) in FINAL_STATUSES:
            return trigger_response, latest
        time.sleep(3)
    return trigger_response, latest


def target_rows(target_table: str) -> list[dict[str, Any]]:
    with mysql_conn(LONG_MYSQL_DB) as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                select order_id, customer_name, cast(order_amount as char) as order_amount, order_status,
                       date_format(updated_at, '%Y-%m-%d %H:%i:%s') as updated_at
                from {target_table}
                order by order_id
                """
            )
            return cur.fetchall()


def notification_rows(recipient_user_id: str, run_record_id: str) -> list[dict[str, Any]]:
    with mysql_conn(META_MYSQL_DB) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                select id, recipient_user_id, category, title, target_type, target_id, target_path,
                       target_tenant_id, target_project_id, dedupe_key, created_at
                from studio_notification
                where recipient_user_id=%s
                  and category='COLLECTION_TASK_RUN'
                  and target_type='COLLECTION_TASK_RUN'
                  and target_id=%s
                order by created_at desc, id desc
                """,
                (recipient_user_id, run_record_id),
            )
            rows = cur.fetchall()
    return [dict(row) for row in rows]


def main() -> int:
    batch = datetime.now().strftime("%Y%m%d%H%M%S")
    source_table = "lt_reg_s21_src_" + batch
    target_table = "lt_reg_s21_tgt_" + batch
    client = StudioClient()
    results: list[dict[str, Any]] = []
    evidence: dict[str, Any] = {"batch": batch, "health": read_health()}

    health_ok = (
        evidence["health"].get("nacos") == "OK"
        and isinstance(evidence["health"].get("server"), dict)
        and evidence["health"]["server"].get("status") == "UP"
        and isinstance(evidence["health"].get("worker"), dict)
        and evidence["health"]["worker"].get("status") == "UP"
    )
    add_bool_case(
        results,
        "S21-ENV-001",
        "运行态",
        "Nacos readiness=OK，Server/Worker health=UP",
        health_ok,
        evidence["health"],
        need_fix=not health_ok,
    )

    admin_token, admin_login = client.login(ADMIN_USER, ADMIN_PASSWORD, LONG_PROJECT_ID)
    receiver_token, receiver_login = client.login(RECEIVER_USER, RECEIVER_PASSWORD, RECEIVER_PROJECT_ID)
    add_case(results, "S21-AUTH-001", "认证", "管理员登录成功", expect_ok, admin_login, need_fix=False)
    add_case(results, "S21-AUTH-002", "认证", "接收项目成员登录成功", expect_ok, receiver_login, need_fix=False)
    receiver_user_id = user_id_by_username(RECEIVER_USER)
    evidence["receiverUser"] = {"username": RECEIVER_USER, "userId": receiver_user_id}
    if not admin_token or not receiver_token or receiver_user_id is None:
        print(json.dumps(output(batch, results, evidence), ensure_ascii=False, indent=2, default=str))
        return 1

    table_setup = ensure_batch_tables(source_table, target_table, batch)
    evidence["tables"] = table_setup
    add_bool_case(
        results,
        "S21-MYSQL-001",
        "MySQL 长期夹具",
        "本批次源/目标表已创建并保留，源表写入 2 行业务样例",
        True,
        table_setup,
        need_fix=False,
    )

    sync_response = sync_selected_models(client, admin_token, [source_table, target_table])
    models = list_models(client, admin_token)
    source_model = model_by_locator(models, source_table)
    target_model = model_by_locator(models, target_table)
    add_bool_case(
        results,
        "S21-MODEL-001",
        "模型同步",
        "本批次源/目标表模型同步后可被采集任务引用",
        is_ok(sync_response) and source_model is not None and target_model is not None,
        {
            "sync": summarize(sync_response),
            "sourceModelId": (source_model or {}).get("id"),
            "targetModelId": (target_model or {}).get("id"),
        },
    )
    if source_model is None or target_model is None:
        print(json.dumps(output(batch, results, evidence), ensure_ascii=False, indent=2, default=str))
        return 1

    task_response = client.request(
        "POST",
        "/collection-tasks",
        admin_token,
        LONG_PROJECT_ID,
        json_body=create_task_payload(batch, source_model, target_model),
        timeout=60,
    )
    task = response_data(task_response) if is_ok(task_response) else None
    task_id = str(task.get("id")) if isinstance(task, dict) and task.get("id") else None
    evidence["taskId"] = task_id
    add_bool_case(
        results,
        "S21-FIXTURE-001",
        "共享采集任务夹具",
        "创建具有业务展示名的 S21 采集任务",
        task_id is not None,
        {"task": summarize(task_response)},
    )
    if task_id is None:
        print(json.dumps(output(batch, results, evidence), ensure_ascii=False, indent=2, default=str))
        return 1

    publish_response = client.request("POST", f"/collection-tasks/{task_id}/online", admin_token, LONG_PROJECT_ID, timeout=45)
    add_case(results, "S21-FIXTURE-002", "共享采集任务夹具", "采集任务上线成功", expect_ok, publish_response)

    share_body = {
        "sourceProjectId": int(LONG_PROJECT_ID),
        "targetProjectId": int(RECEIVER_PROJECT_ID),
        "resourceType": "COLLECTION_TASK",
        "resourceId": int(task_id),
        "enabled": 1,
    }
    share_response = client.request(
        "POST",
        "/system/resource-shares",
        admin_token,
        LONG_PROJECT_ID,
        json_body=share_body,
        timeout=45,
    )
    share = response_data(share_response) if is_ok(share_response) else None
    share_id = str(share.get("id")) if isinstance(share, dict) and share.get("id") else None
    evidence["shareId"] = share_id
    add_bool_case(
        results,
        "S21-SHARE-001",
        "资源共享",
        "源项目采集任务可共享给接收项目",
        share_id is not None,
        {"share": summarize(share_response)},
    )
    if share_id is None:
        print(json.dumps(output(batch, results, evidence), ensure_ascii=False, indent=2, default=str))
        return 1

    receiver_task_detail = client.request("GET", f"/collection-tasks/{task_id}", receiver_token, RECEIVER_PROJECT_ID)
    add_case(
        results,
        "S21-SHARE-002",
        "资源共享",
        "接收项目成员可读取启用共享采集任务",
        expect_ok,
        receiver_task_detail,
    )

    follow_response = client.request(
        "POST",
        "/follows",
        receiver_token,
        RECEIVER_PROJECT_ID,
        json_body={"targetType": "COLLECTION_TASK", "targetId": int(task_id)},
        timeout=30,
    )
    add_case(
        results,
        "S21-FOLLOW-001",
        "关注",
        "接收项目成员可关注启用共享采集任务",
        lambda response: is_ok(response) and (response_data(response) or {}).get("following") is True,
        follow_response,
    )

    trigger_response, active_run = trigger_and_wait(client, admin_token, task_id)
    run_id = str(active_run.get("id")) if isinstance(active_run, dict) and active_run.get("id") else None
    rows = target_rows(target_table) if run_id else []
    add_bool_case(
        results,
        "S21-RUN-001",
        "采集运行",
        "启用共享期间源项目触发采集任务并产生终态运行记录，目标表写入 2 行且运行指标一致",
        run_id is not None
        and active_run is not None
        and active_run.get("status") == "SUCCESS"
        and len(rows) == 2
        and int(active_run.get("collected_records") or 0) == 2
        and int(active_run.get("write_succeed_records") or 0) == 2
        and int(active_run.get("write_failed_records") or 0) == 0
        and int(active_run.get("failed_records") or 0) == 0,
        {"trigger": summarize(trigger_response), "run": active_run, "targetRows": rows},
    )

    if run_id:
        receiver_notifications = notification_rows(receiver_user_id, run_id)
        add_bool_case(
            results,
            "S21-NOTIFY-001",
            "关注通知",
            "接收项目共享关注者应收到采集任务运行通知",
            len(receiver_notifications) == 1,
            {
                "receiverUserId": receiver_user_id,
                "runRecordId": run_id,
                "notificationCount": len(receiver_notifications),
                "rows": receiver_notifications,
            },
            need_fix=len(receiver_notifications) != 1,
        )
        target_ok = (
            len(receiver_notifications) == 1
            and str(receiver_notifications[0].get("target_project_id")) == str(RECEIVER_PROJECT_ID)
            and receiver_notifications[0].get("target_path") == f"/collection-tasks/{task_id}/edit"
        )
        add_bool_case(
            results,
            "S21-NOTIFY-002",
            "关注通知点击目标",
            "共享关注者通知应指向接收项目可读的共享采集任务编辑页",
            target_ok,
            {
                "receiverProjectId": RECEIVER_PROJECT_ID,
                "expectedTargetPath": f"/collection-tasks/{task_id}/edit",
                "rows": receiver_notifications,
            },
            need_fix=not target_ok,
        )
        receiver_run_detail = client.request("GET", f"/runs/{run_id}", receiver_token, RECEIVER_PROJECT_ID)
        add_case(
            results,
            "S21-NOTIFY-003",
            "运行详情隔离",
            "源项目采集运行详情不向接收项目成员直接开放",
            expect_not_found,
            receiver_run_detail,
            need_fix=False,
        )

    disabled_share_body = dict(share_body)
    disabled_share_body["id"] = int(share_id)
    disabled_share_body["enabled"] = 0
    disable_share_response = client.request(
        "POST",
        "/system/resource-shares",
        admin_token,
        LONG_PROJECT_ID,
        json_body=disabled_share_body,
        timeout=45,
    )
    add_case(results, "S21-REVOKE-001", "资源共享撤销", "禁用采集任务共享成功", expect_ok, disable_share_response)
    receiver_after_disabled = client.request("GET", f"/collection-tasks/{task_id}", receiver_token, RECEIVER_PROJECT_ID)
    add_case(
        results,
        "S21-REVOKE-002",
        "资源共享撤销",
        "共享禁用后接收项目成员不可读取采集任务",
        expect_not_found,
        receiver_after_disabled,
        need_fix=False,
    )

    trigger_after_disable_response, disabled_run = trigger_and_wait(client, admin_token, task_id)
    disabled_run_id = str(disabled_run.get("id")) if isinstance(disabled_run, dict) and disabled_run.get("id") else None
    add_bool_case(
        results,
        "S21-REVOKE-003",
        "共享撤销后运行",
        "共享禁用后源项目仍可触发采集任务并产生重复主键失败运行，失败指标一致",
        disabled_run_id is not None
        and disabled_run is not None
        and disabled_run.get("status") == "FAILED"
        and int(disabled_run.get("collected_records") or 0) == 2
        and int(disabled_run.get("write_succeed_records") or 0) == 0
        and int(disabled_run.get("write_failed_records") or 0) == 2
        and int(disabled_run.get("failed_records") or 0) == 2,
        {"trigger": summarize(trigger_after_disable_response), "run": disabled_run},
    )
    if disabled_run_id:
        disabled_notifications = notification_rows(receiver_user_id, disabled_run_id)
        add_bool_case(
            results,
            "S21-REVOKE-004",
            "共享撤销后关注通知",
            "共享禁用后的旧关注不应收到源项目新的采集运行通知",
            len(disabled_notifications) == 0,
            {
                "receiverUserId": receiver_user_id,
                "runRecordId": disabled_run_id,
                "notificationCount": len(disabled_notifications),
                "rows": disabled_notifications,
            },
            need_fix=len(disabled_notifications) != 0,
        )

    print(json.dumps(output(batch, results, evidence), ensure_ascii=False, indent=2, default=str))
    stats = result_stats(results)
    return 0 if stats["FAIL"] == 0 and stats["needFix"] == 0 else 1


def result_stats(results: list[dict[str, Any]]) -> dict[str, int]:
    return {
        "PASS": sum(1 for item in results if item["result"] == "PASS"),
        "FAIL": sum(1 for item in results if item["result"] == "FAIL"),
        "BLOCKED": sum(1 for item in results if item["result"] == "BLOCKED"),
        "needFix": sum(1 for item in results if item.get("needFix")),
    }


def output(batch: str, results: list[dict[str, Any]], evidence: dict[str, Any]) -> dict[str, Any]:
    return {
        "batch": batch,
        "fixtures": {
            "projectId": LONG_PROJECT_ID,
            "receiverProjectId": RECEIVER_PROJECT_ID,
            "datasourceId": DATASOURCE_ID,
            "tables": evidence.get("tables"),
            "taskId": evidence.get("taskId"),
            "shareId": evidence.get("shareId"),
            "receiverUser": evidence.get("receiverUser"),
        },
        "health": evidence.get("health"),
        "results": results,
        "stats": result_stats(results),
    }


if __name__ == "__main__":
    sys.exit(main())

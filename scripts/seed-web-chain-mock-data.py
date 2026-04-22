from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from decimal import Decimal
from typing import Iterable, Sequence

import pymysql


WEB_CHAIN_TABLES = (
    "web_chain_orders_src",
    "web_chain_customers_src",
    "web_chain_orders_agg",
    "web_chain_orders_report",
    "web_chain_orders_snapshot",
    "web_chain_customer_ref",
)


@dataclass(frozen=True)
class MysqlConfig:
    host: str
    port: int
    user: str
    password: str
    database: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Seed mock_data/mock_data_target test tables for Studio web chain testing.")
    parser.add_argument("--host", default="192.168.188.129")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="951753")
    parser.add_argument("--source-db", default="mock_data")
    parser.add_argument("--target-db", default="mock_data_target")
    return parser.parse_args()


def connect_admin(config: MysqlConfig):
    return pymysql.connect(
        host=config.host,
        port=config.port,
        user=config.user,
        password=config.password,
        charset="utf8mb4",
        autocommit=True,
    )


def connect_database(config: MysqlConfig):
    return pymysql.connect(
        host=config.host,
        port=config.port,
        user=config.user,
        password=config.password,
        database=config.database,
        charset="utf8mb4",
        autocommit=False,
    )


def ensure_database(config: MysqlConfig) -> None:
    connection = connect_admin(config)
    try:
        with connection.cursor() as cursor:
            cursor.execute(f"CREATE DATABASE IF NOT EXISTS `{config.database}` DEFAULT CHARACTER SET utf8mb4")
    finally:
        connection.close()


def drop_tables(connection, tables: Sequence[str]) -> None:
    with connection.cursor() as cursor:
        for table in tables:
            cursor.execute(f"DROP TABLE IF EXISTS `{table}`")
    connection.commit()


def create_source_schema(connection) -> None:
    statements = [
        """
        CREATE TABLE `web_chain_orders_src` (
          `order_id` BIGINT NOT NULL,
          `order_no` VARCHAR(64) NOT NULL,
          `customer_id` BIGINT NOT NULL,
          `customer_name` VARCHAR(128) NOT NULL,
          `amount` DECIMAL(12,2) NULL,
          `status` VARCHAR(32) NOT NULL,
          `created_at` DATETIME NOT NULL,
          `updated_at` DATETIME NOT NULL,
          `event_date` DATE NOT NULL,
          `source_flag` TINYINT NOT NULL DEFAULT 1,
          PRIMARY KEY (`order_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE `web_chain_customers_src` (
          `customer_id` BIGINT NOT NULL,
          `customer_name` VARCHAR(128) NOT NULL,
          `customer_level` VARCHAR(32) NOT NULL,
          `city_code` VARCHAR(32) NOT NULL,
          `created_at` DATETIME NOT NULL,
          PRIMARY KEY (`customer_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
    ]
    with connection.cursor() as cursor:
        for statement in statements:
            cursor.execute(statement)
    connection.commit()


def create_target_schema(connection) -> None:
    statements = [
        """
        CREATE TABLE `web_chain_orders_agg` (
          `order_id` BIGINT NOT NULL,
          `order_no` VARCHAR(64) NOT NULL,
          `customer_id` BIGINT NOT NULL,
          `customer_name` VARCHAR(128) NOT NULL,
          `amount` DECIMAL(12,2) NULL,
          `status` VARCHAR(32) NOT NULL,
          `created_at` DATETIME NOT NULL,
          `updated_at` DATETIME NOT NULL,
          `event_date` DATE NOT NULL,
          `source_flag` TINYINT NOT NULL DEFAULT 1,
          PRIMARY KEY (`order_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE `web_chain_orders_report` (
          `report_id` BIGINT NOT NULL AUTO_INCREMENT,
          `order_id` BIGINT NOT NULL,
          `order_no` VARCHAR(64) NOT NULL,
          `customer_name` VARCHAR(128) NOT NULL,
          `amount` DECIMAL(12,2) NULL,
          `status` VARCHAR(32) NOT NULL,
          `amount_bucket` VARCHAR(32) NOT NULL,
          `report_tag` VARCHAR(64) NOT NULL,
          `created_at` DATETIME NOT NULL,
          PRIMARY KEY (`report_id`),
          KEY `idx_web_chain_orders_report_order_id` (`order_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE `web_chain_orders_snapshot` (
          `snapshot_id` BIGINT NOT NULL AUTO_INCREMENT,
          `order_id` BIGINT NOT NULL,
          `order_no` VARCHAR(64) NOT NULL,
          `amount` DECIMAL(12,2) NULL,
          `status` VARCHAR(32) NOT NULL,
          `snapshot_time` DATETIME NOT NULL,
          PRIMARY KEY (`snapshot_id`),
          KEY `idx_web_chain_orders_snapshot_order_id` (`order_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        """
        CREATE TABLE `web_chain_customer_ref` (
          `customer_id` BIGINT NOT NULL,
          `expected_customer_name` VARCHAR(128) NOT NULL,
          `valid_statuses` VARCHAR(128) NOT NULL,
          PRIMARY KEY (`customer_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
    ]
    with connection.cursor() as cursor:
        for statement in statements:
            cursor.execute(statement)
    connection.commit()


def insert_many(connection, sql: str, rows: Iterable[Sequence[object]]) -> None:
    with connection.cursor() as cursor:
        cursor.executemany(sql, list(rows))
    connection.commit()


def seed_source_data(connection) -> None:
    customer_rows = [
        (101, "Alice Chen", "A", "SH", "2026-04-01 08:00:00"),
        (102, "Bob Li", "A", "HZ", "2026-04-01 08:05:00"),
        (103, "Charlie Xu", "B", "NJ", "2026-04-01 08:10:00"),
        (104, "Daisy Guo", "B", "SZ", "2026-04-01 08:15:00"),
        (105, "Eric Hu", "C", "CD", "2026-04-01 08:20:00"),
        (106, "Fiona Sun", "C", "WH", "2026-04-01 08:25:00"),
    ]
    order_rows = [
        (1001, "ORD-202604-001", 101, "Alice Chen", Decimal("120.50"), "PAID", "2026-04-19 09:00:00", "2026-04-21 09:05:00", "2026-04-19", 1),
        (1002, "ORD-202604-002", 102, "Bob Li", Decimal("80.00"), "PAID", "2026-04-19 10:00:00", "2026-04-21 09:06:00", "2026-04-19", 1),
        (1003, "ORD-202604-DUP", 103, "Charlie Xu", Decimal("99.90"), "PAID", "2026-04-19 11:00:00", "2026-04-21 09:07:00", "2026-04-19", 1),
        (1004, "ORD-202604-DUP", 104, "Daisy Guo", Decimal("199.00"), "PAID", "2026-04-19 12:00:00", "2026-04-21 09:08:00", "2026-04-19", 1),
        (1005, "ORD-202604-005", 105, "Eric Hu", None, "PAID", "2026-04-19 13:00:00", "2026-04-21 09:09:00", "2026-04-19", 1),
        (1006, "ORD-202604-006", 106, "Wrong Fiona", Decimal("66.60"), "UNKNOWN", "2026-04-19 14:00:00", "2024-01-01 09:00:00", "2026-04-19", 1),
    ]
    insert_many(
        connection,
        """
        INSERT INTO `web_chain_customers_src`
        (`customer_id`, `customer_name`, `customer_level`, `city_code`, `created_at`)
        VALUES (%s, %s, %s, %s, %s)
        """,
        customer_rows,
    )
    insert_many(
        connection,
        """
        INSERT INTO `web_chain_orders_src`
        (`order_id`, `order_no`, `customer_id`, `customer_name`, `amount`, `status`, `created_at`, `updated_at`, `event_date`, `source_flag`)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        order_rows,
    )


def seed_target_reference_data(connection) -> None:
    reference_rows = [
        (101, "Alice Chen", "PAID,SETTLED"),
        (102, "Bob Li", "PAID,SETTLED"),
        (103, "Charlie Xu", "PAID,SETTLED"),
        (104, "Daisy Guo", "PAID,SETTLED"),
        (105, "Eric Hu", "PAID,SETTLED"),
        (106, "Fiona Sun", "PAID,SETTLED"),
    ]
    snapshot_rows = [
        (1001, "ORD-202604-001", Decimal("120.50"), "PAID", "2026-04-21 08:50:00"),
        (1002, "ORD-202604-002", Decimal("81.00"), "PAID", "2026-04-21 08:51:00"),
        (1003, "ORD-202604-DUP", Decimal("99.90"), "PAID", "2026-04-21 08:52:00"),
        (1005, "ORD-202604-005", Decimal("30.00"), "PAID", "2026-04-21 08:53:00"),
    ]
    insert_many(
        connection,
        """
        INSERT INTO `web_chain_customer_ref`
        (`customer_id`, `expected_customer_name`, `valid_statuses`)
        VALUES (%s, %s, %s)
        """,
        reference_rows,
    )
    insert_many(
        connection,
        """
        INSERT INTO `web_chain_orders_snapshot`
        (`order_id`, `order_no`, `amount`, `status`, `snapshot_time`)
        VALUES (%s, %s, %s, %s, %s)
        """,
        snapshot_rows,
    )


def count_rows(connection, table: str) -> int:
    with connection.cursor() as cursor:
        cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
        value = cursor.fetchone()[0]
    return int(value or 0)


def build_summary(source_config: MysqlConfig, target_config: MysqlConfig) -> dict:
    source_connection = connect_database(source_config)
    target_connection = connect_database(target_config)
    try:
        return {
            "source": {
                "database": source_config.database,
                "tables": {
                    "web_chain_orders_src": count_rows(source_connection, "web_chain_orders_src"),
                    "web_chain_customers_src": count_rows(source_connection, "web_chain_customers_src"),
                },
            },
            "target": {
                "database": target_config.database,
                "tables": {
                    "web_chain_orders_agg": count_rows(target_connection, "web_chain_orders_agg"),
                    "web_chain_orders_report": count_rows(target_connection, "web_chain_orders_report"),
                    "web_chain_orders_snapshot": count_rows(target_connection, "web_chain_orders_snapshot"),
                    "web_chain_customer_ref": count_rows(target_connection, "web_chain_customer_ref"),
                },
            },
        }
    finally:
        source_connection.close()
        target_connection.close()


def seed(source_config: MysqlConfig, target_config: MysqlConfig) -> dict:
    ensure_database(source_config)
    ensure_database(target_config)

    source_connection = connect_database(source_config)
    target_connection = connect_database(target_config)
    try:
        drop_tables(source_connection, ("web_chain_orders_src", "web_chain_customers_src"))
        drop_tables(target_connection, ("web_chain_orders_agg", "web_chain_orders_report", "web_chain_orders_snapshot", "web_chain_customer_ref"))
        create_source_schema(source_connection)
        create_target_schema(target_connection)
        seed_source_data(source_connection)
        seed_target_reference_data(target_connection)
    finally:
        source_connection.close()
        target_connection.close()
    return build_summary(source_config, target_config)


def main() -> int:
    args = parse_args()
    source_config = MysqlConfig(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=args.source_db,
    )
    target_config = MysqlConfig(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=args.target_db,
    )
    summary = seed(source_config, target_config)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

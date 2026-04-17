CREATE TABLE `worker_lease` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `worker_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `worker_kind` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `host_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `last_heartbeat_at` datetime DEFAULT NULL,
  `capabilities_json` json DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
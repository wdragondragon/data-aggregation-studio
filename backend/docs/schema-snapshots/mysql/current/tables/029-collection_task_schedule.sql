CREATE TABLE `collection_task_schedule` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `collection_task_id` bigint DEFAULT NULL,
  `cron_expression` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `enabled` int DEFAULT '0',
  `timezone` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `last_triggered_at` datetime DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_collection_task_schedule_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
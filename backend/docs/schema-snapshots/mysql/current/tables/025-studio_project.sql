CREATE TABLE `studio_project` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `project_code` varchar(128) COLLATE utf8mb4_general_ci NOT NULL,
  `project_name` varchar(255) COLLATE utf8mb4_general_ci NOT NULL,
  `description` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `enabled` int DEFAULT '1',
  `default_project` int DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_studio_project_code` (`tenant_id`,`project_code`),
  UNIQUE KEY `uk_studio_project_name` (`tenant_id`,`project_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
CREATE TABLE `workflow_edge` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `workflow_version_id` bigint DEFAULT NULL,
  `from_node_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `to_node_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `condition_type` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
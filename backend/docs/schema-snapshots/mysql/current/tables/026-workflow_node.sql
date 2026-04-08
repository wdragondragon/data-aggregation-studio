CREATE TABLE `workflow_node` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `workflow_version_id` bigint DEFAULT NULL,
  `node_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `node_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `node_type` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `config_json` json DEFAULT NULL,
  `field_mappings_json` json DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_workflow_node_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
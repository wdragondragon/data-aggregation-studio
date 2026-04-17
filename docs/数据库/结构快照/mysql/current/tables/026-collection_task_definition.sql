CREATE TABLE `collection_task_definition` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `task_type` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `status` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `source_count` int DEFAULT '1',
  `source_bindings_json` json DEFAULT NULL,
  `target_binding_json` json DEFAULT NULL,
  `field_mappings_json` json DEFAULT NULL,
  `execution_options_json` json DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_collection_task_definition_project_name` (`project_id`,`name`),
  KEY `idx_collection_task_definition_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
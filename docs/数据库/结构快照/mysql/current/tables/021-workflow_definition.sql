CREATE TABLE `workflow_definition` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `current_version_id` bigint DEFAULT NULL,
  `published` int DEFAULT '0',
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workflow_definition_project_code` (`project_id`,`code`),
  UNIQUE KEY `uk_workflow_definition_project_name` (`project_id`,`name`),
  KEY `idx_workflow_definition_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
CREATE TABLE `data_model` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `datasource_id` bigint DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `model_kind` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `physical_locator` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `schema_version_id` bigint DEFAULT NULL,
  `technical_metadata` json DEFAULT NULL,
  `business_metadata` json DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_data_model_project_datasource_name` (`project_id`,`datasource_id`,`name`),
  KEY `idx_data_model_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci

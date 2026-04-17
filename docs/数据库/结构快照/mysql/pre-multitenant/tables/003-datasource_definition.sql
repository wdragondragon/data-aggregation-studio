CREATE TABLE `datasource_definition` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `type_code` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `schema_version_id` bigint DEFAULT NULL,
  `enabled` int DEFAULT '1',
  `executable` int DEFAULT '0',
  `technical_metadata` json DEFAULT NULL,
  `business_metadata` json DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
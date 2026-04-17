CREATE TABLE `meta_schema` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `schema_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `schema_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `object_type` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `type_code` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `current_version_id` bigint DEFAULT NULL,
  `status` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `description` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
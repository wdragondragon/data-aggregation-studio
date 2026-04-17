CREATE TABLE `catalog_plugin` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `plugin_name` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `plugin_category` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `asset_type` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `asset_path` varchar(1024) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `executable` int DEFAULT '0',
  `metadata` json DEFAULT NULL,
  `template` json DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
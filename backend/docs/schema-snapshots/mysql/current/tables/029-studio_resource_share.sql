CREATE TABLE `studio_resource_share` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `source_project_id` bigint NOT NULL,
  `target_project_id` bigint NOT NULL,
  `resource_type` varchar(128) COLLATE utf8mb4_general_ci NOT NULL,
  `resource_id` bigint NOT NULL,
  `shared_by_user_id` bigint DEFAULT NULL,
  `enabled` int DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_studio_resource_share_target` (`resource_type`,`resource_id`,`target_project_id`),
  KEY `idx_studio_resource_share_project` (`target_project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
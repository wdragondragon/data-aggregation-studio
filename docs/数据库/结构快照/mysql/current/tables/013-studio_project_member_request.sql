CREATE TABLE `studio_project_member_request` (
  `id` bigint NOT NULL,
  `tenant_id` varchar(64) COLLATE utf8mb4_general_ci DEFAULT 'default',
  `deleted` int DEFAULT '0',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `project_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `request_type` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `status` varchar(64) COLLATE utf8mb4_general_ci NOT NULL,
  `inviter_user_id` bigint DEFAULT NULL,
  `reviewer_user_id` bigint DEFAULT NULL,
  `reason` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `review_comment` varchar(1000) COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_studio_project_member_request_lookup` (`project_id`,`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
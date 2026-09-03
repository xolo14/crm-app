-- Syncpedia CRM — mobile call sync + location tracking
-- Run once in phpMyAdmin after importing database.sql
-- If a column/index already exists, skip that statement and continue.

ALTER TABLE `call_logs`
  ADD COLUMN `device_call_id` VARCHAR(64) DEFAULT NULL AFTER `call_time`;

ALTER TABLE `call_logs`
  ADD COLUMN `latitude` DECIMAL(10,7) DEFAULT NULL AFTER `device_call_id`;

ALTER TABLE `call_logs`
  ADD COLUMN `longitude` DECIMAL(10,7) DEFAULT NULL AFTER `latitude`;

ALTER TABLE `call_logs`
  ADD COLUMN `synced_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP AFTER `longitude`;

ALTER TABLE `call_logs`
  ADD UNIQUE KEY `uq_calllog_rep_device` (`sales_rep_id`, `device_call_id`);

CREATE TABLE IF NOT EXISTS `user_locations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` CHAR(36) NOT NULL,
  `org_id` CHAR(36) NOT NULL,
  `latitude` DECIMAL(10,7) NOT NULL,
  `longitude` DECIMAL(10,7) NOT NULL,
  `recorded_at` DATETIME NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_user_locations_user` (`user_id`),
  INDEX `idx_user_locations_org` (`org_id`),
  INDEX `idx_user_locations_recorded` (`recorded_at`),
  CONSTRAINT `fk_user_locations_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_locations_org` FOREIGN KEY (`org_id`) REFERENCES `organizations`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

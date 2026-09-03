<?php
/**
 * Copy to config.php on Hostinger and fill in values from hPanel → Databases.
 * NEVER commit config.php or put secrets in git / API responses.
 */

define('DB_HOST', 'localhost');
define('DB_PORT', '3306');
define('DB_NAME', 'u123456789_syncpedia');
define('DB_USER', 'u123456789_crmuser');
define('DB_PASS', 'your_mysql_password');
define('DB_CHARSET', 'utf8mb4');

// Random string ≥ 32 chars — generate: php -r "echo bin2hex(random_bytes(32));"
define('JWT_SECRET', 'change-this-to-a-random-secret-key-at-least-32-chars');
define('TOKEN_EXPIRY_DAYS', 7);

// CORS — website origin only (Android apps do not require CORS)
define('CORS_ORIGIN', 'https://crm.syncpedia.in');

define('APP_DEBUG', false);

// Failed login throttle
define('LOGIN_MAX_ATTEMPTS', 8);
define('LOGIN_LOCKOUT_SECONDS', 900); // 15 minutes

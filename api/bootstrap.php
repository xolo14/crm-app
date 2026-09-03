<?php
/**
 * Shared bootstrap — JSON errors, config, PDO, Composer autoload.
 */

declare(strict_types=1);

if (!function_exists('api_json_die')) {
    function api_json_die(array $data, int $status = 500): void
    {
        while (ob_get_level() > 0) {
            @ob_end_clean();
        }
        if (!headers_sent()) {
            http_response_code($status);
            header('Content-Type: application/json; charset=UTF-8');
        }
        echo json_encode($data, JSON_UNESCAPED_UNICODE);
        exit;
    }
}

register_shutdown_function(static function (): void {
    if (defined('API_DONE')) {
        return;
    }
    $err = error_get_last();
    if (!$err || !in_array($err['type'], [E_ERROR, E_PARSE, E_COMPILE_ERROR, E_CORE_ERROR], true)) {
        return;
    }
    api_json_die([
        'error' => 'server_error',
        'message' => 'PHP fatal error — check Hostinger error logs.',
    ], 500);
});

$configPath = __DIR__ . '/config.php';
if (!is_file($configPath)) {
    api_json_die([
        'error' => 'not_configured',
        'message' => 'Copy api/config.example.php to api/config.php and set DB + JWT_SECRET.',
    ], 503);
}

require_once $configPath;

$autoload = __DIR__ . '/vendor/autoload.php';
if (!is_file($autoload)) {
    api_json_die([
        'error' => 'not_configured',
        'message' => 'Run composer install in the api/ folder (needs firebase/php-jwt).',
    ], 503);
}
require_once $autoload;

foreach (['DB_HOST', 'DB_NAME', 'DB_USER', 'DB_PASS', 'JWT_SECRET'] as $c) {
    if (!defined($c)) {
        api_json_die(['error' => 'not_configured', 'message' => "Missing define('$c') in config.php"], 503);
    }
}

if (strlen((string) JWT_SECRET) < 32) {
    api_json_die(['error' => 'not_configured', 'message' => 'JWT_SECRET must be at least 32 characters'], 503);
}

if (!defined('TOKEN_EXPIRY_DAYS')) {
    define('TOKEN_EXPIRY_DAYS', 7);
}
if (!defined('CORS_ORIGIN')) {
    define('CORS_ORIGIN', 'https://crm.syncpedia.in');
}
if (!defined('APP_DEBUG')) {
    define('APP_DEBUG', false);
}
if (!defined('LOGIN_MAX_ATTEMPTS')) {
    define('LOGIN_MAX_ATTEMPTS', 8);
}
if (!defined('LOGIN_LOCKOUT_SECONDS')) {
    define('LOGIN_LOCKOUT_SECONDS', 900);
}
if (!defined('DB_PORT')) {
    define('DB_PORT', '3306');
}
if (!defined('DB_CHARSET')) {
    define('DB_CHARSET', 'utf8mb4');
}

date_default_timezone_set('Asia/Kolkata');

require_once __DIR__ . '/db.php';
require_once __DIR__ . '/helpers.php';

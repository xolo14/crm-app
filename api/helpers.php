<?php
declare(strict_types=1);

use Firebase\JWT\JWT;
use Firebase\JWT\Key;

function cors(): void
{
    if (ob_get_level() === 0) {
        ob_start();
    }

    $allowed = rtrim((string) CORS_ORIGIN, '/');
    $origin = $_SERVER['HTTP_ORIGIN'] ?? '';

    if ($origin !== '' && strcasecmp(rtrim($origin, '/'), $allowed) === 0) {
        header('Access-Control-Allow-Origin: ' . $allowed);
        header('Vary: Origin');
    } elseif ($origin === '') {
        // Non-browser clients (Android) — no CORS preflight needed
        header('Access-Control-Allow-Origin: ' . $allowed);
    } else {
        header('Access-Control-Allow-Origin: ' . $allowed);
        header('Vary: Origin');
    }

    header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
    header('Access-Control-Allow-Headers: Content-Type, Authorization');
    header('Access-Control-Max-Age: 86400');
    header('Content-Type: application/json; charset=UTF-8');
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: no-store');

    if (($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') {
        respond(['ok' => true], 204);
    }
}

function respond(mixed $data, int $status = 200): never
{
    if (!defined('API_DONE')) {
        define('API_DONE', true);
    }
    while (ob_get_level() > 0) {
        @ob_end_clean();
    }
    http_response_code($status);
    header('Content-Type: application/json; charset=UTF-8');

    if ($status === 204) {
        exit;
    }

    if (is_array($data) && !(defined('APP_DEBUG') && APP_DEBUG)) {
        unset($data['detail'], $data['trace'], $data['exception'], $data['file'], $data['line']);
    }

    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

function getJsonInput(): array
{
    $raw = file_get_contents('php://input') ?: '';
    if ($raw === '') {
        return [];
    }
    $decoded = json_decode($raw, true);
    return is_array($decoded) ? $decoded : [];
}

function clientIp(): string
{
    $ip = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    return preg_replace('/[^0-9a-fA-F:.]/', '', $ip) ?: '0.0.0.0';
}

function newUuid(): string
{
    $data = random_bytes(16);
    $data[6] = chr((ord($data[6]) & 0x0f) | 0x40);
    $data[8] = chr((ord($data[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($data), 4));
}

function isAdminOrManager(string $role): bool
{
    $r = strtolower(trim($role));
    return in_array($r, ['admin', 'manager', 'super_admin'], true);
}

function issueJwt(string $userId, string $orgId, string $role): string
{
    $now = time();
    $payload = [
        'user_id' => $userId,
        'org_id' => $orgId,
        'role' => $role,
        'iat' => $now,
        'exp' => $now + ((int) TOKEN_EXPIRY_DAYS * 86400),
    ];
    return JWT::encode($payload, (string) JWT_SECRET, 'HS256');
}

function extractBearerToken(): string
{
    $header = '';
    if (!empty($_SERVER['HTTP_AUTHORIZATION'])) {
        $header = (string) $_SERVER['HTTP_AUTHORIZATION'];
    } elseif (!empty($_SERVER['REDIRECT_HTTP_AUTHORIZATION'])) {
        $header = (string) $_SERVER['REDIRECT_HTTP_AUTHORIZATION'];
    } elseif (function_exists('getallheaders')) {
        $headers = getallheaders();
        foreach ($headers ?: [] as $k => $v) {
            if (strcasecmp((string) $k, 'Authorization') === 0) {
                $header = (string) $v;
                break;
            }
        }
    }

    if (preg_match('/^\s*Bearer\s+(\S+)\s*$/i', $header, $m)) {
        return $m[1];
    }
    return '';
}

/**
 * Rate-limit failed logins by email + IP (file-based, Hostinger-safe).
 */
function loginIsBlocked(string $email, string $ip): bool
{
    $path = loginRateLimitPath($email, $ip);
    if (!is_file($path)) {
        return false;
    }
    $data = json_decode((string) file_get_contents($path), true);
    if (!is_array($data)) {
        return false;
    }
    $fails = (int) ($data['fails'] ?? 0);
    $until = (int) ($data['until'] ?? 0);
    if ($fails >= (int) LOGIN_MAX_ATTEMPTS && $until > time()) {
        return true;
    }
    if ($until > 0 && $until <= time()) {
        @unlink($path);
        return false;
    }
    return false;
}

function loginRecordFailure(string $email, string $ip): void
{
    $dir = __DIR__ . '/storage/rate_limits';
    if (!is_dir($dir)) {
        @mkdir($dir, 0750, true);
    }
    $path = loginRateLimitPath($email, $ip);
    $data = ['fails' => 0, 'until' => 0];
    if (is_file($path)) {
        $existing = json_decode((string) file_get_contents($path), true);
        if (is_array($existing)) {
            $data = $existing;
        }
    }
    $data['fails'] = (int) ($data['fails'] ?? 0) + 1;
    $data['last'] = date('c');
    $data['ip'] = $ip;
    $data['email'] = strtolower($email);
    if ($data['fails'] >= (int) LOGIN_MAX_ATTEMPTS) {
        $data['until'] = time() + (int) LOGIN_LOCKOUT_SECONDS;
    }
    file_put_contents($path, json_encode($data), LOCK_EX);

    $logDir = __DIR__ . '/storage/logs';
    if (!is_dir($logDir)) {
        @mkdir($logDir, 0750, true);
    }
    $line = sprintf(
        "%s\tFAIL\tip=%s\temail=%s\tfails=%d\n",
        date('c'),
        $ip,
        strtolower($email),
        $data['fails']
    );
    @file_put_contents($logDir . '/login_failures.log', $line, FILE_APPEND | LOCK_EX);
}

function loginClearFailures(string $email, string $ip): void
{
    $path = loginRateLimitPath($email, $ip);
    if (is_file($path)) {
        @unlink($path);
    }
}

function loginRateLimitPath(string $email, string $ip): string
{
    $key = hash('sha256', strtolower(trim($email)) . '|' . $ip);
    return __DIR__ . '/storage/rate_limits/' . $key . '.json';
}

function mapCallStatus(string $callType, int $duration): string
{
    if ($duration > 0) {
        return 'connected';
    }
    return match ($callType) {
        'outgoing' => 'not_pickup_by_client',
        'missed', 'rejected' => 'never_attended',
        default => 'never_attended',
    };
}

function normalizeCallType(string $raw): ?string
{
    $t = strtolower(trim($raw));
    $map = [
        'incoming' => 'incoming',
        'outgoing' => 'outgoing',
        'missed' => 'missed',
        'rejected' => 'rejected',
        '1' => 'incoming',
        '2' => 'outgoing',
        '3' => 'missed',
        '5' => 'rejected',
    ];
    return $map[$t] ?? null;
}

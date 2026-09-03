<?php
/**
 * Auth middleware — include from protected routes.
 * On success sets: $auth_user_id, $auth_org_id, $auth_role
 */

declare(strict_types=1);

use Firebase\JWT\JWT;
use Firebase\JWT\Key;

require_once __DIR__ . '/bootstrap.php';
cors();

$auth_user_id = '';
$auth_org_id = '';
$auth_role = '';

$token = extractBearerToken();
if ($token === '') {
    respond(['error' => 'unauthorized'], 401);
}

try {
    $decoded = JWT::decode($token, new Key((string) JWT_SECRET, 'HS256'));
    $payload = (array) $decoded;

    $auth_user_id = (string) ($payload['user_id'] ?? '');
    $auth_org_id = (string) ($payload['org_id'] ?? '');
    $auth_role = (string) ($payload['role'] ?? '');

    if ($auth_user_id === '' || $auth_org_id === '') {
        respond(['error' => 'unauthorized'], 401);
    }
} catch (Throwable $e) {
    respond(['error' => 'unauthorized'], 401);
}

// Confirm user still active and belongs to org from JWT
try {
    $pdo = Database::pdo();
    $stmt = $pdo->prepare(
        'SELECT id, org_id, role, is_active FROM users WHERE id = ? LIMIT 1'
    );
    $stmt->execute([$auth_user_id]);
    $row = $stmt->fetch();
    if (!$row || !(int) ($row['is_active'] ?? 0)) {
        respond(['error' => 'unauthorized'], 401);
    }
    if ((string) ($row['org_id'] ?? '') !== $auth_org_id) {
        respond(['error' => 'unauthorized'], 401);
    }
    $auth_role = (string) ($row['role'] ?? $auth_role);
} catch (Throwable $e) {
    respond(['error' => 'unauthorized'], 401);
}

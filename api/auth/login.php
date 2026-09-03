<?php
declare(strict_types=1);

require_once dirname(__DIR__) . '/bootstrap.php';
cors();

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    respond(['error' => 'method_not_allowed'], 405);
}

$input = getJsonInput();
$email = strtolower(trim((string) ($input['email'] ?? '')));
$password = (string) ($input['password'] ?? '');
$ip = clientIp();

if ($email === '' || $password === '') {
    respond(['error' => 'email and password are required'], 422);
}

if (loginIsBlocked($email, $ip)) {
    respond(['error' => 'too_many_attempts', 'message' => 'Too many failed logins. Try again later.'], 429);
}

try {
    $pdo = Database::pdo();
    $stmt = $pdo->prepare(
        'SELECT id, email, password_hash, full_name, role, org_id, is_active
         FROM users
         WHERE email = ?
         LIMIT 1'
    );
    $stmt->execute([$email]);
    $user = $stmt->fetch();

    if (!$user || !password_verify($password, (string) ($user['password_hash'] ?? ''))) {
        loginRecordFailure($email, $ip);
        respond(['error' => 'invalid_credentials'], 401);
    }

    // Never leave hash in memory dumps of response path
    unset($user['password_hash']);

    if (!(int) ($user['is_active'] ?? 0)) {
        respond(['error' => 'account_inactive'], 403);
    }

    $orgId = (string) ($user['org_id'] ?? '');
    if ($orgId === '') {
        respond(['error' => 'no_organization', 'message' => 'User is not assigned to an organization'], 403);
    }

    loginClearFailures($email, $ip);

    $token = issueJwt(
        (string) $user['id'],
        $orgId,
        (string) $user['role']
    );

    respond([
        'token' => $token,
        'user' => [
            'id' => (string) $user['id'],
            'name' => (string) ($user['full_name'] ?? ''),
            'email' => (string) $user['email'],
            'role' => (string) $user['role'],
        ],
    ]);
} catch (Throwable $e) {
    respond([
        'error' => 'server_error',
        'message' => 'Login failed',
        'detail' => APP_DEBUG ? $e->getMessage() : null,
    ], 500);
}

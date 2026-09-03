<?php
declare(strict_types=1);

require_once dirname(__DIR__) . '/auth_check.php';

$pdo = Database::pdo();
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET') {
    try {
        if (isAdminOrManager($auth_role)) {
            $stmt = $pdo->prepare(
                'SELECT id, name, phone, status, email, source
                 FROM leads
                 WHERE org_id = ?
                 ORDER BY updated_at DESC, created_at DESC'
            );
            $stmt->execute([$auth_org_id]);
        } else {
            $stmt = $pdo->prepare(
                'SELECT id, name, phone, status, email, source
                 FROM leads
                 WHERE org_id = ? AND assigned_to = ?
                 ORDER BY updated_at DESC, created_at DESC'
            );
            $stmt->execute([$auth_org_id, $auth_user_id]);
        }

        $rows = $stmt->fetchAll() ?: [];
        $out = array_map(static function (array $r): array {
            return [
                'id' => (string) $r['id'],
                'name' => (string) ($r['name'] ?? ''),
                'phone' => (string) ($r['phone'] ?? ''),
                'status' => (string) ($r['status'] ?? 'new'),
                'email' => $r['email'] !== null ? (string) $r['email'] : null,
                'source' => $r['source'] !== null ? (string) $r['source'] : null,
            ];
        }, $rows);

        respond($out);
    } catch (Throwable $e) {
        respond([
            'error' => 'server_error',
            'detail' => APP_DEBUG ? $e->getMessage() : null,
        ], 500);
    }
}

if ($method === 'POST') {
    $input = getJsonInput();
    $name = trim((string) ($input['name'] ?? ''));
    $phone = trim((string) ($input['phone'] ?? ''));
    $status = trim((string) ($input['status'] ?? 'new'));
    if ($status === '') {
        $status = 'new';
    }

    if ($name === '' || $phone === '') {
        respond(['error' => 'name and phone are required'], 422);
    }

    try {
        $id = newUuid();
        $stmt = $pdo->prepare(
            'INSERT INTO leads (id, name, phone, status, source, assigned_to, org_id, created_by)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
        );
        $stmt->execute([
            $id,
            $name,
            $phone,
            $status,
            'other',
            $auth_user_id,
            $auth_org_id,
            $auth_user_id,
        ]);

        $sel = $pdo->prepare(
            'SELECT id, name, phone, status, email, source FROM leads WHERE id = ? AND org_id = ? LIMIT 1'
        );
        $sel->execute([$id, $auth_org_id]);
        $row = $sel->fetch();
        if (!$row) {
            respond(['error' => 'create_failed'], 500);
        }

        respond([
            'id' => (string) $row['id'],
            'name' => (string) $row['name'],
            'phone' => (string) ($row['phone'] ?? ''),
            'status' => (string) $row['status'],
            'email' => $row['email'] !== null ? (string) $row['email'] : null,
            'source' => $row['source'] !== null ? (string) $row['source'] : null,
        ], 201);
    } catch (Throwable $e) {
        respond([
            'error' => 'server_error',
            'detail' => APP_DEBUG ? $e->getMessage() : null,
        ], 500);
    }
}

respond(['error' => 'method_not_allowed'], 405);

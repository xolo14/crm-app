<?php
declare(strict_types=1);

require_once dirname(__DIR__) . '/auth_check.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    respond(['error' => 'method_not_allowed'], 405);
}

$pdo = Database::pdo();
$page = max(1, (int) ($_GET['page'] ?? 1));
$limit = min(100, max(1, (int) ($_GET['limit'] ?? 50)));
$offset = ($page - 1) * $limit;

try {
    if (isAdminOrManager($auth_role)) {
        $countStmt = $pdo->prepare('SELECT COUNT(*) FROM call_logs WHERE org_id = ?');
        $countStmt->execute([$auth_org_id]);
        $total = (int) $countStmt->fetchColumn();

        $stmt = $pdo->prepare(
            'SELECT id, sales_rep_id, org_id, lead_id, call_type, call_status,
                    duration_seconds, client_phone, client_name, call_date, call_time,
                    device_call_id, latitude, longitude, synced_at, attachment_path, created_at
             FROM call_logs
             WHERE org_id = ?
             ORDER BY call_date DESC, call_time DESC, id DESC
             LIMIT ? OFFSET ?'
        );
        $stmt->bindValue(1, $auth_org_id);
        $stmt->bindValue(2, $limit, PDO::PARAM_INT);
        $stmt->bindValue(3, $offset, PDO::PARAM_INT);
        $stmt->execute();
    } else {
        $countStmt = $pdo->prepare(
            'SELECT COUNT(*) FROM call_logs WHERE org_id = ? AND sales_rep_id = ?'
        );
        $countStmt->execute([$auth_org_id, $auth_user_id]);
        $total = (int) $countStmt->fetchColumn();

        $stmt = $pdo->prepare(
            'SELECT id, sales_rep_id, org_id, lead_id, call_type, call_status,
                    duration_seconds, client_phone, client_name, call_date, call_time,
                    device_call_id, latitude, longitude, synced_at, attachment_path, created_at
             FROM call_logs
             WHERE org_id = ? AND sales_rep_id = ?
             ORDER BY call_date DESC, call_time DESC, id DESC
             LIMIT ? OFFSET ?'
        );
        $stmt->bindValue(1, $auth_org_id);
        $stmt->bindValue(2, $auth_user_id);
        $stmt->bindValue(3, $limit, PDO::PARAM_INT);
        $stmt->bindValue(4, $offset, PDO::PARAM_INT);
        $stmt->execute();
    }

    $items = $stmt->fetchAll() ?: [];

    respond([
        'page' => $page,
        'limit' => $limit,
        'total' => $total,
        'items' => $items,
    ]);
} catch (Throwable $e) {
    respond([
        'error' => 'server_error',
        'detail' => APP_DEBUG ? $e->getMessage() : null,
    ], 500);
}

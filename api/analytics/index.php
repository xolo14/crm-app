<?php
declare(strict_types=1);

require_once dirname(__DIR__) . '/auth_check.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    respond(['error' => 'method_not_allowed'], 405);
}

$from = trim((string) ($_GET['from'] ?? date('Y-m-d')));
$to = trim((string) ($_GET['to'] ?? date('Y-m-d')));

if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $from) || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $to)) {
    respond(['error' => 'from and to must be YYYY-MM-DD'], 422);
}

if ($from > $to) {
    [$from, $to] = [$to, $from];
}

$pdo = Database::pdo();

try {
    if (isAdminOrManager($auth_role)) {
        $stmt = $pdo->prepare(
            'SELECT call_type,
                    COUNT(*) AS cnt,
                    COALESCE(SUM(duration_seconds), 0) AS dur
             FROM call_logs
             WHERE org_id = ?
               AND call_date BETWEEN ? AND ?
             GROUP BY call_type'
        );
        $stmt->execute([$auth_org_id, $from, $to]);
    } else {
        $stmt = $pdo->prepare(
            'SELECT call_type,
                    COUNT(*) AS cnt,
                    COALESCE(SUM(duration_seconds), 0) AS dur
             FROM call_logs
             WHERE org_id = ?
               AND sales_rep_id = ?
               AND call_date BETWEEN ? AND ?
             GROUP BY call_type'
        );
        $stmt->execute([$auth_org_id, $auth_user_id, $from, $to]);
    }

    $incoming = 0;
    $outgoing = 0;
    $missed = 0;
    $rejected = 0;
    $incomingDuration = 0;
    $outgoingDuration = 0;
    $totalCalls = 0;
    $totalDuration = 0;

    foreach ($stmt->fetchAll() ?: [] as $row) {
        $type = (string) $row['call_type'];
        $cnt = (int) $row['cnt'];
        $dur = (int) $row['dur'];
        $totalCalls += $cnt;
        $totalDuration += $dur;
        switch ($type) {
            case 'incoming':
                $incoming = $cnt;
                $incomingDuration = $dur;
                break;
            case 'outgoing':
                $outgoing = $cnt;
                $outgoingDuration = $dur;
                break;
            case 'missed':
                $missed = $cnt;
                break;
            case 'rejected':
                $rejected = $cnt;
                break;
        }
    }

    respond([
        'totalCalls' => $totalCalls,
        'totalDuration' => $totalDuration,
        'incoming' => $incoming,
        'outgoing' => $outgoing,
        'missed' => $missed,
        'rejected' => $rejected,
        'incomingDuration' => $incomingDuration,
        'outgoingDuration' => $outgoingDuration,
        'from' => $from,
        'to' => $to,
    ]);
} catch (Throwable $e) {
    respond([
        'error' => 'server_error',
        'detail' => APP_DEBUG ? $e->getMessage() : null,
    ], 500);
}

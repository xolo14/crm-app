<?php
declare(strict_types=1);

require_once __DIR__ . '/auth_check.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    respond(['error' => 'method_not_allowed'], 405);
}

$input = getJsonInput();
if (!isset($input['latitude'], $input['longitude'])) {
    respond(['error' => 'latitude and longitude are required'], 422);
}

$lat = (float) $input['latitude'];
$lng = (float) $input['longitude'];
$timestamp = trim((string) ($input['timestamp'] ?? ''));

if ($lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) {
    respond(['error' => 'invalid_coordinates'], 422);
}

$recordedAt = date('Y-m-d H:i:s');
if ($timestamp !== '') {
    $ts = strtotime($timestamp);
    if ($ts !== false) {
        $recordedAt = date('Y-m-d H:i:s', $ts);
    }
}

try {
    $pdo = Database::pdo();
    $stmt = $pdo->prepare(
        'INSERT INTO user_locations (user_id, org_id, latitude, longitude, recorded_at)
         VALUES (?, ?, ?, ?, ?)'
    );
    $stmt->execute([
        $auth_user_id,
        $auth_org_id,
        $lat,
        $lng,
        $recordedAt,
    ]);

    respond(['success' => true]);
} catch (Throwable $e) {
    respond([
        'error' => 'server_error',
        'message' => 'Could not save location. Ensure migrations/2026_08_21_mobile_call_sync.sql was applied.',
        'detail' => APP_DEBUG ? $e->getMessage() : null,
    ], 500);
}

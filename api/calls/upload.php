<?php
declare(strict_types=1);

require_once dirname(__DIR__) . '/auth_check.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    respond(['error' => 'method_not_allowed'], 405);
}

$pdo = Database::pdo();

// multipart/form-data fields
$phoneNumber = trim((string) ($_POST['phoneNumber'] ?? ''));
$contactName = trim((string) ($_POST['contactName'] ?? ''));
$callTypeRaw = (string) ($_POST['callType'] ?? '');
$duration = (int) ($_POST['duration'] ?? 0);
$calledAt = trim((string) ($_POST['calledAt'] ?? ''));
$latitude = isset($_POST['latitude']) && $_POST['latitude'] !== ''
    ? (float) $_POST['latitude'] : null;
$longitude = isset($_POST['longitude']) && $_POST['longitude'] !== ''
    ? (float) $_POST['longitude'] : null;
$leadId = trim((string) ($_POST['leadId'] ?? ''));
$deviceCallId = trim((string) ($_POST['deviceCallId'] ?? ''));

if ($phoneNumber === '' || $callTypeRaw === '' || $calledAt === '') {
    respond(['error' => 'phoneNumber, callType, and calledAt are required'], 422);
}

$callType = normalizeCallType($callTypeRaw);
if ($callType === null) {
    respond(['error' => 'invalid_call_type'], 422);
}

if ($deviceCallId === '') {
    // Fallback — still allow upload but weaker dedupe
    $deviceCallId = substr(hash('sha256', $auth_user_id . '|' . $phoneNumber . '|' . $calledAt), 0, 32);
}

$ts = strtotime($calledAt);
if ($ts === false) {
    respond(['error' => 'invalid_calledAt'], 422);
}
$callDate = date('Y-m-d', $ts);
$callTime = date('H:i:s', $ts);
$callStatus = mapCallStatus($callType, max(0, $duration));

// Validate optional lead belongs to same org
if ($leadId !== '') {
    $chk = $pdo->prepare('SELECT id FROM leads WHERE id = ? AND org_id = ? LIMIT 1');
    $chk->execute([$leadId, $auth_org_id]);
    if (!$chk->fetch()) {
        $leadId = '';
    }
} else {
    $leadId = '';
}

$attachmentPath = null;

// Optional recording upload
if (!empty($_FILES['recording']) && is_array($_FILES['recording'])
    && (int) ($_FILES['recording']['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_NO_FILE
) {
    $file = $_FILES['recording'];
    if ((int) $file['error'] !== UPLOAD_ERR_OK) {
        respond(['error' => 'recording_upload_failed'], 400);
    }

    $maxBytes = 25 * 1024 * 1024; // 25 MB
    if ((int) $file['size'] > $maxBytes) {
        respond(['error' => 'recording_too_large', 'message' => 'Max 25MB'], 413);
    }

    $finfo = new finfo(FILEINFO_MIME_TYPE);
    $mime = $finfo->file($file['tmp_name']) ?: '';
    $allowedMimes = [
        'audio/mpeg' => 'mp3',
        'audio/mp4' => 'm4a',
        'audio/x-m4a' => 'm4a',
        'audio/aac' => 'aac',
        'audio/wav' => 'wav',
        'audio/x-wav' => 'wav',
        'audio/ogg' => 'ogg',
        'audio/webm' => 'webm',
        'application/octet-stream' => null, // check extension
    ];

    $ext = strtolower(pathinfo((string) $file['name'], PATHINFO_EXTENSION));
    $allowedExt = ['mp3', 'm4a', 'aac', 'wav', 'ogg', 'webm', 'amr', '3gp'];

    if (!isset($allowedMimes[$mime]) && !in_array($ext, $allowedExt, true)) {
        respond(['error' => 'invalid_recording_type'], 415);
    }
    if ($ext === '' || !in_array($ext, $allowedExt, true)) {
        $ext = $allowedMimes[$mime] ?? 'm4a';
    }

    $safeOrg = preg_replace('/[^a-f0-9\-]/i', '', $auth_org_id) ?: 'unknown';
    $dir = dirname(__DIR__) . '/uploads/recordings/' . $safeOrg;
    if (!is_dir($dir) && !mkdir($dir, 0750, true) && !is_dir($dir)) {
        respond(['error' => 'storage_unavailable'], 500);
    }

    $filename = $deviceCallId . '_' . time() . '.' . $ext;
    $filename = preg_replace('/[^a-zA-Z0-9._\-]/', '_', $filename) ?: ('rec_' . time() . '.' . $ext);
    $dest = $dir . '/' . $filename;

    if (!move_uploaded_file($file['tmp_name'], $dest)) {
        respond(['error' => 'recording_save_failed'], 500);
    }

    $attachmentPath = 'uploads/recordings/' . $safeOrg . '/' . $filename;
}

try {
    $sql = 'INSERT INTO call_logs (
                sales_rep_id, org_id, lead_id, call_type, call_status,
                duration_seconds, client_phone, client_name, attachment_path,
                call_date, call_time, device_call_id, latitude, longitude, synced_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?, NOW()
            )
            ON DUPLICATE KEY UPDATE
                synced_at = NOW(),
                duration_seconds = VALUES(duration_seconds),
                call_type = VALUES(call_type),
                call_status = VALUES(call_status),
                client_phone = VALUES(client_phone),
                client_name = VALUES(client_name),
                latitude = COALESCE(VALUES(latitude), latitude),
                longitude = COALESCE(VALUES(longitude), longitude),
                attachment_path = COALESCE(VALUES(attachment_path), attachment_path),
                lead_id = COALESCE(VALUES(lead_id), lead_id)';

    $stmt = $pdo->prepare($sql);
    $stmt->execute([
        $auth_user_id,
        $auth_org_id,
        $leadId !== '' ? $leadId : null,
        $callType,
        $callStatus,
        max(0, $duration),
        $phoneNumber,
        $contactName !== '' ? $contactName : null,
        $attachmentPath,
        $callDate,
        $callTime,
        $deviceCallId,
        $latitude,
        $longitude,
    ]);

    $callId = (int) $pdo->lastInsertId();
    if ($callId === 0) {
        $find = $pdo->prepare(
            'SELECT id FROM call_logs WHERE sales_rep_id = ? AND device_call_id = ? LIMIT 1'
        );
        $find->execute([$auth_user_id, $deviceCallId]);
        $existing = $find->fetch();
        $callId = (int) ($existing['id'] ?? 0);
    }

    respond([
        'success' => true,
        'callId' => $callId > 0 ? $callId : null,
    ]);
} catch (Throwable $e) {
    respond([
        'error' => 'server_error',
        'message' => 'Upload failed',
        'detail' => APP_DEBUG ? $e->getMessage() : null,
    ], 500);
}

# Security / Logic Audit — Syncpedia CRM Android app

Scope covered this pass: auth/session/network layer, call-sync pipeline (CallUploader,
SyncWorker, AppCallStore), the default-dialer call-recording engine (CallRecorder,
SyncpediaInCallService), boot/alarm receivers, manifest, Gradle config, and the PHP
backend under `/api` (bootstrap, helpers, db, auth/login, calls/index+upload, leads,
analytics, auth_check). Not every one of the ~90 Kotlin files got a line-by-line pass —
see "Not reviewed / unverifiable" at the bottom.

## 2026-11-12 pass — "does recording actually work on every phone, and why aren't files in the Files app"

### 8. Real calls never used the robust recording engine — the live path had no silence detection at all (most important finding)

`CallRecorder.kt` contains an elaborate, well-built recording engine: AudioRecord + LAME MP3,
a 5-source priority list (`VOICE_CALL → VOICE_DOWNLINK → VOICE_RECOGNITION → CAMCORDER → MIC`),
a silence watchdog that polls peak amplitude and **switches sources mid-call** if the current
one turns out to be silent, and only keeps a file if its peak amplitude actually passed a
threshold. I searched the whole app for calls to `CallRecorder.start()`/`startRecording()` —
**there are none.** Every real call goes through `SyncpediaInCallService.trySource()` instead,
which is a much simpler, separate implementation:

- plain `MediaRecorder` with only 3 sources (`VOICE_CALL → VOICE_DOWNLINK → MIC`)
- **no amplitude/silence checking at all**, mid-call or at stop — `stopRecorderValidated()`
  only checked `file.length() > 2048L`
- no mid-call source switching

This matters because `VOICE_CALL`/`VOICE_DOWNLINK` frequently "succeed" (MediaRecorder's
`prepare()`/`start()` throw no exception) on Samsung One UI, Xiaomi/MIUI, Vivo/Oppo/realme and
other OEMs that block the actual call-audio mix for policy reasons — the recorder runs for the
whole call and produces a real, 2KB+ file that is **pure silence**. The old code had no way to
tell that apart from a real recording, so on a large share of real-world Android phones the app
would silently "succeed" at recording a call, mark it `isSynced=true`/"Synced" in the UI, and
upload a useless silent file to the CRM — with the user having no idea anything was wrong.

**So, to directly answer "is it really recording on all phones": no — on devices that block
`VOICE_CALL`/`VOICE_DOWNLINK` audio mixing, the app was recording silence and reporting success.**

**Fix** (`SyncpediaInCallService.kt`):
- Added `scheduleSilenceWatch()`, which polls `MediaRecorder.maxAmplitude` every 500ms (reusing
  `RecordingConfig.silencePeakThreshold()`, the same threshold logic `CallRecorder` already
  used). If a non-air-capture source (`VOICE_CALL`/`VOICE_DOWNLINK`) stays silent for ~3s, it
  calls `switchAwayFromSilentSource()`, which discards the silent take and starts the next
  untried source (ultimately `MIC`, which always captures *something* once the user has the
  speaker on, and is flagged to the user via a status message if it's still quiet).
- `stopRecorderValidated()` now also requires the tracked peak amplitude to have crossed the
  threshold for `VOICE_CALL`/`VOICE_DOWNLINK` takes before registering the file (air-capture/MIC
  keeps the old size-only check, since quiet real speech there can legitimately have a low peak).
  A call that ends before 3s on a silent source is now correctly discarded instead of kept.
- This is not a full port of `CallRecorder`'s engine (that would be a bigger refactor: switch
  `SyncpediaInCallService` to reuse `CallRecorder.start()/stop()` directly instead of its own
  `MediaRecorder` calls) — it's the minimum change that gives the *live* call path the same
  silence-detection safety net the unused engine already had. Consider the bigger refactor
  (delete the duplicate `MediaRecorder` logic in `SyncpediaInCallService`, call into
  `CallRecorder` instead) as a follow-up so there's only one recording engine to maintain.

### 9. Recordings never appeared in the phone's Files app by default

`CallRecorder.getRecordingsDir()` saves to `getExternalFilesDir(DIRECTORY_RECORDINGS)` —
`Android/data/com.syncpedia.crm/files/Recordings/...`. That's app-private external storage:
on Android 11+ most file manager apps can't browse `Android/data/*` at all (Google restricted
it), so recordings were invisible outside the app. The only way to get a copy into the public
Files app was a **manual, per-recording** long-press → "Save to Downloads" in
`CallRecordingsActivity` — nothing copied them there automatically.

**Fix:** added `CallRecorder.exportToDownloads()`, called automatically (default **on**, toggle via
`CallRecorder.setAutoSaveToDownloads()`) every time a recording is kept — from both
`CallRecorder.remember()` (the AudioRecord/MP3 path) and transitively from
`registerExternal()` (the `SyncpediaInCallService` MediaRecorder path, since it also calls
`remember()`). It copies into `Downloads/Syncpedia CRM/<contact>_<timestamp>.<ext>` via
`MediaStore` on Android 10+ (no extra permission needed) or direct file copy +
`MediaScannerConnection.scanFile()` on Android 8–9 (only if storage permission is already
granted — this runs from a background/service context with no Activity to prompt from). Runs
on `Dispatchers.IO`, never blocks the call-recording thread, and never throws into the caller.

### 10. `WRITE_EXTERNAL_STORAGE` was never declared — manual "Save to Downloads" was silently broken on Android 8–9

`CallRecordingsActivity.saveToDownloads()` requests `Manifest.permission.WRITE_EXTERNAL_STORAGE`
via `registerForActivityResult(RequestPermission())` on API < 29, but the permission was **not
declared in `AndroidManifest.xml`**. An undeclared permission can't be granted — the system
either auto-denies the request without ever showing the dialog, or the behavior is undefined
depending on OEM. On real Android 8.0/8.1/9 devices (within this app's `minSdk 26`), tapping
"Save to Downloads" would just fail with the "Storage permission is needed" toast forever, with
no way for the user to actually grant it from Settings (it isn't listed there either).

**Fix:** added `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28"/>` to `AndroidManifest.xml`.

### 11. Confirmed: per-device "known-good source" memoization already exists — no change needed

Asked whether the engine needs an upgrade to remember which audio source worked on a given
phone, to skip straight past `VOICE_CALL`/`VOICE_DOWNLINK` probing on OEMs that always block
them. Checked `SyncpediaInCallService.kt` and this is already implemented, pre-existing in
this codebase (not added in any audit pass): `startRecordingInternal()` reads
`prefs.getInt(KEY_SRC, -1)` and tries that remembered source first (`order = listOf(saved) +
SOURCES.filter { it != saved }`), and both `switchAwayFromSilentSource()` and the failure
branch of `stopRecorderValidated()` clear that saved value whenever the remembered source
turns out silent or invalid — so a firmware update that breaks a previously-working source
doesn't get stuck on it forever. No `Build.MODEL` keying is needed since one app install only
ever runs on one physical phone, so per-install `SharedPreferences` already is per-device.
No code change made.

## Fixed previously (earlier passes)

1. **Login fallback bypassed certificate pinning** — `app/src/main/java/com/syncpedia/crm/ui/LoginActivity.kt`
   `rawLogin()` built a brand-new `OkHttpClient` with no `CertificatePinner`, so the one
   retry path that re-sends the user's password (triggered when Retrofit/Gson throws on
   an otherwise-200 response) had no pinning. A MITM with a device-trusted rogue CA could
   intercept it.
   **Fix:** `RetrofitClient` now exposes its pinned `okHttpClient`; `rawLogin()` reuses it.
   (`network/RetrofitClient.kt`, `ui/LoginActivity.kt`)

2. **AuthInterceptor used substring matching on the raw query string** —
   `app/src/main/java/com/syncpedia/crm/network/AuthInterceptor.kt`
   `query.contains("action=login")` etc. would false-match any query string that happens
   to *contain* that substring (e.g. a `redirect=` param), and doesn't parse the actual
   `action` value.
   **Fix:** parses `action` via `HttpUrl.queryParameter("action")` and checks it against
   an explicit `Set`. Also tightened `path.contains("auth.php")` to `path.endsWith(...)`.

3. **Call history stored unencrypted** — `app/src/main/java/com/syncpedia/crm/utils/AppCallStore.kt`
   persisted phone numbers / contact names via plain `MODE_PRIVATE` SharedPreferences
   while the session token already uses `EncryptedSharedPreferences`.
   **Fix:** `AppCallStore` now uses the same `EncryptedSharedPreferences` (AES-256-SIV/GCM)
   approach as `SessionManager`, with a fallback to plain prefs only if the Keystore is
   unavailable (mirrors `SessionManager.createSecurePrefs`).
   **Note:** this changes the storage file's encoding. Any previously-saved plaintext call
   history becomes unreadable (silently returns empty list) after this update — acceptable
   because this list is re-populated from the server via `fetchMerged()`; nothing
   unrecoverable is lost, but call it out to testers so they don't mistake it for a crash.

4. **Call recording had no enforcement-layer consent check (compliance gap)** —
   `ConsentPrefs.hasAccepted()` was only checked in UI navigation (`LoginActivity`,
   `MainActivity`, `CallConsentActivity`). The actual recording engines —
   `CallRecorder.start()` and `SyncpediaInCallService.startRecordingInternal()` — never
   checked it themselves; they only gated on mic permission + per-SIM recording prefs.
   This is a Play Store policy risk (recording without verified consent at the point of
   capture) and violates the stated design goal ("recording must never start before the
   user has accepted CallConsentActivity").
   **Fix:** added a hard `ConsentPrefs.hasAccepted(ctx)` gate inside both
   `CallRecorder.start()` and `SyncpediaInCallService.startRecordingInternal()`, so no
   code path can start capture without consent, regardless of how it was triggered.

5. **Duplicate/dead `consumeRecentPlacement` call** — `SyncpediaInCallService.finishCall()`
   called `AppCallTracker.consumeRecentPlacement(this, phone)` twice: once conditionally
   (assigned to `pending`, used for outgoing calls) and then again unconditionally
   immediately after, discarding the result. For outgoing calls this is a redundant
   double-consume (the second call is a no-op since the first already drained it); for
   incoming calls it needlessly invoked placement-consuming logic that has nothing to
   consume. Logic error / dead code, not a user-visible bug today, but removed because it
   obscures the real control flow and could become a real bug if `consumeRecentPlacement`
   is ever made idempotent-unsafe.
   **Fix:** removed the stray unconditional call.

6. **Release build silently fell back to the debug keystore** — `app/build.gradle` used
   `signingConfig signingConfigs.release` only if `release.jks` (or `KEYSTORE_PATH`)
   existed, otherwise silently signed the "release" build type with the debug keystore.
   Play Console rejects debug-signed uploads, so this would fail at upload time with no
   warning during the build itself.
   **Fix:** `release` build type now always uses `signingConfigs.release`. A
   `gradle.taskGraph.whenReady` check throws a `GradleException` with a clear message
   whenever an `assembleRelease`/`bundleRelease`/`packageRelease`/`signRelease`-style task
   is requested and no real keystore file is present — it does not affect debug builds,
   lint, tests, or any other task. Set `KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS`,
   `KEY_PASS` (or place `release.jks` at the repo root) before running
   `./gradlew bundleRelease`. Never commit the keystore or its passwords; use Play App
   Signing so Google re-signs the upload key for distribution.

7. **No security response headers / no explicit `display_errors` off** — `api/helpers.php`'s
   `cors()` only set `X-Content-Type-Options` and `Cache-Control`.
   **Fix:** added `X-Frame-Options: DENY`, a restrictive `Content-Security-Policy`
   (`default-src 'none'` — this API only ever returns JSON, never HTML), `Referrer-Policy`,
   and `Strict-Transport-Security` (sent only when the request arrived over HTTPS/forwarded
   HTTPS, so it's harmless on local HTTP testing). Also added `error_reporting(E_ALL)` +
   `ini_set('display_errors', '0')` in `bootstrap.php` so warnings/notices can never leak
   into a response body, on top of the existing output-buffer-discard in `respond()`/
   `api_json_die()`.

## ⚠️ Could not audit — signup / password-reset / Google-login endpoints are not in this repo

The Android client (`ApiService.kt`) calls a single `auth.php` endpoint with an `action`
query/body param for **login, signup, forgot_password, verify_reset_otp, reset_password,
google_login, and google_config** (see `AuthInterceptor.kt`'s public-action allow-list).

This repo's `/api` folder only contains **`api/auth/login.php`** (email+password login —
audited above, looks solid) and **no file at all** implements `signup`, `forgot_password`,
`verify_reset_otp`, `reset_password`, `google_login`, or `google_config`. There's also no
`auth.php` router at the API root, and `api/.htaccess` only rewrites `auth/login` →
`auth/login.php`, not `auth.php`.

I cannot audit password hashing, reset-token expiry, OTP flow security, or session
handling for those flows **because the code implementing them isn't present in this
checkout**. Two explanations are possible:

- The live server (`crm.syncpedia.in`) has additional PHP files that were never committed
  to this repo — in which case **those files need to be added to this repo (or shared with
  me) before I can audit them**, and right now nobody reviewing this repo can see what
  they do.
- Or those app features (Google sign-in, signup, forgot-password) are not actually wired
  up server-side yet, and calling them from the app currently 404s.

**You need to tell me which is true.** If the code exists on the server, get it into this
repo (git pull from wherever it's deployed, or paste the files) and I'll audit it in a
follow-up pass — specifically: does signup hash with `password_verify`-compatible
`password_hash()`? Do reset tokens expire and get single-use invalidated? Is the reset OTP
rate-limited per the same pattern as `loginIsBlocked()`? Does `google_login` verify the
Google ID token's signature/audience server-side (not just trust whatever the client sends)?
None of that can be answered from what's checked into this repository today.

## Reviewed — no issue found

- **PHP backend auth (`api/auth/login.php`, `api/helpers.php`, `api/auth_check.php`,
  `api/db.php`):** all SQL uses `PDO` prepared statements with bound params
  (`PDO::ATTR_EMULATE_PREPARES => false`). Passwords use `password_hash`/`password_verify`.
  JWT uses `firebase/php-jwt` with HS256 signature verification (`Key` class, not just
  base64-decode) and server-side expiry (`exp` claim, `TOKEN_EXPIRY_DAYS`). Login is
  rate-limited per (email, IP) with lockout (file-based, `LOGIN_MAX_ATTEMPTS` /
  `LOGIN_LOCKOUT_SECONDS`). `auth_check.php` re-verifies the user is still `is_active` and
  still belongs to the JWT's `org_id` on every request (not just trusting the JWT claims).
  CORS is locked to a single configured origin; `JWT_SECRET` must be ≥32 chars or the API
  refuses to boot. No secrets found committed (`config.php` itself isn't in the repo, only
  `config.example.php`; `local.properties` is clean). Scanned the full (single-commit) git
  history for `password=`, `api_key`, `secret=`, `JWT_SECRET`, `DB_PASS` and found only the
  placeholder values in `config.example.php` (`your_mysql_password`, `change-this-to-a-
  random-secret-key-at-least-32-chars`) — no real secrets were ever committed.
  `.gitignore` already excludes `api/config.php`, `*.jks`/`*.keystore`, and
  `local.properties`. **Nothing to rotate** — no real credential was found in the repo or
  its history.
- **`api/location.php`:** input validated (`latitude`/`longitude` required + range-checked
  -90..90 / -180..180), scoped to `auth_user_id`/`auth_org_id` on insert, parameterized
  query. No issues.
- **`api/uploads/.htaccess`, `api/storage/.htaccess`, `api/.htaccess`:** script execution
  blocked in the uploads tree, `storage/` (rate-limit + log files) fully denied over HTTP,
  and `config.php` blocked from direct access. All correct.
- **`api/calls/upload.php`:** recording uploads are validated by both MIME (`finfo`) and
  extension allow-list, capped at 25MB, written under `org_id`-scoped directories with a
  sanitized filename (regex-stripped), and `api/uploads/.htaccess` denies script execution
  in that tree (`.php`/`.phtml`/etc. → 403). Inserts use `ON DUPLICATE KEY UPDATE` keyed on
  `device_call_id` for idempotent retries. No path traversal: destination path is built from
  server-generated components, not user input.
- **`api/calls/index.php`, `api/leads/index.php`, `api/analytics/index.php`:** every query
  is scoped by `org_id` (and `sales_rep_id`/`assigned_to` for non-admin roles) — a user
  cannot read another org's or another rep's data. No divide-by-zero (analytics returns raw
  counts/sums; no ratio computed server-side).
- **Manifest:** `android:usesCleartextTraffic="false"`, `allowBackup="false"`,
  `dataExtractionRules` present, network security config + cert pinning both in place.
  `BootReceiver` only enqueues `WorkManager` work, never starts a service directly, so it's
  fine under Android 12+ background-start limits. `CallSyncAlarmReceiver` uses
  `setAndAllowWhileIdle` (inexact — no `SCHEDULE_EXACT_ALARM` permission required) plus
  `goAsync()` for its coroutine, which is correct for a non-foreground receiver.
- **Gradle:** `jitpack.io` is correctly declared in `settings.gradle` for MPAndroidChart and
  TAndroidLame (flagged as unmaintained below, but the build config itself is correct).
  Release keystore path/password/alias all come from env vars, nothing hardcoded.

## Not reviewed / unverifiable this pass (be aware before shipping)

I did not have a full day's budget to read all ~90 Kotlin files line-by-line, and I have
no Android build/device/emulator or Play Console access from here. The following items
from your checklist are **not done** and need your (or a follow-up session's) attention:

- **Full call-lifecycle audit** (dual-SIM edge cases, hold/unhold race with recorder,
  InCallActivity restart/lock-screen paths) — I reviewed state transitions in
  `SyncpediaInCallService` at a high level and found the two issues above, but did not
  trace every SIM/hold/race permutation.
- **Room schema vs. `migrations/2026_08_21_mobile_call_sync.sql`** — I did not locate/diff
  this migration file against `CallRecordEntity`/`AppDatabase` in this pass.
- **Runtime permission rationale/denial flows, Android 13+ notifications, Android 14+
  foreground-service-type correctness across every service** — only checked
  `SyncpediaInCallService`'s foreground type (correct: `MICROPHONE|PHONE_CALL`, matches its
  declared permissions). Did not check `CallSyncService` or `NotificationWorker` paths.
- **targetSdk/compileSdk bump to current Play requirement, edge-to-edge/predictive-back
  fallout, exact-alarm permission audit across the whole app** — not attempted; raising
  `targetSdk` can change runtime behavior broadly and needs a real device/emulator pass to
  validate, which I don't have here.
- **Building an `.aab` with `./gradlew bundleRelease`, running `lintRelease` /
  `testReleaseUnitTest`, installing + smoke-testing on a device** — cannot run Gradle/ADB
  from this environment.
- **Privacy policy text, Data Safety form answers, in-app account/data-deletion flow, store
  listing assets** — these are product/legal/content decisions for you to make; see
  checklist below for what's needed.
- **MPAndroidChart (`com.github.PhilJay:MPAndroidChart:v3.1.0`) and
  `com.github.naman14:TAndroidLame:1.1`** — both are unmaintained GitHub-sourced
  dependencies (via JitPack). They work today but have no security patch cadence; consider
  budgeting a migration (e.g. Vico/Compose charts, and a maintained MP3 encoder or
  dropping MP3 in favor of the AAC path `SyncpediaInCallService` already uses).

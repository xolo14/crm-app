package com.syncpedia.crm.utils

import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.syncpedia.crm.data.CallRecordRepository
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.models.ServerCallLog
import com.syncpedia.crm.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Local CRM call history for Syncpedia-originated calls.
 * Does NOT read the device Call Log (Play policy).
 */
object AppCallStore {

    private const val PREFS = "app_call_store"
    private const val KEY_CALLS = "calls"
    private const val MAX = 300
    private const val TAG = "AppCallStore"
    private const val SERVER_PAGE_SIZE = 200
    private const val SERVER_MAX_PAGES = 25 // up to 5,000 server rows

    private val gson = Gson()
    private val idSeq = AtomicLong(System.currentTimeMillis())

    @Synchronized
    fun recordCompleted(
        ctx: Context,
        phone: String,
        contactName: String,
        startedAtMs: Long,
        endedAtMs: Long,
        callType: Int = CallLog.Calls.OUTGOING_TYPE
    ): CallLogItem? {
        val digits = AppCallTracker.normalize(phone)
        if (digits.length < 7 && phone.isBlank()) return null
        val start = if (startedAtMs > 0) startedAtMs else endedAtMs
        val durationSec = ((endedAtMs - start).coerceAtLeast(0L) / 1000L)
        val prefs = prefs(ctx)
        val list = load(prefs).toMutableList()

        // Dedupe: same number within 90s → keep the longer duration entry only
        val existingIdx = list.indexOfFirst { existing ->
            existing.callType == callType &&
                AppCallTracker.phonesMatch(
                    AppCallTracker.normalize(existing.phoneNumber),
                    digits
                ) &&
                kotlin.math.abs(existing.date - start) < 90_000L
        }
        if (existingIdx >= 0) {
            val existing = list[existingIdx]
            if (durationSec <= existing.duration) {
                Log.d(TAG, "skip duplicate call (existing ${existing.duration}s >= ${durationSec}s)")
                return existing
            }
            // Replace shorter/ghost with this longer one
            val updated = existing.copy(
                contactName = contactName.ifBlank { existing.contactName },
                date = minOf(existing.date, start),
                duration = durationSec,
                isSynced = false
            )
            list[existingIdx] = updated
            save(prefs, list.take(MAX))
            Log.d(TAG, "merged call ${updated.id} dur=${updated.duration}s")
            mirrorToRoom(ctx, updated, endedAtMs)
            return updated
        }

        val item = CallLogItem(
            id = idSeq.incrementAndGet(),
            contactName = contactName.ifBlank { phone.ifBlank { "Unknown" } },
            phoneNumber = phone.ifBlank { digits },
            callType = callType,
            date = start,
            duration = durationSec,
            isSynced = false
        )
        list.add(0, item)
        save(prefs, list.take(MAX))
        Log.d(TAG, "recorded call ${item.id} dur=${item.duration}s")
        mirrorToRoom(ctx, item, endedAtMs)
        return item
    }

    fun mirrorToRoom(
        ctx: Context,
        item: CallLogItem,
        endTimeMs: Long = item.date + item.duration * 1000L,
        recordingPath: String? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallRecordRepository(ctx).upsertFromLocalCall(item, endTimeMs, recordingPath)
            } catch (e: Exception) {
                Log.w(TAG, "Room mirror failed: ${e.message}")
            }
        }
    }

    fun getAll(ctx: Context): List<CallLogItem> = load(prefs(ctx))

    /**
     * Local app calls plus paginated server history for the logged-in user.
     * Used by Calls / Analysis so another device still sees synced CRM calls.
     *
     * @param appUploadsOnly when true, only server rows with device_call_id (app uploads).
     *                       when false, include all server call rows for this user (Analysis).
     */
    suspend fun fetchMerged(
        ctx: Context,
        appUploadsOnly: Boolean = true,
        force: Boolean = false
    ): List<CallLogItem> {
        val server = cachedServerCalls(force)
        return withContext(Dispatchers.Default) {
            mergeWithServer(getAll(ctx), server, appUploadsOnly)
        }
    }

    private const val SERVER_CACHE_TTL_MS = 90_000L
    @Volatile private var serverCache: List<ServerCallLog>? = null
    @Volatile private var serverCacheAtMs = 0L
    private val serverCacheLock = Mutex()

    /** Server history shared by Calls / Analysis / detail screens; refetched after the TTL or on [force]. */
    suspend fun cachedServerCalls(force: Boolean = false): List<ServerCallLog> =
        serverCacheLock.withLock {
            val cached = serverCache
            if (!force && cached != null &&
                System.currentTimeMillis() - serverCacheAtMs < SERVER_CACHE_TTL_MS
            ) {
                return@withLock cached
            }
            val fresh = withContext(Dispatchers.IO) { fetchAllServerCalls() }
            // Keep the previous copy if the network failed rather than blanking the screen.
            if (fresh.isNotEmpty() || cached == null) {
                serverCache = fresh
                serverCacheAtMs = System.currentTimeMillis()
            }
            serverCache.orEmpty()
        }

    fun invalidateServerCache() {
        serverCache = null
        serverCacheAtMs = 0L
    }

    suspend fun fetchAllServerCalls(
        pageSize: Int = SERVER_PAGE_SIZE,
        maxPages: Int = SERVER_MAX_PAGES
    ): List<ServerCallLog> {
        val all = mutableListOf<ServerCallLog>()
        var page = 1
        while (page <= maxPages) {
            try {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                val website = RetrofitClient.api.getWebsiteCallLogs(
                    dateFrom = "2020-01-01",
                    dateTo = today,
                    page = page,
                    limit = pageSize
                )
                val websiteBatch = website.body()?.logs.orEmpty()
                if (website.isSuccessful) {
                    if (websiteBatch.isEmpty()) break
                    all.addAll(websiteBatch)
                    val total = website.body()?.total
                    if (total != null && all.size >= total) break
                    if (websiteBatch.size < pageSize) break
                    page++
                    continue
                }
                val resp = RetrofitClient.api.getCallLogs(page = page, limit = pageSize)
                if (!resp.isSuccessful) break
                val envelope = resp.body()
                val batch = envelope?.data.orEmpty()
                all.addAll(batch)
                val total = envelope?.total
                if (batch.isEmpty()) break
                if (total != null && all.size >= total) break
                if (batch.size < pageSize) break
                page++
            } catch (e: Exception) {
                Log.w(TAG, "fetchAllServerCalls page=$page failed: ${e.message}")
                break
            }
        }
        return all
    }

    fun getUnsynced(ctx: Context, sessionSynced: (Long) -> Boolean): List<CallLogItem> =
        getAll(ctx).filter { !it.isSynced && !sessionSynced(it.id) }

    /**
     * Older builds posted to calls.php, which the website Call Log does not show.
     * Mark local calls pending once so they are sent to call_logs.php.
     */
    @Synchronized
    fun queueWebsiteResync(ctx: Context) {
        val syncPrefs = ctx.applicationContext.getSharedPreferences("sp_sync", Context.MODE_PRIVATE)
        if (syncPrefs.getBoolean("website_direct_upload", false)) return
        syncPrefs.edit().putBoolean("website_direct_upload", true).apply()
        val callPrefs = prefs(ctx)
        save(callPrefs, load(callPrefs).map { it.copy(isSynced = false) })
        SessionManager.getInstance(ctx).clearSyncedIds()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.syncpedia.crm.data.AppDatabase.get(ctx).callRecordDao().resetSyncedToPending()
            } catch (e: Exception) {
                Log.w(TAG, "website resync reset failed: ${e.message}")
            }
            try {
                PendingCallSync.uploadAll(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "direct upload failed: ${e.message}")
            }
        }
    }

    @Synchronized
    fun markLocalSynced(ctx: Context, callId: Long) {
        val prefs = prefs(ctx)
        val list = load(prefs).map {
            if (it.id == callId) it.copy(isSynced = true) else it
        }
        save(prefs, list)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallRecordRepository(ctx).markSynced(callId, deleteRecording = false)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Merge local app-placed calls with server history.
     * When [appUploadsOnly] is true, skips website/manual CRM rows without device_call_id.
     * When false, includes all server rows for the logged-in user (Analysis cross-device).
     */
    fun mergeWithServer(
        local: List<CallLogItem>,
        server: List<ServerCallLog>,
        appUploadsOnly: Boolean = true
    ): List<CallLogItem> {
        val localIds = local.map { it.id }.toSet()
        val localIdStrs = localIds.map { it.toString() }.toSet()
        val fromServer = server.mapNotNull { s ->
            val deviceId = s.deviceCallId?.trim().orEmpty()
            if (appUploadsOnly && deviceId.isEmpty()) return@mapNotNull null
            val asLong = deviceId.toLongOrNull()
            if (asLong != null && asLong in localIds) return@mapNotNull null
            if (deviceId.isNotEmpty() && deviceId in localIdStrs) return@mapNotNull null
            serverToItem(s)
        }
        return (local + fromServer).sortedByDescending { it.date }
    }

    private fun serverToItem(s: ServerCallLog): CallLogItem? {
        val phone = s.clientPhone.orEmpty()
        if (phone.isBlank() && s.id == 0) return null
        val type = when (s.callType?.lowercase()) {
            "incoming" -> CallLog.Calls.INCOMING_TYPE
            "missed" -> CallLog.Calls.MISSED_TYPE
            "rejected" -> CallLog.Calls.REJECTED_TYPE
            else -> CallLog.Calls.OUTGOING_TYPE
        }
        val dateMs = parseServerDate(s.callDate, s.callTime) ?: System.currentTimeMillis()
        val id = s.deviceCallId?.toLongOrNull() ?: (1_000_000_000L + s.id)
        return CallLogItem(
            id = id,
            contactName = s.clientName?.takeIf { it.isNotBlank() } ?: phone.ifBlank { "CRM call" },
            phoneNumber = phone,
            callType = type,
            date = dateMs,
            duration = (s.durationSeconds ?: 0).toLong(),
            isSynced = true
        )
    }

    private fun parseServerDate(date: String?, time: String?): Long? {
        if (date.isNullOrBlank()) return null
        val candidates = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd"
        )
        val combined = if (!time.isNullOrBlank()) "$date $time" else date
        for (pattern in candidates) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                return fmt.parse(combined)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    @Volatile
    private var cachedPrefs: android.content.SharedPreferences? = null

    /** Call history contains phone numbers/names, so it goes in EncryptedSharedPreferences, same as the session token. */
    private fun prefs(ctx: Context): android.content.SharedPreferences {
        cachedPrefs?.let { return it }
        val appCtx = ctx.applicationContext
        val created = try {
            val masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
            )
            androidx.security.crypto.EncryptedSharedPreferences.create(
                PREFS,
                masterKeyAlias,
                appCtx,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted call store unavailable, using private prefs", e)
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        cachedPrefs = created
        return created
    }

    private fun load(prefs: android.content.SharedPreferences): List<CallLogItem> {
        val raw = prefs.getString(KEY_CALLS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CallLogItem>>() {}.type
            gson.fromJson<List<CallLogItem>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(prefs: android.content.SharedPreferences, list: List<CallLogItem>) {
        prefs.edit().putString(KEY_CALLS, gson.toJson(list)).apply()
    }
}

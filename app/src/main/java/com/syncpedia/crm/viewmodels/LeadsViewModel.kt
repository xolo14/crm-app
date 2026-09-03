package com.syncpedia.crm.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.syncpedia.crm.models.ActivityItem
import com.syncpedia.crm.models.CreateActivityRequest
import com.syncpedia.crm.models.CreateLeadRequest
import com.syncpedia.crm.models.CreateLeadResponse
import com.syncpedia.crm.models.Lead
import com.syncpedia.crm.models.LeadStatusCard
import com.syncpedia.crm.models.UpdateLeadRequest
import com.syncpedia.crm.network.RetrofitClient
import com.syncpedia.crm.utils.LeadStatuses
import com.syncpedia.crm.utils.SessionManager
import com.syncpedia.crm.utils.TimelineRanges
import kotlinx.coroutines.launch

class LeadsViewModel(app: Application) : AndroidViewModel(app) {

    private val session = SessionManager.getInstance(app)

    private val _leads = MutableLiveData<List<Lead>>(emptyList())
    val leads: LiveData<List<Lead>> = _leads

    private val _statusCards = MutableLiveData<List<LeadStatusCard>>(emptyList())
    val statusCards: LiveData<List<LeadStatusCard>> = _statusCards

    private val _total = MutableLiveData(0)
    val total: LiveData<Int> = _total

    private val _accessLabel = MutableLiveData(session.leadsAccessLabel())
    val accessLabel: LiveData<String> = _accessLabel

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _createSuccess = MutableLiveData<CreateLeadResponse?>(null)
    val createSuccess: LiveData<CreateLeadResponse?> = _createSuccess

    private val _updateSuccess = MutableLiveData<String?>(null)
    val updateSuccess: LiveData<String?> = _updateSuccess

    private val _activities = MutableLiveData<List<ActivityItem>>(emptyList())
    val activities: LiveData<List<ActivityItem>> = _activities

    /** Full latest fetch (up to MAX_LEADS); UI list is timeline-filtered. */
    private var cachedLeads: List<Lead> = emptyList()
    private var filterFromMs: Long = 0L
    private var filterToMs: Long = Long.MAX_VALUE

    fun refreshAccessLabel() {
        _accessLabel.value = session.leadsAccessLabel()
    }

    fun setTimelineRange(fromMs: Long, toMs: Long) {
        filterFromMs = fromMs
        filterToMs = toMs
        publishFiltered()
    }

    fun fetchLeads(
        search: String? = null,
        status: String? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            refreshAccessLabel()

            if (!session.hasOrgAccess() && session.role() == "super_admin") {
                cachedLeads = emptyList()
                _leads.value = emptyList()
                _statusCards.value = emptyList()
                _total.value = 0
                _error.value = "No organization selected — switch org on the website first"
                _isLoading.value = false
                return@launch
            }

            try {
                val orgQuery = if (session.role() == "super_admin") session.getOrgId() else null
                val searchQ = search?.takeIf { it.isNotBlank() }
                val statusQ = status?.takeIf { it.isNotBlank() && it != "all" }
                val pageSize = PAGE_SIZE
                val all = mutableListOf<Lead>()
                var offset = 0
                var reportedTotal = 0
                var pages = 0

                while (pages < MAX_PAGES && all.size < MAX_LEADS) {
                    val remaining = MAX_LEADS - all.size
                    val limit = minOf(pageSize, remaining)
                    val resp = RetrofitClient.api.getLeads(
                        search = searchQ,
                        status = statusQ,
                        statusChanged = null,
                        limit = limit,
                        offset = offset,
                        orgId = orgQuery
                    )
                    if (!resp.isSuccessful) {
                        val err = resp.errorBody()?.string().orEmpty().take(200)
                        _error.value = when (resp.code()) {
                            401 -> "Session expired — please log in again"
                            403 -> "No permission to view leads for this organization"
                            else -> err.ifBlank { "Failed to load leads (${resp.code()})" }
                        }
                        if (all.isNotEmpty()) {
                            cachedLeads = all
                            publishFiltered()
                        }
                        return@launch
                    }

                    val envelope = resp.body()
                    val page = envelope?.data.orEmpty()
                    reportedTotal = envelope?.total ?: (offset + page.size)
                    all.addAll(page)
                    pages++

                    if (all.size >= MAX_LEADS) {
                        if (all.size > MAX_LEADS) {
                            all.subList(MAX_LEADS, all.size).clear()
                        }
                        break
                    }

                    val truncated = envelope?.truncated == true
                    val moreByTotal = all.size < reportedTotal && page.isNotEmpty()
                    if (!truncated && !moreByTotal) break
                    if (page.isEmpty()) break
                    offset += page.size
                    if (page.size < limit) break
                }

                cachedLeads = all
                publishFiltered()
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message ?: "Network error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun publishFiltered() {
        val filtered = TimelineRanges.filterLeads(cachedLeads, filterFromMs, filterToMs)
        _leads.value = filtered
        _total.value = filtered.size
        rebuildCards(filtered)
    }

    companion object {
        /** Newest-first from API; hard cap so the app stays responsive. */
        private const val MAX_LEADS = 10_000
        private const val PAGE_SIZE = 500
        private const val MAX_PAGES = 20 // 20 × 500 = 10,000
    }

    private fun rebuildCards(list: List<Lead>) {
        val grouped = list.groupingBy { LeadStatuses.normalize(it.status) }.eachCount()
        _statusCards.value = LeadStatuses.CARD_ORDER.map { st ->
            LeadStatusCard(
                status = st,
                label = LeadStatuses.label(st),
                count = grouped[st] ?: 0,
                colorRes = LeadStatuses.colorRes(st)
            )
        }
    }

    fun leadsForStatus(status: String, query: String = ""): List<Lead> {
        val want = LeadStatuses.normalize(status)
        val q = query.trim().lowercase()
        return _leads.value.orEmpty().filter { LeadStatuses.normalize(it.status) == want }
            .filter {
                q.isEmpty() ||
                    it.name.lowercase().contains(q) ||
                    (it.phone?.contains(q) == true) ||
                    (it.email?.lowercase()?.contains(q) == true)
            }
    }

    fun createLead(
        name: String,
        phone: String,
        email: String? = null,
        notes: String? = null,
        status: String = "new"
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val resp = RetrofitClient.api.createLead(
                    CreateLeadRequest(
                        name = name,
                        phone = phone,
                        email = email?.takeIf { it.isNotBlank() },
                        notes = notes?.takeIf { it.isNotBlank() },
                        status = LeadStatuses.normalize(status)
                    )
                )
                if (resp.isSuccessful) {
                    _createSuccess.value = resp.body()
                    fetchLeads()
                } else {
                    val err = resp.errorBody()?.string().orEmpty()
                    _error.value = err.ifBlank { "Failed to create lead (${resp.code()})" }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Network error"
                _isLoading.value = false
            }
        }
    }

    fun updateLead(id: String, body: UpdateLeadRequest) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val normalized = body.copy(
                    status = body.status?.let { LeadStatuses.normalize(it) }
                )
                val resp = RetrofitClient.api.updateLead(id, normalized)
                if (resp.isSuccessful) {
                    val msg = resp.body()?.message
                        ?: resp.body()?.error
                        ?: "Lead updated"
                    _updateSuccess.value = msg
                    // Caller / observers refresh; default full list
                    fetchLeads()
                } else {
                    val err = resp.errorBody()?.string().orEmpty().take(200)
                    _error.value = err.ifBlank { "Failed to update lead (${resp.code()})" }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Network error"
                _isLoading.value = false
            }
        }
    }

    fun fetchActivities(leadId: String) {
        viewModelScope.launch {
            try {
                val resp = RetrofitClient.api.getActivities(leadId)
                if (resp.isSuccessful) {
                    _activities.value = resp.body()?.data.orEmpty()
                } else {
                    _activities.value = emptyList()
                }
            } catch (_: Exception) {
                _activities.value = emptyList()
            }
        }
    }

    fun addNoteActivity(leadId: String, note: String) {
        viewModelScope.launch {
            try {
                val resp = RetrofitClient.api.createActivity(
                    CreateActivityRequest(
                        type = "note",
                        subject = "Note",
                        description = note,
                        leadId = leadId
                    )
                )
                if (resp.isSuccessful) {
                    // Also persist on lead.notes for website visibility
                    val lead = _leads.value.orEmpty().firstOrNull { it.id == leadId }
                    val merged = listOfNotNull(lead?.notes?.takeIf { it.isNotBlank() }, note)
                        .joinToString("\n\n")
                    updateLead(leadId, UpdateLeadRequest(notes = merged))
                    fetchActivities(leadId)
                } else {
                    _error.value = "Could not save note"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Network error"
            }
        }
    }

    fun filterLocal(query: String): List<Lead> {
        val q = query.trim().lowercase()
        val all = _leads.value.orEmpty()
        if (q.isEmpty()) return all
        return all.filter {
            it.name.lowercase().contains(q) ||
                (it.phone?.contains(q) == true) ||
                (it.email?.lowercase()?.contains(q) == true)
        }
    }
}

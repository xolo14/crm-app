package com.syncpedia.crm.viewmodels

import android.content.Context
import android.provider.CallLog
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.models.ServerCallLog
import com.syncpedia.crm.network.RetrofitClient
import com.syncpedia.crm.utils.AppCallStore
import kotlinx.coroutines.launch

class CallsViewModel : ViewModel() {

    private val _calls = MutableLiveData<List<CallLogItem>>(emptyList())
    val calls: LiveData<List<CallLogItem>> = _calls

    private val _filteredCalls = MutableLiveData<List<CallLogItem>>(emptyList())
    val filteredCalls: LiveData<List<CallLogItem>> = _filteredCalls

    private val _serverCalls = MutableLiveData<List<ServerCallLog>>(emptyList())
    val serverCalls: LiveData<List<ServerCallLog>> = _serverCalls

    private val _serverError = MutableLiveData<String?>(null)
    val serverError: LiveData<String?> = _serverError

    private val _serverLoading = MutableLiveData(false)
    val serverLoading: LiveData<Boolean> = _serverLoading

    private var currentFilter = "all"

    fun loadCalls(ctx: Context) {
        viewModelScope.launch {
            val local = AppCallStore.getAll(ctx)
            val server = try {
                AppCallStore.fetchAllServerCalls().also { _serverCalls.value = it }
            } catch (_: Exception) {
                emptyList()
            }
            // Local app calls + server rows that have device_call_id (app uploads only)
            _calls.value = AppCallStore.mergeWithServer(local, server)
            filterCalls(currentFilter)
        }
    }

    fun filterCalls(type: String) {
        currentFilter = type
        val all = _calls.value.orEmpty()
        _filteredCalls.value = when (type.lowercase()) {
            "incoming" -> all.filter { it.callType == CallLog.Calls.INCOMING_TYPE }
            "outgoing" -> all.filter { it.callType == CallLog.Calls.OUTGOING_TYPE }
            "missed" -> all.filter { it.callType == CallLog.Calls.MISSED_TYPE }
            "rejected" -> all.filter { it.callType == CallLog.Calls.REJECTED_TYPE }
            else -> all
        }
    }

    fun fetchServerCalls(page: Int = 1, limit: Int = 50) {
        viewModelScope.launch {
            _serverLoading.value = true
            _serverError.value = null
            try {
                val resp = RetrofitClient.api.getCallLogs(page = page, limit = limit)
                if (resp.isSuccessful) {
                    _serverCalls.value = resp.body()?.data.orEmpty()
                } else {
                    _serverError.value = "Failed to load calls (${resp.code()})"
                }
            } catch (e: Exception) {
                _serverError.value = e.message ?: "Network error"
            } finally {
                _serverLoading.value = false
            }
        }
    }
}

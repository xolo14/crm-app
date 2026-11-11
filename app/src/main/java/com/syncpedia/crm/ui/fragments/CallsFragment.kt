package com.syncpedia.crm.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.BottomSheetCallNoteBinding
import com.syncpedia.crm.databinding.FragmentCallsBinding
import com.syncpedia.crm.models.CallLogItem
import com.syncpedia.crm.models.CreateActivityRequest
import com.syncpedia.crm.models.CreateLeadRequest
import com.syncpedia.crm.models.UpdateLeadRequest
import com.syncpedia.crm.network.RetrofitClient
import com.syncpedia.crm.ui.DialPadActivity
import com.syncpedia.crm.ui.adapters.CallLogAdapter
import com.syncpedia.crm.utils.AppCallTracker
import com.syncpedia.crm.utils.AppDialer
import com.syncpedia.crm.utils.LeadStatuses
import com.syncpedia.crm.viewmodels.CallsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallsFragment : Fragment() {

    private var _binding: FragmentCallsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CallsViewModel by viewModels()
    private lateinit var adapter: CallLogAdapter
    private var selectedTab: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = CallLogAdapter(
            onCallClick = { item ->
                AppDialer.dial(requireContext(), item.phoneNumber, item.contactName)
            },
            onItemClick = { item ->
                Toast.makeText(
                    requireContext(),
                    "${item.contactName}\n${item.phoneNumber}",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onNoteClick = { item -> showCallNoteSheet(item) }
        )
        binding.rvCalls.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCalls.adapter = adapter

        viewModel.filteredCalls.observe(viewLifecycleOwner) { list ->
            adapter.submit(list)
            updateEmptyState(list)
            binding.swipeRefresh.isRefreshing = false
        }

        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.syncpedia_teal)
        )
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadCalls(requireContext(), force = true) }

        binding.fabDialer.setOnClickListener {
            startActivity(Intent(requireContext(), DialPadActivity::class.java))
        }

        setupFilters()
        selectedTab = binding.tabAll
        // The first load runs in onResume, right after this.
    }

    private fun updateEmptyState(calls: List<CallLogItem>) {
        if (calls.isEmpty()) {
            binding.rvCalls.visibility = View.GONE
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.tvEmptyTitle.text = "No calls yet"
            binding.tvEmptySub.text = "Calls made from this device will appear here"
        } else {
            binding.rvCalls.visibility = View.VISIBLE
            binding.layoutEmpty.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    private fun showCallNoteSheet(item: CallLogItem) {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = BottomSheetCallNoteBinding.inflate(layoutInflater)
        sheet.tvNoteContact.text = "${item.contactName}\n${item.phoneNumber}"
        sheet.tvNoteLeadHint.text = "Saves to lead notes + activity log (same as Leads)"
        sheet.btnSaveCallNote.setOnClickListener {
            val note = sheet.etCallNote.text?.toString()?.trim().orEmpty()
            if (note.isEmpty()) {
                Toast.makeText(requireContext(), "Enter a note", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sheet.btnSaveCallNote.isEnabled = false
            sheet.btnSaveCallNote.text = "Saving…"
            lifecycleScope.launch {
                val ok = saveNoteToLead(item, note)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(requireContext(), "Note saved to lead", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        sheet.btnSaveCallNote.isEnabled = true
                        sheet.btnSaveCallNote.text = "Save to lead"
                        Toast.makeText(requireContext(), "Could not save note", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    /** Find or create lead by phone, then log activity + merge into lead.notes. */
    private suspend fun saveNoteToLead(item: CallLogItem, note: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val phone = item.phoneNumber.trim()
                val digits = AppCallTracker.normalize(phone)
                val search = RetrofitClient.api.getLeads(search = phone.takeLast(10), limit = 50)
                val leads = if (search.isSuccessful) search.body()?.data.orEmpty() else emptyList()
                var lead = leads.firstOrNull { l ->
                    AppCallTracker.phonesMatch(
                        AppCallTracker.normalize(l.phone.orEmpty()),
                        digits
                    )
                }
                if (lead == null) {
                    val created = RetrofitClient.api.createLead(
                        CreateLeadRequest(
                            name = item.contactName.ifBlank { phone },
                            phone = phone,
                            notes = note,
                            status = LeadStatuses.normalize("contacted")
                        )
                    )
                    if (!created.isSuccessful) return@withContext false
                    val id = created.body()?.id
                    if (id.isNullOrBlank()) {
                        val again = RetrofitClient.api.getLeads(search = phone.takeLast(10), limit = 50)
                        lead = again.body()?.data.orEmpty().firstOrNull { l ->
                            AppCallTracker.phonesMatch(
                                AppCallTracker.normalize(l.phone.orEmpty()),
                                digits
                            )
                        }
                        if (lead == null) return@withContext true
                        // The lead was just created with this note; only log the activity.
                        return@withContext RetrofitClient.api.createActivity(
                            CreateActivityRequest(
                                type = "note",
                                subject = "Call note",
                                description = note,
                                leadId = lead.id
                            )
                        ).isSuccessful
                    } else {
                        RetrofitClient.api.createActivity(
                            CreateActivityRequest(
                                type = "note",
                                subject = "Call note",
                                description = note,
                                leadId = id
                            )
                        )
                        return@withContext true
                    }
                }

                val leadId = lead.id
                val act = RetrofitClient.api.createActivity(
                    CreateActivityRequest(
                        type = "note",
                        subject = "Call note",
                        description = note,
                        leadId = leadId
                    )
                )
                if (!act.isSuccessful) return@withContext false
                val merged = listOfNotNull(lead.notes?.takeIf { it.isNotBlank() }, note)
                    .joinToString("\n\n")
                RetrofitClient.api.updateLead(leadId, UpdateLeadRequest(notes = merged))
                true
            } catch (_: Exception) {
                false
            }
        }

    private fun setupFilters() {
        val tabs = listOf(
            binding.tabAll to "all",
            binding.tabIncoming to "incoming",
            binding.tabOutgoing to "outgoing",
            binding.tabMissed to "missed",
            binding.tabRejected to "rejected"
        )
        tabs.forEach { (tv, type) ->
            tv.setOnClickListener {
                selectTab(tv)
                viewModel.filterCalls(type)
            }
        }
    }

    private fun selectTab(tv: TextView) {
        selectedTab?.apply {
            background = null
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        }
        tv.setBackgroundResource(R.drawable.bg_filter_selected)
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_on_accent))
        selectedTab = tv
    }

    private fun refresh() {
        viewModel.loadCalls(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

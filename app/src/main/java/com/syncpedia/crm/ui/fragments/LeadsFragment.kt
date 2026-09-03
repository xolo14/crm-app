package com.syncpedia.crm.ui.fragments

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.BottomSheetAddLeadBinding
import com.syncpedia.crm.databinding.BottomSheetLeadDetailBinding
import com.syncpedia.crm.databinding.FragmentLeadsBinding
import com.syncpedia.crm.models.Lead
import com.syncpedia.crm.models.UpdateLeadRequest
import com.syncpedia.crm.ui.adapters.LeadAdapter
import com.syncpedia.crm.ui.adapters.LeadStatusCardAdapter
import com.syncpedia.crm.utils.AppDialer
import com.syncpedia.crm.utils.LeadStatuses
import com.syncpedia.crm.utils.SessionManager
import com.syncpedia.crm.utils.TimelineRanges
import com.syncpedia.crm.viewmodels.LeadsViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LeadsFragment : Fragment() {

    private var _binding: FragmentLeadsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LeadsViewModel by viewModels()
    private lateinit var session: SessionManager
    private lateinit var cardAdapter: LeadStatusCardAdapter
    private lateinit var leadAdapter: LeadAdapter

    private var showingCards = true
    private var selectedStatus: String? = null
    private var searchQuery = ""
    private var detailDialog: BottomSheetDialog? = null
    private var detailBinding: BottomSheetLeadDetailBinding? = null
    private var detailLeadId: String? = null

    private var timelineLabel: String = ""
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLeadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager.getInstance(requireContext())
        timelineLabel = getString(R.string.filter_all)
        updateTimelineLabel()

        cardAdapter = LeadStatusCardAdapter { card ->
            openStatusList(card.status, card.label)
        }
        leadAdapter = LeadAdapter(
            onCallClick = { lead ->
                AppDialer.dial(
                    requireContext(),
                    lead.phone.orEmpty(),
                    lead.name.ifBlank { lead.phone.orEmpty() }
                )
            },
            onItemClick = { lead -> showLeadDetail(lead) }
        )

        showCardsMode()

        binding.fabAddLead.visibility =
            if (session.canCreateLeads()) View.VISIBLE else View.GONE
        binding.fabAddLead.setOnClickListener { showAddLeadSheet() }
        binding.btnBack.setOnClickListener { showCardsMode() }
        binding.timelineChip.setOnClickListener { showTimelineMenu(it) }
        binding.tvTimeline.setOnClickListener { showTimelineMenu(binding.timelineChip) }

        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.syncpedia_teal)
        )
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.fetchLeads(search = searchQuery.takeIf { it.length >= 2 })
        }

        viewModel.accessLabel.observe(viewLifecycleOwner) { label ->
            binding.tvAccessLabel.text = label
        }
        viewModel.total.observe(viewLifecycleOwner) {
            binding.tvAccessLabel.text = session.leadsAccessLabel()
        }
        viewModel.statusCards.observe(viewLifecycleOwner) { cards ->
            if (showingCards) {
                cardAdapter.submit(cards)
                binding.tvEmpty.visibility =
                    if (cards.all { it.count == 0 } && viewModel.error.value.isNullOrBlank())
                        View.VISIBLE else View.GONE
            }
            binding.swipeRefresh.isRefreshing = false
        }
        viewModel.leads.observe(viewLifecycleOwner) {
            if (!showingCards) {
                refreshLeadList()
            } else {
                binding.tvEmpty.visibility =
                    if (viewModel.leads.value.isNullOrEmpty()) View.VISIBLE else View.GONE
            }
            detailLeadId?.let { id ->
                val updated = viewModel.leads.value.orEmpty().firstOrNull { it.id == id }
                if (updated != null) detailBinding?.etDetailNotes?.setText(updated.notes.orEmpty())
            }
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            if (loading) binding.swipeRefresh.isRefreshing = true
        }
        viewModel.error.observe(viewLifecycleOwner) { err ->
            if (!err.isNullOrEmpty()) {
                Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                binding.swipeRefresh.isRefreshing = false
            }
        }
        viewModel.createSuccess.observe(viewLifecycleOwner) { created ->
            if (created != null) {
                Toast.makeText(
                    requireContext(),
                    created.message?.takeIf { it.isNotBlank() } ?: "Lead created",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        viewModel.updateSuccess.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
        viewModel.activities.observe(viewLifecycleOwner) { list ->
            val sheet = detailBinding ?: return@observe
            sheet.activityContainer.removeAllViews()
            sheet.tvNoActivity.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            list.forEach { act ->
                val tv = TextView(requireContext()).apply {
                    val whenStr = act.occurredAt ?: act.createdAt ?: ""
                    val body = act.description ?: act.subject ?: act.type ?: ""
                    text = "• ${act.type ?: "note"}  $whenStr\n  $body"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    textSize = 13f
                    setPadding(0, 8, 0, 8)
                }
                sheet.activityContainer.addView(tv)
            }
        }

        viewModel.fetchLeads()
    }

    private fun showCardsMode() {
        showingCards = true
        selectedStatus = null
        binding.listHeader.visibility = View.GONE
        binding.rvContent.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvContent.adapter = cardAdapter
        val cards = viewModel.statusCards.value.orEmpty()
        cardAdapter.submit(cards)
        binding.tvEmpty.text = "No leads in this timeline"
        binding.tvEmpty.visibility =
            if (viewModel.leads.value.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    private fun showTimelineMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, TimelineRanges.Kind.ALL.ordinal, 0, R.string.filter_all)
        popup.menu.add(0, TimelineRanges.Kind.TODAY.ordinal, 1, R.string.timeline_today)
        popup.menu.add(0, TimelineRanges.Kind.YESTERDAY.ordinal, 2, R.string.timeline_yesterday)
        popup.menu.add(0, TimelineRanges.Kind.CURRENT_WEEK.ordinal, 3, R.string.timeline_current_week)
        popup.menu.add(0, TimelineRanges.Kind.PREV_WEEK.ordinal, 4, R.string.timeline_prev_week)
        popup.menu.add(0, TimelineRanges.Kind.CURRENT_MONTH.ordinal, 5, R.string.timeline_current_month)
        popup.menu.add(0, TimelineRanges.Kind.PREV_MONTH.ordinal, 6, R.string.timeline_prev_month)
        popup.menu.add(0, TimelineRanges.Kind.CURRENT_YEAR.ordinal, 7, R.string.timeline_current_year)
        popup.menu.add(0, TimelineRanges.Kind.CUSTOM.ordinal, 8, R.string.timeline_custom)
        popup.setOnMenuItemClickListener { item ->
            val kind = TimelineRanges.Kind.entries.getOrNull(item.itemId)
                ?: TimelineRanges.Kind.ALL
            if (kind == TimelineRanges.Kind.CUSTOM) {
                pickCustomDateRange()
            } else {
                applyTimeline(kind)
            }
            true
        }
        popup.show()
    }

    private fun applyTimeline(kind: TimelineRanges.Kind) {
        val win = TimelineRanges.window(kind)
        timelineLabel = when (kind) {
            TimelineRanges.Kind.ALL -> getString(R.string.filter_all)
            TimelineRanges.Kind.TODAY -> getString(R.string.timeline_today)
            TimelineRanges.Kind.YESTERDAY -> getString(R.string.timeline_yesterday)
            TimelineRanges.Kind.CURRENT_WEEK -> getString(R.string.timeline_current_week)
            TimelineRanges.Kind.PREV_WEEK -> getString(R.string.timeline_prev_week)
            TimelineRanges.Kind.CURRENT_MONTH -> getString(R.string.timeline_current_month)
            TimelineRanges.Kind.PREV_MONTH -> getString(R.string.timeline_prev_month)
            TimelineRanges.Kind.CURRENT_YEAR -> getString(R.string.timeline_current_year)
            TimelineRanges.Kind.CUSTOM -> timelineLabel
        }
        updateTimelineLabel()
        viewModel.setTimelineRange(win.fromMs, win.toMs)
        if (showingCards) {
            showCardsMode()
        } else {
            refreshLeadList()
        }
    }

    private fun pickCustomDateRange() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(y, m, d)
                val fromMs = TimelineRanges.startOfDay(cal.timeInMillis)
                DatePickerDialog(
                    requireContext(),
                    { _, y2, m2, d2 ->
                        cal.set(y2, m2, d2)
                        val toMs = TimelineRanges.endOfDay(cal.timeInMillis)
                        timelineLabel =
                            "${dateFmt.format(Date(fromMs))} - ${dateFmt.format(Date(toMs))}"
                        updateTimelineLabel()
                        viewModel.setTimelineRange(fromMs, toMs)
                        if (showingCards) showCardsMode() else refreshLeadList()
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateTimelineLabel() {
        binding.tvTimeline.text = timelineLabel.ifBlank { getString(R.string.filter_all) }
    }

    private fun openStatusList(status: String, label: String) {
        showingCards = false
        selectedStatus = status
        binding.listHeader.visibility = View.VISIBLE
        binding.tvListTitle.text = "$label leads"
        binding.rvContent.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContent.adapter = leadAdapter
        refreshLeadList()
    }

    private fun refreshLeadList() {
        val status = selectedStatus ?: return
        val list = viewModel.leadsForStatus(status, searchQuery)
        leadAdapter.submit(list)
        binding.tvEmpty.text = "No leads in this status for the selected timeline"
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    fun applySearch(query: String) {
        searchQuery = query
        if (showingCards) {
            if (query.length >= 2) viewModel.fetchLeads(search = query)
            else viewModel.fetchLeads()
        } else {
            if (query.length >= 2) viewModel.fetchLeads(search = query)
            else refreshLeadList()
        }
    }

    fun openAddLead(name: String, phone: String) {
        showAddLeadSheet(name, phone)
    }

    private fun statusDropdownAdapter(): ArrayAdapter<String> {
        val labels = LeadStatuses.ALL.map { LeadStatuses.label(it) }
        return ArrayAdapter(requireContext(), R.layout.item_status_dropdown, labels)
    }

    private fun selectedStatusKey(label: String): String {
        val idx = LeadStatuses.ALL.indexOfFirst { LeadStatuses.label(it).equals(label, true) }
        return if (idx >= 0) LeadStatuses.ALL[idx] else "new"
    }

    private fun showAddLeadSheet(prefillName: String = "", prefillPhone: String = "") {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = BottomSheetAddLeadBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        sheet.etName.setText(prefillName)
        sheet.etPhone.setText(prefillPhone)
        sheet.actStatus.setAdapter(statusDropdownAdapter())
        sheet.actStatus.setText(LeadStatuses.label("new"), false)
        sheet.btnSaveLead.setOnClickListener {
            val name = sheet.etName.text?.toString()?.trim().orEmpty()
            val phone = sheet.etPhone.text?.toString()?.trim().orEmpty()
            if (name.isBlank() || phone.isBlank()) {
                Toast.makeText(requireContext(), "Name and phone required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.createLead(
                name = name,
                phone = phone,
                email = sheet.etEmail.text?.toString()?.trim(),
                notes = sheet.etNotes.text?.toString()?.trim(),
                status = selectedStatusKey(sheet.actStatus.text?.toString().orEmpty())
            )
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showLeadDetail(lead: Lead) {
        detailDialog?.dismiss()
        val dialog = BottomSheetDialog(requireContext())
        val sheet = BottomSheetLeadDetailBinding.inflate(layoutInflater)
        detailDialog = dialog
        detailBinding = sheet
        detailLeadId = lead.id
        dialog.setContentView(sheet.root)
        sheet.tvDetailName.text = lead.name
        sheet.tvDetailPhone.text = lead.phone.orEmpty()
        sheet.tvDetailEmail.text = lead.email.orEmpty()
        sheet.etDetailNotes.setText(lead.notes.orEmpty())
        sheet.actDetailStatus.setAdapter(statusDropdownAdapter())
        sheet.actDetailStatus.setText(LeadStatuses.label(lead.status), false)
        sheet.btnDetailCall.setOnClickListener {
            AppDialer.dial(requireContext(), lead.phone.orEmpty(), lead.name)
        }
        sheet.btnSaveStatus.setOnClickListener {
            val status = selectedStatusKey(sheet.actDetailStatus.text?.toString().orEmpty())
            viewModel.updateLead(lead.id, UpdateLeadRequest(status = status))
        }
        sheet.btnSaveNotes.setOnClickListener {
            val notes = sheet.etDetailNotes.text?.toString()?.trim()
            viewModel.updateLead(lead.id, UpdateLeadRequest(notes = notes))
            if (!notes.isNullOrBlank()) {
                viewModel.addNoteActivity(lead.id, notes)
            }
        }
        sheet.btnAddActivity.setOnClickListener {
            val note = sheet.etNewActivity.text?.toString()?.trim().orEmpty()
            if (note.isBlank()) return@setOnClickListener
            viewModel.addNoteActivity(lead.id, note)
            sheet.etNewActivity.setText("")
        }
        viewModel.fetchActivities(lead.id)
        dialog.setOnDismissListener {
            if (detailDialog === dialog) {
                detailDialog = null
                detailBinding = null
                detailLeadId = null
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        detailDialog?.dismiss()
        _binding = null
        super.onDestroyView()
    }
}

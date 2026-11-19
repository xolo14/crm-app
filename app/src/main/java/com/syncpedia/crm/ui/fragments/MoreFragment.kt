package com.syncpedia.crm.ui.fragments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.syncpedia.crm.data.CallRecordRepository
import com.syncpedia.crm.databinding.FragmentMoreBinding
import com.syncpedia.crm.ui.CallRecordingsActivity
import com.syncpedia.crm.ui.LoginActivity
import com.syncpedia.crm.ui.NotificationsActivity
import com.syncpedia.crm.ui.SimRecordingActivity
import com.syncpedia.crm.utils.DefaultDialerHelper
import com.syncpedia.crm.utils.NotificationSync
import com.syncpedia.crm.utils.SessionManager
import com.syncpedia.crm.utils.SimRecordingPrefs
import com.syncpedia.crm.workers.SyncPrefs
import com.syncpedia.crm.workers.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager.getInstance(requireContext())

        val user = session.getUser()
        if (user != null) {
            val display = user.displayName()
            binding.tvProfileName.text = display
            val orgLabel = session.getOrgName()?.takeIf { it.isNotBlank() }
                ?: session.getOrgId()?.take(8)?.let { "Org $it…" }
                ?: "No organization"
            binding.tvProfileEmail.text = "${user.email}\n$orgLabel"
            binding.tvProfileRole.text = user.role.uppercase()
            binding.tvProfileAvatar.text =
                display.firstOrNull()?.uppercaseChar()?.toString() ?: "S"
        } else {
            binding.tvProfileName.text = "User"
            binding.tvProfileEmail.text = ""
            binding.tvProfileRole.text = "USER"
            binding.tvProfileAvatar.text = "S"
        }

        binding.rowDashboard.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crm.syncpedia.in")))
        }

        binding.rowSyncNow.setOnClickListener { syncNow() }

        binding.rowDefaultDialer.setOnClickListener {
            if (DefaultDialerHelper.isDefaultDialer(requireContext())) {
                Toast.makeText(requireContext(), "Already the default Phone app", Toast.LENGTH_SHORT)
                    .show()
            } else {
                DefaultDialerHelper.requestDefaultDialer(requireActivity())
            }
        }

        binding.rowRecordings.setOnClickListener {
            startActivity(Intent(requireContext(), CallRecordingsActivity::class.java))
        }

        binding.rowSimRecording.visibility = View.VISIBLE
        binding.rowSimRecording.setOnClickListener {
            DefaultDialerHelper.requireDefaultDialerForRecording(
                requireActivity(),
                onReady = {
                    startActivity(Intent(requireContext(), SimRecordingActivity::class.java))
                }
            )
        }

        binding.rowNotifications.setOnClickListener {
            startActivity(Intent(requireContext(), NotificationsActivity::class.java))
        }

        binding.rowAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Syncpedia CRM")
                .setMessage("Version 1.0\nSales & Call Management\nhttps://crm.syncpedia.in")
                .setPositiveButton("OK", null)
                .show()
        }

        binding.btnLogout.setOnClickListener {
            NotificationSync.reset(requireContext())
            session.clearSession()
            val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            requireActivity().finish()
        }

        refreshDialerRow()
        refreshSimRecordingRow()
        refreshSyncSummary()
        observeSyncState()
    }

    private fun refreshDialerRow() {
        binding.tvDefaultDialerLabel.text =
            if (DefaultDialerHelper.isDefaultDialer(requireContext()))
                "Default Phone app · ON (two-way recording)"
            else
                "Set as default Phone app (required for recording)"
    }

    private fun refreshSimRecordingRow() {
        binding.tvSimRecordingSummary.text = SimRecordingPrefs.summaryLabel(requireContext())
    }

    private fun refreshSyncSummary(workState: WorkInfo.State? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                CallRecordRepository(requireContext()).syncSummary()
            }
            if (_binding == null) return@launch
            val statusLine = when (workState) {
                WorkInfo.State.RUNNING -> "Syncing calls…"
                WorkInfo.State.ENQUEUED -> "Sync queued…"
                WorkInfo.State.FAILED ->
                    if (summary.failed + summary.pending > 0) "Sync didn't finish — tap to try again"
                    else "All calls synced · ${getRelativeTime()}"
                WorkInfo.State.SUCCEEDED -> "All calls synced · ${getRelativeTime()}"
                else -> when {
                    // A saved message only counts while something is actually failed/pending;
                    // otherwise it is stale (e.g. the call uploaded later) and is cleared.
                    summary.failed > 0 ->
                        SyncPrefs.needsManual(requireContext())
                            ?: "Some calls didn't upload — tap to try again"
                    summary.pending + summary.uploading > 0 ->
                        SyncPrefs.needsManual(requireContext())
                            ?: "Waiting to upload — tap to sync now"
                    else -> {
                        SyncPrefs.setNeedsManual(requireContext(), null)
                        "All calls synced · ${getRelativeTime()}"
                    }
                }
            }
            binding.tvSyncLabel.text = statusLine
            val parts = buildList {
                if (summary.pending > 0) add("${summary.pending} pending")
                if (summary.failed > 0) add("${summary.failed} failed")
                if (summary.pendingRecordingFiles > 0) add(summary.storageLabel())
                if (isEmpty() && summary.synced > 0) add("${summary.synced} synced")
            }
            if (parts.isEmpty()) {
                binding.tvSyncDetail.visibility = View.GONE
            } else {
                binding.tvSyncDetail.visibility = View.VISIBLE
                binding.tvSyncDetail.text = parts.joinToString(" · ")
            }
        }
    }

    private fun observeSyncState() {
        val workManager = WorkManager.getInstance(requireContext())
        workManager.getWorkInfosForUniqueWorkLiveData("call_sync")
            .observe(viewLifecycleOwner) { refreshFromWork(it.firstOrNull()?.state) }
        workManager.getWorkInfosForUniqueWorkLiveData("call_sync_auto")
            .observe(viewLifecycleOwner) { refreshFromWork(it.firstOrNull()?.state) }
    }

    private fun refreshFromWork(state: WorkInfo.State?) {
        if (_binding == null) return
        if (state == WorkInfo.State.RUNNING) {
            binding.syncProgress.visibility = View.VISIBLE
            binding.ivSyncChevron.visibility = View.GONE
        } else {
            binding.syncProgress.visibility = View.GONE
            binding.ivSyncChevron.visibility = View.VISIBLE
        }
        refreshSyncSummary(state)
    }

    private fun getRelativeTime(): String {
        val prefs = requireContext().getSharedPreferences("sp_sync", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_sync_ts", 0L)
        if (last == 0L) return "never"
        val diff = System.currentTimeMillis() - last
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            else -> "${diff / 3_600_000}h ago"
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refreshDialerRow()
            refreshSimRecordingRow()
            refreshSyncSummary()
        }
    }

    private fun syncNow() {
        binding.syncProgress.visibility = View.VISIBLE
        binding.ivSyncChevron.visibility = View.GONE
        binding.tvSyncLabel.text = "Uploading calls to the website…"
        viewLifecycleOwner.lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                com.syncpedia.crm.utils.PendingCallSync.uploadAll(requireContext())
            }
            if (_binding == null) return@launch
            binding.syncProgress.visibility = View.GONE
            binding.ivSyncChevron.visibility = View.VISIBLE
            binding.tvSyncLabel.text = message
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            refreshSyncSummary()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

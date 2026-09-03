package com.syncpedia.crm.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.syncpedia.crm.databinding.ActivityNotificationsBinding
import com.syncpedia.crm.databinding.ItemNotificationBinding
import com.syncpedia.crm.models.CrmNotification
import com.syncpedia.crm.models.MarkAllReadRequest
import com.syncpedia.crm.network.RetrofitClient
import com.syncpedia.crm.utils.NotificationSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HIGHLIGHT_ID = "highlight_id"
    }

    private lateinit var binding: ActivityNotificationsBinding
    private var items: List<CrmNotification> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationSync.ensureChannel(this)

        binding.btnNotifBack.setOnClickListener { finish() }
        binding.btnMarkAll.setOnClickListener { markAllRead() }
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.swipeNotif.setOnRefreshListener { load(showSpinner = false) }

        load(showSpinner = true)
    }

    private fun load(showSpinner: Boolean) {
        if (showSpinner) binding.progressNotif.visibility = View.VISIBLE
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                NotificationSync.syncAndNotify(this@NotificationsActivity)
            }
            binding.progressNotif.visibility = View.GONE
            binding.swipeNotif.isRefreshing = false
            items = list
            bindList()
        }
    }

    private fun bindList() {
        binding.tvNotifEmpty.visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
        val unread = items.count { !it.isRead }
        binding.tvNotifHint.text = if (unread > 0) {
            "$unread unread · same alerts as the Syncpedia website"
        } else {
            "Same alerts as the Syncpedia website · pull to refresh"
        }
        binding.rvNotifications.adapter = object : RecyclerView.Adapter<VH>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
                val b = ItemNotificationBinding.inflate(layoutInflater, parent, false)
                return VH(b)
            }

            override fun getItemCount() = items.size

            override fun onBindViewHolder(holder: VH, position: Int) {
                holder.bind(items[position])
            }
        }
    }

    private inner class VH(private val b: ItemNotificationBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(n: CrmNotification) {
            b.tvNotifTitle.text = n.title?.ifBlank { "Notification" } ?: "Notification"
            b.tvNotifMessage.text = n.message.orEmpty()
            b.tvNotifMessage.visibility =
                if (n.message.isNullOrBlank()) View.GONE else View.VISIBLE
            b.tvNotifType.text = NotificationSync.typeLabel(n.type)
            b.tvNotifWhen.text = NotificationSync.formatWhen(n.createdAt)
            b.dotUnread.visibility = if (n.isRead) View.GONE else View.VISIBLE
            b.tvNotifTitle.alpha = if (n.isRead) 0.7f else 1f

            b.root.setOnClickListener {
                if (!n.isRead) markOneRead(n)
                openLink(n)
            }
            b.root.setOnLongClickListener {
                confirmDelete(n)
                true
            }
        }
    }

    private fun openLink(n: CrmNotification) {
        val link = n.link?.trim().orEmpty()
        if (link.isBlank()) return
        val url = when {
            link.startsWith("http") -> link
            link.startsWith("/") -> "https://crm.syncpedia.in$link"
            else -> "https://crm.syncpedia.in/$link"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun markOneRead(n: CrmNotification) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.api.markNotificationRead(n.id)
                } catch (_: Exception) {
                }
            }
            NotificationSync.markSeen(this@NotificationsActivity, listOf(n.id))
            load(showSpinner = false)
        }
    }

    private fun markAllRead() {
        val unreadIds = items.filter { !it.isRead }.map { it.id }.filter { it.isNotBlank() }
        if (unreadIds.isEmpty()) {
            Toast.makeText(this, "Nothing unread", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            binding.progressNotif.visibility = View.VISIBLE
            val ok = withContext(Dispatchers.IO) {
                try {
                    val resp = RetrofitClient.api.markAllNotificationsRead(
                        body = MarkAllReadRequest(unreadIds)
                    )
                    resp.isSuccessful
                } catch (_: Exception) {
                    false
                }
            }
            binding.progressNotif.visibility = View.GONE
            if (ok) {
                NotificationSync.markSeen(this@NotificationsActivity, unreadIds)
                Toast.makeText(this@NotificationsActivity, "All marked read", Toast.LENGTH_SHORT).show()
                load(showSpinner = false)
            } else {
                Toast.makeText(this@NotificationsActivity, "Could not update", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(n: CrmNotification) {
        AlertDialog.Builder(this)
            .setTitle("Delete notification?")
            .setMessage(n.title ?: "This notification")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            RetrofitClient.api.deleteNotification(n.id)
                        } catch (_: Exception) {
                        }
                    }
                    load(showSpinner = false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

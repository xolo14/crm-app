package com.syncpedia.crm.ui.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.ItemLeadBinding
import com.syncpedia.crm.models.Lead
import com.syncpedia.crm.utils.LeadStatuses

class LeadAdapter(
    private val onCallClick: (Lead) -> Unit,
    private val onItemClick: (Lead) -> Unit
) : RecyclerView.Adapter<LeadAdapter.VH>() {

    private val items = mutableListOf<Lead>()

    fun submit(list: List<Lead>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemLeadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemLeadBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(lead: Lead) {
            val ctx = b.root.context
            val displayName = lead.name.ifBlank { lead.phone ?: lead.email ?: "Lead" }
            b.tvName.text = displayName
            val phone = lead.phone?.takeIf { it.isNotBlank() }
            val email = lead.email?.takeIf { it.isNotBlank() }
            b.tvPhone.text = listOfNotNull(phone, email).joinToString(" · ").ifBlank { "—" }
            b.tvAvatar.text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            b.tvStatus.text = LeadStatuses.label(lead.status)

            val statusColor = LeadStatuses.colorRes(lead.status)
            val chipBg = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(ContextCompat.getColor(ctx, statusColor))
            }
            b.tvStatus.background = chipBg
            b.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_on_primary))

            val avatarBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(ctx, statusColor))
            }
            b.tvAvatar.background = avatarBg
            b.tvAvatar.setTextColor(ContextCompat.getColor(ctx, R.color.text_on_primary))

            b.btnCall.setOnClickListener { onCallClick(lead) }
            b.root.setOnClickListener { onItemClick(lead) }
        }
    }
}

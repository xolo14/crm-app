package com.syncpedia.crm.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.syncpedia.crm.databinding.ItemStatusCardBinding
import com.syncpedia.crm.models.LeadStatusCard

class LeadStatusCardAdapter(
    private val onClick: (LeadStatusCard) -> Unit
) : RecyclerView.Adapter<LeadStatusCardAdapter.VH>() {

    private val items = mutableListOf<LeadStatusCard>()

    fun submit(list: List<LeadStatusCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemStatusCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemStatusCardBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(card: LeadStatusCard) {
            val color = ContextCompat.getColor(b.root.context, card.colorRes)
            b.tvStatusLabel.text = card.label
            b.tvCount.text = card.count.toString()
            b.accentBar.setBackgroundColor(color)
            b.tvCount.setTextColor(color)
            b.cardRoot.setOnClickListener { onClick(card) }
        }
    }
}

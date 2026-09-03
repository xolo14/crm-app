package com.syncpedia.crm.ui.adapters

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.syncpedia.crm.databinding.ItemContactBinding
import com.syncpedia.crm.databinding.ItemSectionHeaderBinding
import com.syncpedia.crm.models.ContactItem

class ContactAdapter(
    private val onClick: (ContactItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val letter: String) : Row()
        data class Contact(val item: ContactItem) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(contacts: List<ContactItem>) {
        rows.clear()
        var last = ""
        for (c in contacts) {
            val letter = c.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            if (letter != last) {
                rows.add(Row.Header(letter))
                last = letter
            }
            rows.add(Row.Contact(c))
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Contact -> TYPE_CONTACT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            ContactVH(ItemContactBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row.letter)
            is Row.Contact -> (holder as ContactVH).bind(row.item)
        }
    }

    class HeaderVH(private val b: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(letter: String) {
            b.tvSection.text = letter
        }
    }

    inner class ContactVH(private val b: ItemContactBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: ContactItem) {
            b.tvName.text = item.name
            b.tvPhone.text = item.phone
            val initial = item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            b.tvAvatar.text = initial
            val color = colorForName(item.name)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            b.tvAvatar.background = bg
            b.root.setOnClickListener { onClick(item) }
        }
    }

    private fun colorForName(name: String): Int {
        val palette = intArrayOf(
            0xFF26A67D.toInt(), // logo teal
            0xFF378ADD.toInt(), // logo blue
            0xFFFF6200.toInt(), // logo orange
            0xFF2E7D32.toInt(),
            0xFF5C6BC0.toInt(),
            0xFFAB47BC.toInt(),
            0xFF00897B.toInt()
        )
        val idx = (name.hashCode().and(0x7FFFFFFF)) % palette.size
        return palette[idx]
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CONTACT = 1
    }
}

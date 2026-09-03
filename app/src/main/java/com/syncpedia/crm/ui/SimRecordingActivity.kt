package com.syncpedia.crm.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.syncpedia.crm.databinding.ActivitySimRecordingBinding
import com.syncpedia.crm.databinding.ItemSimRecordingBinding
import com.syncpedia.crm.utils.AppDialer
import com.syncpedia.crm.utils.DefaultDialerHelper
import com.syncpedia.crm.utils.SimRecordingPrefs

/**
 * Pick which SIMs auto-start call recording when Syncpedia is the default Phone app.
 */
class SimRecordingActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimRecordingBinding
    private val selected = linkedSetOf<String>()
    private var lines: List<AppDialer.SimLine> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSaveSims.setOnClickListener { save() }

        binding.rvSims.layoutManager = LinearLayoutManager(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refreshGate()
        // If user returned from default-dialer screen with SIMs selected, OK to save
        refresh()
    }

    private fun refresh() {
        lines = AppDialer.listSimLines(this)
        SimRecordingPrefs.ensureBootstrap(this)
        selected.clear()
        selected.addAll(SimRecordingPrefs.getEnabledKeys(this))
        binding.rvSims.adapter = SimAdapter()
        refreshGate()
    }

    private fun refreshGate() {
        val dialerOk = DefaultDialerHelper.isDefaultDialer(this)
        binding.tvSimRecGate.visibility = if (dialerOk) View.GONE else View.VISIBLE
        if (!dialerOk) {
            binding.tvSimRecGate.text =
                "Recording requires Syncpedia as the default Phone app. " +
                    "Set it in More, then return here to choose SIMs."
        }
        binding.btnSaveSims.isEnabled = dialerOk
        binding.btnSaveSims.alpha = if (dialerOk) 1f else 0.5f
    }

    private fun save() {
        if (!DefaultDialerHelper.isDefaultDialer(this)) {
            DefaultDialerHelper.requireDefaultDialerForRecording(this, onReady = { save() })
            return
        }
        SimRecordingPrefs.setEnabledKeys(this, selected.toSet())
        Toast.makeText(
            this,
            if (selected.isEmpty()) "Auto-recording off for all SIMs"
            else "Saved — auto-record on ${selected.size} SIM(s)",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private inner class SimAdapter : RecyclerView.Adapter<SimAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemSimRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(b)
        }

        override fun getItemCount() = lines.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(lines[position])
        }

        inner class Holder(private val b: ItemSimRecordingBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(line: AppDialer.SimLine) {
                b.tvSimLabel.text = line.titleLine
                b.tvSimNumber.text = line.subtitleLine
                val bg = b.simBadge.background
                if (bg is GradientDrawable) {
                    bg.setColor(if (line.badgeColor != 0) line.badgeColor else 0xFF26A67D.toInt())
                } else {
                    b.simBadge.setBackgroundColor(
                        if (line.badgeColor != 0) line.badgeColor else 0xFF26A67D.toInt()
                    )
                }
                b.switchSimRec.setOnCheckedChangeListener(null)
                b.switchSimRec.isChecked = line.simKey in selected
                b.switchSimRec.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(line.simKey) else selected.remove(line.simKey)
                }
                b.root.setOnClickListener { b.switchSimRec.toggle() }
            }
        }
    }
}

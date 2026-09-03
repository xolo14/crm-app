package com.syncpedia.crm.ui

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.syncpedia.crm.databinding.ActivityCallRecordingsBinding
import com.syncpedia.crm.databinding.ItemCallRecordingBinding
import com.syncpedia.crm.utils.CallRecorder
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit

class CallRecordingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CallRecordings"
    }

    private lateinit var binding: ActivityCallRecordingsBinding
    private var player: MediaPlayer? = null
    private var playerStream: FileInputStream? = null
    private var playingPath: String? = null
    private var items: List<CallRecorder.RecordingItem> = emptyList()
    private var listAdapter: RecyclerView.Adapter<*>? = null
    private var seeking = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true && playingPath != null && !seeking) {
                notifyPlayingRow()
                uiHandler.postDelayed(this, 400L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnRecBack.setOnClickListener { finish() }
        binding.tvRecHint.text =
            "Source is shown per file. Discarded takes are kept under discarded/ for diagnosis."
        CallRecorder.purgeSilentFiles(this)
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        reload()
    }

    private fun reload() {
        stopPlayer()
        items = CallRecorder.listAll(this)
        val empty = items.isEmpty()
        binding.tvRecEmpty.visibility =
            if (empty) View.VISIBLE else View.GONE
        if (empty) {
            val status = CallRecorder.loadLastStatus(this)
            binding.tvRecEmpty.text = if (status.isNotBlank()) {
                "No recordings yet.\n\nLast status:\n$status"
            } else {
                "No recordings yet.\nPlace a call from Syncpedia with Microphone allowed.\nTurn on speaker while talking."
            }
        }
        val adapter = object : RecyclerView.Adapter<RecVH>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecVH {
                val b = ItemCallRecordingBinding.inflate(layoutInflater, parent, false)
                return RecVH(b)
            }

            override fun getItemCount() = items.size

            override fun onBindViewHolder(holder: RecVH, position: Int) {
                holder.bind(items[position])
            }

            override fun onBindViewHolder(
                holder: RecVH,
                position: Int,
                payloads: MutableList<Any>
            ) {
                if (payloads.contains("progress")) {
                    holder.bindProgress(items[position])
                } else {
                    holder.bind(items[position])
                }
            }
        }
        listAdapter = adapter
        binding.rvRecordings.adapter = adapter
    }

    private fun notifyPlayingRow() {
        val idx = items.indexOfFirst { it.path == playingPath }
        if (idx >= 0) listAdapter?.notifyItemChanged(idx, "progress")
    }

    private inner class RecVH(private val b: ItemCallRecordingBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bindProgress(item: CallRecorder.RecordingItem) {
            if (playingPath != item.path || player == null) {
                b.rowRecProgress.visibility = View.GONE
                return
            }
            if (seeking) return
            val mp = player ?: return
            val duration = mp.duration.coerceAtLeast(0)
            val position = mp.currentPosition.coerceAtLeast(0)
            b.rowRecProgress.visibility = View.VISIBLE
            b.tvRecTime.text = "${formatMs(position)} / ${formatMs(duration)}"
            b.seekRecProgress.max = if (duration > 0) duration else 100
            b.seekRecProgress.progress = position.coerceAtMost(b.seekRecProgress.max)
            b.tvRecBadge.text = if (mp.isPlaying) "Playing" else "Paused"
            b.tvRecIcon.text = if (mp.isPlaying) "■" else "▶"
        }

        fun bind(item: CallRecorder.RecordingItem) {
            val title = item.contactName.ifBlank { item.phone.ifBlank { "Call recording" } }
            b.tvRecTitle.text = title
            val phone = item.phone.takeIf { it.isNotBlank() } ?: item.digits
            val playing = playingPath == item.path && player != null
            val isPlayingNow = playing && player?.isPlaying == true

            b.tvRecMeta.text = listOfNotNull(
                phone.takeIf { it.isNotBlank() },
                CallRecorder.formatWhen(item.endedAtMs),
                CallRecorder.formatSize(item.path),
                item.sourceLabel.takeIf { it.isNotBlank() }?.substringBefore('(')?.trim(),
                File(item.path).extension.uppercase().takeIf { it.isNotBlank() }
            ).joinToString(" · ")

            b.tvRecBadge.text = when {
                isPlayingNow -> "Playing"
                playing -> "Paused"
                item.uploaded -> "Synced"
                else -> "Local"
            }
            b.tvRecIcon.text = if (isPlayingNow) "■" else "▶"

            if (playing) {
                b.rowRecProgress.visibility = View.VISIBLE
                val mp = player
                val duration = (mp?.duration ?: 0).coerceAtLeast(0)
                val position = (mp?.currentPosition ?: 0).coerceAtLeast(0)
                b.tvRecTime.text = "${formatMs(position)} / ${formatMs(duration)}"
                b.seekRecProgress.max = if (duration > 0) duration else 100
                if (!seeking) {
                    b.seekRecProgress.progress = position.coerceAtMost(b.seekRecProgress.max)
                }
                b.seekRecProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            b.tvRecTime.text = "${formatMs(progress)} / ${formatMs(duration)}"
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        seeking = true
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        seeking = false
                        try {
                            player?.seekTo(seekBar?.progress ?: 0)
                        } catch (_: Exception) {
                        }
                        startProgressUpdates()
                    }
                })
            } else {
                b.rowRecProgress.visibility = View.GONE
                b.seekRecProgress.setOnSeekBarChangeListener(null)
            }

            b.root.setOnClickListener { togglePlay(item) }
            b.tvRecIcon.setOnClickListener { togglePlay(item) }
            b.root.setOnLongClickListener {
                confirmDelete(item)
                true
            }
        }
    }

    private fun formatMs(ms: Int): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun togglePlay(item: CallRecorder.RecordingItem) {
        if (playingPath == item.path && player?.isPlaying == true) {
            stopPlayer()
            listAdapter?.notifyDataSetChanged()
            return
        }
        play(item)
    }

    private fun play(item: CallRecorder.RecordingItem) {
        val file = File(item.path)
        if (!file.exists() || file.length() < 44L) {
            Toast.makeText(this, "Recording file missing or empty", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }

        if (file.extension.equals("wav", true)) {
            repairWavHeader(file)
        }

        stopPlayer()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            val fis = FileInputStream(file)
            playerStream = fis
            mp.setDataSource(fis.fd)
            mp.setOnCompletionListener {
                stopPlayer()
                listAdapter?.notifyDataSetChanged()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                Toast.makeText(this, "Playback error ($what)", Toast.LENGTH_SHORT).show()
                stopPlayer()
                listAdapter?.notifyDataSetChanged()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            playingPath = item.path
            listAdapter?.notifyDataSetChanged()
            startProgressUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "play via FD failed: ${e.message}", e)
            try {
                closeStream()
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    stopPlayer()
                    listAdapter?.notifyDataSetChanged()
                }
                mp.prepare()
                mp.start()
                player = mp
                playingPath = item.path
                listAdapter?.notifyDataSetChanged()
                startProgressUpdates()
            } catch (e2: Exception) {
                Log.e(TAG, "play fallback failed: ${e2.message}", e2)
                stopPlayer()
                Toast.makeText(
                    this,
                    "Cannot play (${file.extension}, ${file.length()} bytes). Try another recording.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startProgressUpdates() {
        uiHandler.removeCallbacks(progressTick)
        uiHandler.post(progressTick)
    }

    private fun repairWavHeader(file: File) {
        try {
            val len = file.length()
            if (len < 44L) return
            val dataLen = (len - 44L).toInt().coerceAtLeast(0)
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                val riff = ByteArray(4)
                raf.readFully(riff)
                if (String(riff) != "RIFF") return
                raf.seek(4)
                writeIntLE(raf, (36 + dataLen))
                raf.seek(40)
                writeIntLE(raf, dataLen)
            }
        } catch (e: Exception) {
            Log.w(TAG, "repairWavHeader: ${e.message}")
        }
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xff)
        raf.write(v shr 8 and 0xff)
        raf.write(v shr 16 and 0xff)
        raf.write(v shr 24 and 0xff)
    }

    private fun confirmDelete(item: CallRecorder.RecordingItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording?")
            .setMessage(item.contactName.ifBlank { item.phone.ifBlank { item.path } })
            .setPositiveButton("Delete") { _, _ ->
                stopPlayer()
                CallRecorder.deleteRecording(this, item.path)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopPlayer() {
        uiHandler.removeCallbacks(progressTick)
        seeking = false
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        playingPath = null
        closeStream()
    }

    private fun closeStream() {
        try {
            playerStream?.close()
        } catch (_: Exception) {
        }
        playerStream = null
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }
}

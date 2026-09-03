package com.syncpedia.crm.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.syncpedia.crm.databinding.ActivityDialPadBinding
import com.syncpedia.crm.utils.AppDialer
import com.syncpedia.crm.utils.PermissionHelper
import com.syncpedia.crm.utils.SessionManager

/** Full-screen dial pad — type a number and place the call from Syncpedia. */
class DialPadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialPadBinding
    private val number = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val session = SessionManager.getInstance(this)
        if (!session.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        binding = ActivityDialPadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val keys = listOf(
            binding.key1 to "1",
            binding.key2 to "2",
            binding.key3 to "3",
            binding.key4 to "4",
            binding.key5 to "5",
            binding.key6 to "6",
            binding.key7 to "7",
            binding.key8 to "8",
            binding.key9 to "9",
            binding.keyStar to "*",
            binding.key0 to "0",
            binding.keyHash to "#"
        )
        keys.forEach { (btn, digit) ->
            btn.setOnClickListener { append(digit) }
            if (digit == "0") {
                btn.setOnLongClickListener {
                    append("+")
                    true
                }
            }
        }

        binding.btnDialBackspace.setOnClickListener {
            if (number.isNotEmpty()) {
                number.deleteCharAt(number.lastIndex)
                refresh()
            }
        }
        binding.btnDialBackspace.setOnLongClickListener {
            number.clear()
            refresh()
            true
        }
        binding.btnDialClose.setOnClickListener { finish() }
        binding.btnDialCall.setOnClickListener { placeCall() }

        val data = intent?.data
        if (data != null && data.scheme.equals("tel", true)) {
            val pre = data.schemeSpecificPart.orEmpty().filter {
                it.isDigit() || it == '+' || it == '*' || it == '#'
            }
            if (pre.isNotEmpty()) {
                number.clear()
                number.append(pre)
            }
        }
        refresh()
    }

    private fun placeCall() {
        val phone = number.toString().trim()
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 3) {
            Toast.makeText(this, "Enter a valid number", Toast.LENGTH_SHORT).show()
            return
        }
        if (!PermissionHelper.has(this, android.Manifest.permission.CALL_PHONE)) {
            PermissionHelper.requestAll(this)
            Toast.makeText(this, "Allow Phone permission, then tap Call again", Toast.LENGTH_LONG).show()
            return
        }
        AppDialer.dial(this, phone)
    }

    private fun append(ch: String) {
        if (number.length >= 20) return
        number.append(ch)
        refresh()
    }

    private fun refresh() {
        val shown = number.toString()
        binding.tvDialNumber.text = shown
        binding.tvDialHint.visibility =
            if (shown.isEmpty()) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }
}

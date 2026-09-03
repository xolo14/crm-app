package com.syncpedia.crm.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.syncpedia.crm.databinding.ActivityCallConsentBinding
import com.syncpedia.crm.utils.ConsentPrefs
import com.syncpedia.crm.utils.PermissionHelper
import com.syncpedia.crm.utils.SessionManager
import com.syncpedia.crm.workers.NotificationWorker

/**
 * Work-purpose consent before using Calls / recording features.
 * Does not use READ_CALL_LOG — Syncpedia dialer / InCall only.
 */
class CallConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallConsentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ConsentPrefs.hasAccepted(this) && SessionManager.getInstance(this).isLoggedIn()) {
            goMain()
            return
        }
        binding = ActivityCallConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cbConsent.setOnCheckedChangeListener { _, checked ->
            binding.btnContinue.isEnabled = checked
        }
        binding.btnContinue.setOnClickListener {
            if (!binding.cbConsent.isChecked) return@setOnClickListener
            ConsentPrefs.accept(this)
            PermissionHelper.requestAll(this)
            goMain()
        }
        binding.btnDecline.setOnClickListener {
            SessionManager.getInstance(this).clearSession()
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }

    private fun goMain() {
        NotificationWorker.schedule(this)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }
}

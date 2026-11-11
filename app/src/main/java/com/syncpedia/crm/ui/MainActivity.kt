package com.syncpedia.crm.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import com.syncpedia.crm.R
import com.syncpedia.crm.databinding.ActivityMainBinding
import com.syncpedia.crm.ui.fragments.AnalysisFragment
import com.syncpedia.crm.ui.fragments.CallsFragment
import com.syncpedia.crm.ui.fragments.ContactsFragment
import com.syncpedia.crm.ui.fragments.LeadsFragment
import com.syncpedia.crm.ui.fragments.MoreFragment
import com.syncpedia.crm.utils.PermissionHelper
import com.syncpedia.crm.utils.DefaultDialerHelper
import com.syncpedia.crm.utils.NotificationSync
import com.syncpedia.crm.utils.SessionManager
import com.syncpedia.crm.workers.NotificationWorker
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var session: SessionManager
    private var pendingLeadName: String? = null
    private var pendingLeadPhone: String? = null
    private var dialerPromptedThisSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            session = SessionManager.getInstance(this)
            if (!ensureLoggedIn()) return
            SessionManager.sessionExpired.observe(this) {
                if (!session.isLoggedIn()) {
                    Toast.makeText(this, "Session expired — please log in again", Toast.LENGTH_LONG).show()
                    ensureLoggedIn()
                }
            }
            if (!com.syncpedia.crm.utils.ConsentPrefs.hasAccepted(this)) {
                startActivity(
                    Intent(this, CallConsentActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                finish()
                return
            }

            setSupportActionBar(binding.toolbar)
            binding.toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.text_on_toolbar))
            binding.toolbar.title = getString(R.string.calls)

            try {
                binding.toolbar.inflateMenu(R.menu.toolbar_menu)
                binding.toolbar.overflowIcon?.setTint(ContextCompat.getColor(this, R.color.text_on_toolbar))
                listOf(R.id.action_search, R.id.action_filter, R.id.action_more).forEach { id ->
                    binding.toolbar.menu.findItem(id)?.icon?.setTint(
                        ContextCompat.getColor(this, R.color.text_on_toolbar)
                    )
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "toolbar menu", e)
            }

            binding.toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_search -> {
                        val frag = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                        if (frag is LeadsFragment) {
                            val input = android.widget.EditText(this).apply {
                                setHint("Search leads")
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_card2))
                                setPadding(32, 24, 32, 24)
                            }
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Search")
                                .setView(input)
                                .setPositiveButton("Go") { _, _ ->
                                    frag.applySearch(input.text?.toString().orEmpty())
                                }
                                .setNegativeButton("Clear") { _, _ -> frag.applySearch("") }
                                .show()
                        }
                        true
                    }
                    else -> false
                }
            }

            if (!PermissionHelper.hasAll(this)) {
                PermissionHelper.requestAll(this)
            } else {
                DefaultDialerHelper.promptIfNeeded(this)
            }

            try {
                NotificationWorker.schedule(this)
                NotificationSync.ensureChannel(this)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NotificationSync.syncAndNotify(this@MainActivity)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "NotificationWorker", e)
            }

            setupBottomNav()

            if (savedInstanceState == null) {
                selectNav(binding.navCalls)
                switchTab(CallsFragment(), getString(R.string.calls))
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "onCreate crash", e)
            var cause: Throwable? = e
            val parts = mutableListOf<String>()
            while (cause != null && parts.size < 4) {
                parts += "${cause.javaClass.simpleName}: ${cause.message}"
                cause = cause.cause
            }
            Toast.makeText(this, parts.joinToString(" → ").take(350), Toast.LENGTH_LONG).show()
        }
    }

    /** Switch to Leads and open add-lead sheet (used from Contacts). */
    fun openLeadsAndAdd(name: String, phone: String) {
        pendingLeadName = name
        pendingLeadPhone = phone
        selectNav(binding.navLeads)
        switchTab(LeadsFragment(), getString(R.string.leads))
        binding.root.postDelayed({
            val frag = supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? LeadsFragment
            val n = pendingLeadName
            val p = pendingLeadPhone
            pendingLeadName = null
            pendingLeadPhone = null
            if (n != null && p != null) {
                frag?.openAddLead(n, p)
            }
        }, 350)
    }

    private fun ensureLoggedIn(): Boolean {
        if (!::session.isInitialized) {
            session = SessionManager.getInstance(this)
        }
        if (session.isLoggedIn()) return true
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
        return false
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            if (!ensureLoggedIn()) return
            checkDefaultDialer()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    NotificationSync.syncAndNotify(this@MainActivity)
                }
            }
        }
    }

    private fun checkDefaultDialer() {
        if (DefaultDialerHelper.isDefaultDialer(this)) return
        if (dialerPromptedThisSession) return
        dialerPromptedThisSession = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.default_dialer_title)
            .setMessage(R.string.default_dialer_msg)
            .setPositiveButton("Set Now") { _, _ ->
                DefaultDialerHelper.requestDefaultDialer(this)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    private fun setupBottomNav() {
        binding.navCalls.setOnClickListener {
            selectNav(binding.navCalls)
            switchTab(CallsFragment(), getString(R.string.calls))
        }
        binding.navLeads.setOnClickListener {
            selectNav(binding.navLeads)
            switchTab(LeadsFragment(), getString(R.string.leads))
        }
        binding.navAnalysis.setOnClickListener {
            selectNav(binding.navAnalysis)
            switchTab(AnalysisFragment(), getString(R.string.analysis))
        }
        binding.navContacts.setOnClickListener {
            selectNav(binding.navContacts)
            switchTab(ContactsFragment(), getString(R.string.contacts))
        }
        binding.navMore.setOnClickListener {
            selectNav(binding.navMore)
            switchTab(MoreFragment(), getString(R.string.more))
        }
    }

    private fun selectNav(item: TextView) {
        val selected = ContextCompat.getColor(this, R.color.tab_selected)
        val unselected = ContextCompat.getColor(this, R.color.tab_unselected)
        listOf(
            binding.navCalls,
            binding.navLeads,
            binding.navAnalysis,
            binding.navContacts,
            binding.navMore
        ).forEach { tv ->
            val color = if (tv === item) selected else unselected
            tv.setTextColor(color)
            tv.compoundDrawables.getOrNull(1)?.let { top ->
                val wrapped = DrawableCompat.wrap(top.mutate())
                DrawableCompat.setTint(wrapped, color)
            }
        }
    }

    private fun switchTab(fragment: Fragment, title: String) {
        try {
            binding.toolbar.title = title
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            Log.e("MainActivity", "switchTab", e)
            Toast.makeText(this, "Tab error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

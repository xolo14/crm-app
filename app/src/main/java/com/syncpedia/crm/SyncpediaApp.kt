package com.syncpedia.crm

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.syncpedia.crm.network.RetrofitClient
import com.syncpedia.crm.utils.AppCallStore
import com.syncpedia.crm.utils.NotificationSync
import com.syncpedia.crm.utils.SessionManager
import com.syncpedia.crm.workers.NotificationWorker

class SyncpediaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // App palette is light Syncpedia; login screen keeps its own dark branding.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        val session = SessionManager.getInstance(this)
        RetrofitClient.init(session)
        NotificationSync.ensureChannel(this)
        if (session.isLoggedIn()) {
            NotificationWorker.schedule(this)
            AppCallStore.queueWebsiteResync(this)
        }
    }
}

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

        com.syncpedia.crm.utils.EdgeToEdge.install(this)

        val session = SessionManager.getInstance(this)
        RetrofitClient.init(session)
        // Any screen opening counts as use; 7 days without use signs the rep out.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) = session.markActive()
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        NotificationSync.ensureChannel(this)
        if (session.isLoggedIn()) {
            NotificationWorker.schedule(this)
            AppCallStore.queueWebsiteResync(this)
        }
    }
}

package com.syncpedia.crm.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class IncomingCallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ANSWER = "com.syncpedia.crm.INCOMING_ANSWER"
        const val ACTION_DECLINE = "com.syncpedia.crm.INCOMING_DECLINE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER -> {
                SyncpediaInCallService.answer()
                IncomingCallNotifier.cancel(context)
            }
            ACTION_DECLINE -> {
                SyncpediaInCallService.hangUp()
                IncomingCallNotifier.cancel(context)
            }
        }
    }
}

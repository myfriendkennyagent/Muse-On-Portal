package com.myfriendkennyagent.museportal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.myfriendkennyagent.museportal.ui.MainActivity

/**
 * Reconnects to Muse after a reboot or an app update, and brings the face
 * back up. Android 10 only lets the activity start from here if the app may
 * draw over other apps (`adb shell appops set <pkg> SYSTEM_ALERT_WINDOW
 * allow`, which tools/deploy.sh does); the connection starts either way.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    Log.i("MuseBoot", "starting after ${intent.action}")
    MuseService.start(context)
    try {
      context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
      Log.i("MuseBoot", "couldn't open the face from the background: $e")
    }
  }
}

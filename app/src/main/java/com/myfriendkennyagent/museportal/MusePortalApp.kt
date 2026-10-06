package com.myfriendkennyagent.museportal

import android.app.Application
import com.myfriendkennyagent.museportal.store.FileDeviceStore
import com.myfriendkennyagent.museportal.store.Settings
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class MusePortalApp : Application() {
  lateinit var store: FileDeviceStore
    private set

  lateinit var settings: Settings
    private set

  override fun onCreate() {
    super.onCreate()
    store = FileDeviceStore(this)
    settings = Settings(this)
    routeProtocolLogs()
  }

  /**
   * The protocol module logs through java.util.logging, as upstream logs
   * through Python's logging; send it to logcat under "Muse.<logger>" so
   * `adb logcat -s Muse.LinkSession` shows "registered with the Muse".
   */
  private fun routeProtocolLogs() {
    val root = Logger.getLogger("")
    root.handlers.forEach { root.removeHandler(it) }
    root.level = Level.INFO
    root.addHandler(
      object : Handler() {
        override fun publish(record: LogRecord) {
          val tag = "Muse." + (record.loggerName ?: "protocol").substringAfterLast('.').take(20)
          val msg = record.message ?: return
          when {
            record.level.intValue() >= Level.SEVERE.intValue() -> android.util.Log.e(tag, msg)
            record.level.intValue() >= Level.WARNING.intValue() -> android.util.Log.w(tag, msg)
            else -> android.util.Log.i(tag, msg)
          }
        }

        override fun flush() {}

        override fun close() {}
      }
    )
  }
}

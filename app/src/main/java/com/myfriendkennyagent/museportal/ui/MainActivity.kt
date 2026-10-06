package com.myfriendkennyagent.museportal.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.myfriendkennyagent.museportal.MusePortalApp
import com.myfriendkennyagent.museportal.service.MuseService

/**
 * The Portal's Muse screen. The face is the whole app: it stays on screen so
 * the mic is in the foreground (Android 10 silences background microphones),
 * and the screen is kept on while it is up.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    MuseService.start(this)
    askPermissions()
    val app = application as MusePortalApp
    setContent {
      PortalTheme {
        var showSettings by remember { mutableStateOf(false) }
        BackHandler(enabled = showSettings) { showSettings = false }
        if (showSettings) {
          SettingsScreen(app.store, app.settings, onClose = { showSettings = false })
        } else {
          AmbientScreen(onOpenSettings = { showSettings = true })
        }
      }
    }
  }

  private fun askPermissions() {
    val wanted =
      listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION).filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
      }
    if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
  }
}

package com.myfriendkennyagent.museportal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.myfriendkennyagent.museportal.BuildConfig
import com.myfriendkennyagent.museportal.Face
import com.myfriendkennyagent.museportal.MuseActions
import com.myfriendkennyagent.museportal.MuseHub
import com.myfriendkennyagent.museportal.MusePortalApp
import com.myfriendkennyagent.museportal.PairingStatus
import com.myfriendkennyagent.museportal.R
import com.myfriendkennyagent.museportal.ble.BlePairingServer
import com.myfriendkennyagent.museportal.commands.PortalCommands
import com.myfriendkennyagent.museportal.protocol.api.MuseApi
import com.myfriendkennyagent.museportal.protocol.chat.ChatEvent
import com.myfriendkennyagent.museportal.protocol.link.ConnectionListener
import com.myfriendkennyagent.museportal.protocol.link.ConnectionState
import com.myfriendkennyagent.museportal.protocol.link.DeviceDescription
import com.myfriendkennyagent.museportal.protocol.link.MuseConnection
import com.myfriendkennyagent.museportal.protocol.link.OkHttpSocketConnector
import com.myfriendkennyagent.museportal.protocol.link.toCommandsJson
import com.myfriendkennyagent.museportal.ui.MainActivity
import com.myfriendkennyagent.museportal.voice.Speaker
import com.myfriendkennyagent.museportal.voice.TurnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the Portal connected to Muse while the app is installed: the link
 * session, the chat subscription, command handling and spoken turns. Runs in
 * the foreground so Portal's aggressive power management leaves it alone.
 */
class MuseService : LifecycleService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var speaker: Speaker
  private lateinit var commands: PortalCommands
  private lateinit var connection: MuseConnection
  private lateinit var turns: TurnController
  private lateinit var ble: BlePairingServer
  private var wifiLock: WifiManager.WifiLock? = null
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    startForeground(NOTIFICATION_ID, notification())
    val app = application as MusePortalApp
    val store = app.store
    val settings = app.settings
    store.importPending()

    val userAgent = "MuseOnPortal/${BuildConfig.VERSION_NAME} (${Build.MODEL}; Android ${Build.VERSION.RELEASE})"
    val api = MuseApi(userAgent = userAgent)
    speaker = Speaker(this).also { it.setRate(settings.speechRate) }
    commands = PortalCommands(this, speaker, quietHours = settings::inQuietHours)
    connection =
      MuseConnection(
        store = store,
        api = api,
        connector = OkHttpSocketConnector(userAgent = userAgent),
        device = {
          DeviceDescription(
            nodeId = store.identity().nodeId,
            displayName = settings.displayName,
            version = BuildConfig.VERSION_NAME,
            commands = commands.specs.toCommandsJson(),
          )
        },
        runner = commands,
        listener =
          object : ConnectionListener {
            override fun onState(state: ConnectionState) {
              MuseHub.connection.value = state
              Log.i(TAG, "connection: $state")
            }

            override fun onChatEvent(event: ChatEvent) {
              turns.offer(event)
            }

            override fun onSubscription(error: String?) {
              MuseHub.subscriptionError.value = error
              if (error == null) turns.onSubscribed() else Log.w(TAG, "reply stream: $error")
            }
          },
      )
    turns = TurnController(this, scope, connection, speaker, settings)
    ble = BlePairingServer(this, store, api, onPaired = { Log.i(TAG, "paired over Bluetooth") })

    MuseHub.actions =
      object : MuseActions {
        override fun talk() = turns.talk()

        override fun cancel() {
          commands.stopMedia()
          turns.cancel()
        }

        override fun sendText(text: String) = turns.sendText(text)

        override fun testSpeech(): Boolean = speaker.speak("This is how Muse will sound on your Portal.")

        override fun startPairing() {
          scope.launch(Dispatchers.IO) {
            ble.start()?.let { reason -> MuseHub.pairing.value = PairingStatus.Failed(reason) }
          }
        }

        override fun stopPairing() = ble.stop()

        override fun unpair() {
          store.deletePairing()
          MuseHub.connection.value = ConnectionState.NotPaired
          // The session notices on its next token use; restart it now instead.
          restartConnection()
        }

        override fun importFromAdb(): List<String> = store.importPending().also { if (it.isNotEmpty()) restartConnection() }
      }

    // Music from media.play_url plays on, quieter, while Muse talks over it.
    scope.launch { speaker.speaking.collect { commands.duck(it) } }

    acquireLocks()
    startConnection()
    MuseHub.setFace(Face.IDLE, "")
  }

  private var connectionJob: kotlinx.coroutines.Job? = null

  private fun startConnection() {
    connectionJob = scope.launch(Dispatchers.IO) { connection.run() }
  }

  private fun restartConnection() {
    connectionJob?.cancel()
    startConnection()
  }

  private fun acquireLocks() {
    val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Suppress("DEPRECATION")
    wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "museportal:wifi").apply { acquire() }
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "museportal:link").apply { acquire() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    return START_STICKY
  }

  override fun onDestroy() {
    MuseHub.actions = null
    ble.stop()
    commands.stopMedia()
    speaker.shutdown()
    scope.cancel()
    wifiLock?.release()
    wakeLock?.release()
    super.onDestroy()
  }

  private fun notification(): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN)
    )
    val open =
      PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, CHANNEL)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(getString(R.string.notification_text))
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentIntent(open)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val TAG = "MuseService"
    private const val CHANNEL = "muse"
    private const val NOTIFICATION_ID = 1

    fun start(context: Context) {
      context.startForegroundService(Intent(context, MuseService::class.java))
    }
  }
}

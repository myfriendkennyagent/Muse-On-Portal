package com.myfriendkennyagent.museportal.commands

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.myfriendkennyagent.museportal.BuildConfig
import com.myfriendkennyagent.museportal.DisplayContent
import com.myfriendkennyagent.museportal.MuseHub
import com.myfriendkennyagent.museportal.protocol.link.CommandResult
import com.myfriendkennyagent.museportal.protocol.link.CommandRunner
import com.myfriendkennyagent.museportal.protocol.link.CommandSpec
import com.myfriendkennyagent.museportal.protocol.link.Param
import com.myfriendkennyagent.museportal.voice.Speaker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The commands Muse can run on the Portal. Descriptions tell Muse what the
 * device is and what each command does, so it can pick them on its own
 * ("show me a picture of a heron on the Portal").
 */
class PortalCommands(private val context: Context, private val speaker: Speaker) : CommandRunner {
  private val http =
    OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var player: MediaPlayer? = null

  private val screen: String
    get() {
      val m = context.resources.displayMetrics
      return "${maxOf(m.widthPixels, m.heightPixels)}x${minOf(m.widthPixels, m.heightPixels)}"
    }

  val specs: List<CommandSpec>
    get() =
      listOf(
        CommandSpec(
          "display.draw_url",
          "Download an image (JPEG, PNG, WebP or GIF) and show it full screen on this Meta Portal's $screen " +
            "colour touchscreen, which sits in the user's home. It stays up until display.show_animation, " +
            "another display command, or a tap. Replies once the image is on screen.",
          required = listOf(Param("url", "string", "http:// or https:// URL of the image.")),
          optional = listOf(Param("caption", "string", "Short text shown under the image.")),
          timeoutMs = 60_000,
        ),
        CommandSpec(
          "display.show_text",
          "Show a card of text on the Portal's screen in large type, readable across a room: a list, " +
            "a recipe step, a reminder. Stays up until display.show_animation, another display command, or a tap.",
          required = listOf(Param("text", "string", "The body text. Plain text; line breaks are kept.")),
          optional = listOf(Param("title", "string", "A heading above the text.")),
        ),
        CommandSpec(
          "display.show_animation",
          "Clear whatever a display command put on the Portal's screen and bring back Muse's face.",
        ),
        CommandSpec(
          "voice.say",
          "Speak text aloud on the Portal's speaker, as an announcement to whoever is in the room. Use it " +
            "to tell the user something proactively on this Portal: when a scheduled or background task " +
            "finds what they asked to hear about, a reminder comes due, or a long task finishes. " +
            "Keep it to a sentence or two. Replies when it has been queued.",
          required = listOf(Param("text", "string", "What to say.")),
        ),
        CommandSpec(
          "voice.configure",
          "Set the Portal speaker volume. Without volume, reports the current one.",
          optional = listOf(Param("volume", "integer", "Speaker volume, 0 to 100.")),
        ),
        CommandSpec(
          "media.play_url",
          "Stream audio (MP3, AAC, Ogg, an internet radio stream) from a URL on the Portal's speaker. " +
            "Replaces anything already playing.",
          required = listOf(Param("url", "string", "http:// or https:// URL of the audio.")),
          timeoutMs = 30_000,
        ),
        CommandSpec("media.stop", "Stop audio started with media.play_url."),
        CommandSpec(
          "device.health",
          "Report the Portal's state: model, Android version, uptime, memory, storage, Wi-Fi, " +
            "battery (Portal Go), speaker volume, text-to-speech engine and app version.",
        ),
      )

  override suspend fun run(command: String, params: JSONObject, timeoutMs: Long?): JSONObject =
    when (command) {
      "display.draw_url" -> drawUrl(params)
      "display.show_text" -> showText(params)
      "display.show_animation" -> {
        MuseHub.display.value = null
        CommandResult.ok()
      }
      "voice.say" -> say(params)
      "voice.configure" -> configureVolume(params)
      "media.play_url" -> playUrl(params)
      "media.stop" -> {
        stopMedia()
        CommandResult.ok()
      }
      "device.health" -> CommandResult.ok(health())
      else -> CommandResult.error("unsupported command: $command")
    }

  private suspend fun drawUrl(params: JSONObject): JSONObject {
    val url = params.optString("url")
    if (!url.startsWith("http://") && !url.startsWith("https://")) return CommandResult.error("url must be http(s)")
    val bitmap =
      withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
          if (!response.isSuccessful) return@withContext null
          val bytes = response.body?.bytes() ?: return@withContext null
          if (bytes.size > MAX_IMAGE_BYTES) return@withContext null
          // Decode at most about screen size to keep memory in check.
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
          var sample = 1
          while (bounds.outWidth / (sample * 2) >= 1280 || bounds.outHeight / (sample * 2) >= 800) sample *= 2
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
      } ?: return CommandResult.error("couldn't download or decode the image")
    MuseHub.display.value = DisplayContent.Image(bitmap, params.optString("caption"))
    return CommandResult.ok(JSONObject().put("width", bitmap.width).put("height", bitmap.height))
  }

  private fun showText(params: JSONObject): JSONObject {
    val text = params.optString("text")
    if (text.isBlank()) return CommandResult.error("text is required")
    MuseHub.display.value = DisplayContent.Text(params.optString("title"), text)
    return CommandResult.ok()
  }

  private fun say(params: JSONObject): JSONObject {
    val text = params.optString("text")
    if (text.isBlank()) return CommandResult.error("text is required")
    if (!speaker.available.value) return CommandResult.error("no text-to-speech engine is installed on this Portal")
    speaker.speak(text)
    return CommandResult.ok()
  }

  private fun configureVolume(params: JSONObject): JSONObject {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (params.has("volume")) {
      val pct = params.optInt("volume", -1)
      if (pct !in 0..100) return CommandResult.error("volume must be 0 to 100")
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, (pct * max + 50) / 100, 0)
    }
    val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(max, 1)
    return CommandResult.ok(JSONObject().put("volume", now))
  }

  private suspend fun playUrl(params: JSONObject): JSONObject {
    val url = params.optString("url")
    if (!url.startsWith("http://") && !url.startsWith("https://")) return CommandResult.error("url must be http(s)")
    stopMedia()
    return withContext(Dispatchers.Main) {
      try {
        player =
          MediaPlayer().apply {
            setAudioAttributes(
              AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            )
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stopMedia() }
            setOnErrorListener { _, _, _ ->
              stopMedia()
              true
            }
            prepareAsync()
          }
        CommandResult.ok()
      } catch (e: Exception) {
        stopMedia()
        CommandResult.error("couldn't play that: ${e.message}")
      }
    }
  }

  fun stopMedia() {
    player?.let {
      try {
        it.stop()
      } catch (e: IllegalStateException) {}
      it.release()
    }
    player = null
  }

  /** True while media.play_url audio is playing. */
  val mediaPlaying: Boolean
    get() =
      try {
        player?.isPlaying == true
      } catch (e: IllegalStateException) {
        false
      }

  private fun health(): JSONObject {
    val mem = ActivityManager.MemoryInfo().also {
      (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }
    val stat = StatFs(Environment.getDataDirectory().path)
    val wifi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val json =
      JSONObject()
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        .put("app_version", BuildConfig.VERSION_NAME)
        .put("uptime_s", SystemClock.elapsedRealtime() / 1000)
        .put("memory_available_mb", mem.availMem / 1_048_576)
        .put("memory_total_mb", mem.totalMem / 1_048_576)
        .put("storage_free_mb", stat.availableBytes / 1_048_576)
        .put("storage_total_mb", stat.totalBytes / 1_048_576)
        .put("wifi_rssi_dbm", wifi?.rssi ?: JSONObject.NULL)
        .put("screen", screen)
        .put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 1))
        .put("tts_engine", if (speaker.available.value) speaker.engineName else JSONObject.NULL)
        .put("media_playing", mediaPlaying)
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val present = battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) ?: false
    if (present && level >= 0 && scale > 0) {
      json.put("battery_percent", level * 100 / scale)
      json.put("charging", (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    } else {
      json.put("battery_percent", JSONObject.NULL)
    }
    return json
  }

  companion object {
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
  }
}

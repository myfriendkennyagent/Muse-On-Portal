package com.myfriendkennyagent.museportal.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Speaks Muse's replies through the platform TextToSpeech API.
 *
 * Portals ship with no TTS engine. Install one (see docs/TTS.md: the
 * on-device sherpa-onnx engine is the tested choice); it is preferred when
 * present. With no engine, [available] stays false and replies are captions
 * only.
 */
class Speaker(context: Context) {
  private val pending = AtomicInteger(0)
  private val _speaking = MutableStateFlow(false)
  val speaking: StateFlow<Boolean> = _speaking

  private val _available = MutableStateFlow(false)
  val available: StateFlow<Boolean> = _available

  var engineName: String = "none"
    private set

  private var tts: TextToSpeech? = null
  private val queuedBeforeInit = ArrayList<String>()
  private var onDone: (() -> Unit)? = null

  init {
    val app = context.applicationContext
    val listener =
      TextToSpeech.OnInitListener { status ->
        val engine = tts ?: return@OnInitListener
        if (status == TextToSpeech.SUCCESS) {
          engine.language = Locale.US
          engine.setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ASSISTANT)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build()
          )
          engine.setOnUtteranceProgressListener(progress)
          engineName = engine.defaultEngine ?: "unknown"
          _available.value = true
          Log.i(TAG, "TTS ready: $engineName")
          synchronized(queuedBeforeInit) {
            queuedBeforeInit.forEach { speak(it) }
            queuedBeforeInit.clear()
          }
        } else {
          Log.w(TAG, "no TTS engine; replies will be captions only")
          _available.value = false
        }
      }
    val preferred = PREFERRED_ENGINES.firstOrNull { isInstalled(app, it) }
    tts = if (preferred != null) TextToSpeech(app, listener, preferred) else TextToSpeech(app, listener)
  }

  private val progress =
    object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {
        _speaking.value = true
      }

      override fun onDone(utteranceId: String?) = finished()

      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) = finished()

      override fun onError(utteranceId: String?, errorCode: Int) = finished()

      override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
    }

  private fun finished() {
    if (pending.decrementAndGet() <= 0) {
      pending.set(0)
      _speaking.value = false
      onDone?.invoke()
    }
  }

  fun setRate(rate: Float) {
    tts?.setSpeechRate(rate)
  }

  /** Called once everything queued has been spoken. */
  fun setOnIdle(callback: () -> Unit) {
    onDone = callback
  }

  /** Queues [text]; returns false if there is no engine to speak it. */
  fun speak(text: String): Boolean {
    val engine = tts
    if (!_available.value || engine == null) {
      synchronized(queuedBeforeInit) { if (queuedBeforeInit.size < 4) queuedBeforeInit += text }
      return false
    }
    val spoken = forSpeech(text)
    if (spoken.isEmpty()) return true
    for (part in split(spoken)) {
      pending.incrementAndGet()
      _speaking.value = true
      val id = "u" + System.nanoTime()
      if (engine.speak(part, TextToSpeech.QUEUE_ADD, Bundle(), id) != TextToSpeech.SUCCESS) finished()
    }
    return true
  }

  fun stop() {
    synchronized(queuedBeforeInit) { queuedBeforeInit.clear() }
    tts?.stop()
    pending.set(0)
    _speaking.value = false
  }

  fun shutdown() {
    tts?.shutdown()
    tts = null
  }

  companion object {
    private const val TAG = "MuseSpeaker"
    /** On-device engines known to work on Portal, best first. */
    val PREFERRED_ENGINES = listOf("com.k2fsa.sherpa.onnx.tts.engine")

    private fun isInstalled(context: Context, pkg: String): Boolean =
      try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
      } catch (e: Exception) {
        false
      }

    /** Strips Markdown and links so replies read naturally aloud. */
    fun forSpeech(text: String): String =
      text
        .replace(Regex("```[\\s\\S]*?```"), " (code on screen) ")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "a link")
        .replace(Regex("[*_`#>|~]+"), "")
        .replace(Regex("^[ \\t]*(?:[-•]|\\d+[.)])[ \\t]+", RegexOption.MULTILINE), "")
        // A line break after unpunctuated text is a pause: make it a sentence end.
        .replace(Regex("([^.!?:;,\\s])[ \\t]*\\n+"), "$1. ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Engines have an input limit; split long replies at sentence ends. */
    fun split(text: String, max: Int = 3000): List<String> {
      if (text.length <= max) return listOf(text)
      val parts = ArrayList<String>()
      var rest = text
      while (rest.length > max) {
        val cut = rest.lastIndexOf(". ", max).takeIf { it > max / 2 }?.plus(1) ?: max
        parts += rest.substring(0, cut).trim()
        rest = rest.substring(cut)
      }
      if (rest.isNotBlank()) parts += rest.trim()
      return parts
    }
  }
}

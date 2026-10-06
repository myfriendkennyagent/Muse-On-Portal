package com.myfriendkennyagent.museportal.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.myfriendkennyagent.museportal.Face
import com.myfriendkennyagent.museportal.MuseHub
import com.myfriendkennyagent.museportal.protocol.chat.ChatEvent
import com.myfriendkennyagent.museportal.protocol.chat.ChatTracker
import com.myfriendkennyagent.museportal.protocol.chat.ChatUpdate
import com.myfriendkennyagent.museportal.protocol.chat.EndReason
import com.myfriendkennyagent.museportal.protocol.chat.VoiceNote
import com.myfriendkennyagent.museportal.protocol.link.MuseConnection
import com.myfriendkennyagent.museportal.store.Settings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One spoken exchange at a time: tap → listen → voice note → Muse transcribes
 * and replies in text → captions stream in → the reply is spoken.
 *
 * Everything here runs on the main thread ([scope] must use Dispatchers.Main),
 * so the tracker and UI state need no locks.
 */
class TurnController(
  private val context: Context,
  private val scope: CoroutineScope,
  private val connection: MuseConnection,
  private val speaker: Speaker,
  private val settings: Settings,
) {
  private val recorder = VoiceRecorder()
  private val events = Channel<ChatEvent>(Channel.UNLIMITED)
  private var recordJob: Job? = null
  private var listening = false
  private var lastPartial = ""

  private val tracker = ChatTracker(ownSessionId = { settings.sideChatId }, emit = ::onUpdate)

  init {
    scope.launch { for (e in events) tracker.onEvent(e) }
    scope.launch {
      while (isActive) {
        tracker.tick()
        delay(250)
      }
    }
    speaker.setOnIdle {
      scope.launch {
        if (!listening && MuseHub.ui.value.face == Face.SPEAKING) {
          MuseHub.setFace(if (tracker.turnActive) Face.THINKING else Face.IDLE)
        }
      }
    }
  }

  /** From the subscription stream (any thread). */
  fun offer(event: ChatEvent) {
    events.trySend(event)
  }

  /** The reply stream (re)opened: messages in the next few seconds may be replayed history. */
  fun onSubscribed() {
    scope.launch { tracker.onSubscribed() }
  }

  /** The talk button: start listening, or finish the current recording. */
  fun talk() {
    if (listening) {
      recorder.stop()
      return
    }
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      MuseHub.setFace(Face.ERROR, "I need microphone access. Open settings to allow it.")
      return
    }
    // Barge in: stop talking and listen.
    speaker.stop()
    tracker.cancelTurn()
    listening = true
    MuseHub.ui.update { it.copy(face = Face.LISTENING, caption = "Listening…", heard = "") }
    recordJob =
      scope.launch {
        val rec = recorder.record { level -> MuseHub.ui.update { it.copy(level = level) } }
        listening = false
        when {
          rec.reason == StopReason.MIC_ERROR -> MuseHub.setFace(Face.ERROR, "The microphone isn't available.")
          !rec.heardSpeech -> MuseHub.setFace(Face.IDLE, "I didn't catch that.")
          else -> {
            send(rec.pcm)
            // Muse stops listening at 15 s: say so rather than cut off silently.
            if (rec.clipped) MuseHub.ui.update { it.copy(heard = "(clipped at 15 seconds: try something shorter)") }
          }
        }
      }
  }

  fun cancel() {
    if (listening) {
      recordJob?.cancel()
      listening = false
    }
    speaker.stop()
    tracker.cancelTurn()
    MuseHub.setFace(Face.IDLE, "")
  }

  /** A typed message, from the on-screen keyboard. */
  fun sendText(text: String) {
    if (text.isBlank()) return
    speaker.stop()
    MuseHub.ui.update { it.copy(face = Face.THINKING, caption = "", heard = text) }
    post(VoiceNote.textBody(text, settings.sideChatId))
  }

  private fun send(pcm: ByteArray) {
    MuseHub.setFace(Face.THINKING, "")
    val sideChat = settings.sideChatId
    // Base64 of up to 15 s of audio: build it off the main thread.
    post { withContext(Dispatchers.Default) { VoiceNote.chatBody(VoiceNote.wav(pcm), sideChat) } }
  }

  private fun post(body: JSONObject) = post { body }

  private fun post(body: suspend () -> JSONObject) {
    tracker.beginTurn()
    lastPartial = ""
    scope.launch {
      try {
        val ack = connection.sendChat(body())
        if (!ack.ok) {
          tracker.cancelTurn()
          MuseHub.setFace(Face.ERROR, "Muse couldn't take that (HTTP ${ack.status}).")
          return@launch
        }
        val json = ack.json()
        val obj = json?.optJSONObject("result") ?: json
        tracker.onAck(obj?.optString("message_id"), obj?.optString("reply_to_message_id"))
      } catch (e: IOException) {
        Log.w(TAG, "chat failed: $e")
        tracker.cancelTurn()
        MuseHub.setFace(Face.ERROR, "I can't reach Muse right now.")
      }
    }
  }

  private fun onUpdate(update: ChatUpdate) {
    when (update) {
      is ChatUpdate.Heard -> MuseHub.ui.update { it.copy(heard = update.text) }
      is ChatUpdate.Partial -> {
        lastPartial = update.text
        if (!speaker.speaking.value) MuseHub.ui.update { it.copy(caption = update.text) }
      }
      is ChatUpdate.Reply -> reply(update.text)
      is ChatUpdate.Busy ->
        if (!speaker.speaking.value) MuseHub.setFace(if (update.busy) Face.WORKING else Face.THINKING)
      is ChatUpdate.TurnEnded ->
        when (update.reason) {
          EndReason.NO_REPLY ->
            if (!update.sawOwnMessage && settings.sideChatId != null) {
              // Not even our own message came back: side-chat events may not reach
              // this subscription. Fall back to the main chat, where they do.
              Log.w(TAG, "no events for a side-chat turn; switching to the main chat")
              settings.sideChatId = null
              MuseHub.setFace(Face.IDLE, "Replies didn't come back from the Portal's own chat, so I switched to your main chat. Please ask again.")
            } else {
              MuseHub.setFace(Face.IDLE, "Muse is still working on it. I'll tell you when it's done.")
            }
          EndReason.TOO_LONG -> MuseHub.setFace(Face.IDLE)
          EndReason.SETTLED,
          EndReason.CANCELLED -> if (!speaker.speaking.value && !listening) MuseHub.setFace(Face.IDLE)
        }
      is ChatUpdate.Announcement -> announce(update.text)
    }
  }

  private fun reply(text: String) {
    MuseHub.ui.update { it.copy(caption = text) }
    if (settings.speakReplies && speaker.speak(text)) {
      MuseHub.setFace(Face.SPEAKING)
    } else {
      MuseHub.setFace(Face.IDLE)
    }
  }

  private fun announce(text: String) {
    if (!settings.announcements || listening || tracker.turnActive) {
      if (tracker.turnActive) reply(text)
      return
    }
    MuseHub.ui.update { it.copy(caption = text, heard = "") }
    if (settings.speakReplies && !settings.inQuietHours() && speaker.speak(text)) MuseHub.setFace(Face.SPEAKING)
  }

  companion object {
    private const val TAG = "MuseTurn"
  }
}

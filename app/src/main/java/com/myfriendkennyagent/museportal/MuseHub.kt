package com.myfriendkennyagent.museportal

import android.graphics.Bitmap
import com.myfriendkennyagent.museportal.protocol.link.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the face is doing. */
enum class Face { IDLE, LISTENING, THINKING, WORKING, SPEAKING, ERROR }

data class UiState(
  val face: Face = Face.IDLE,
  /** What Muse is saying, or a short status line. */
  val caption: String = "",
  /** What Muse heard you say. */
  val heard: String = "",
  /** Mic level 0..1 while listening. */
  val level: Float = 0f,
)

/** Something Muse put on the screen with a display command. */
sealed class DisplayContent {
  data class Image(val bitmap: Bitmap, val caption: String) : DisplayContent()

  data class Text(val title: String, val body: String) : DisplayContent()
}

sealed class PairingStatus {
  data object Idle : PairingStatus()

  data class Advertising(val bleName: String, val secondsLeft: Long) : PairingStatus()

  data class InProgress(val step: String) : PairingStatus()

  data object Paired : PairingStatus()

  data class Failed(val reason: String) : PairingStatus()
}

/** Actions the UI can ask of the running service. */
interface MuseActions {
  /** Starts listening, or stops the current recording and sends it. */
  fun talk()

  /** Stops speaking and cancels the current turn. */
  fun cancel()

  fun sendText(text: String)

  /** Speaks a sample sentence locally; returns false if there is no TTS engine. */
  fun testSpeech(): Boolean

  fun startPairing()

  fun stopPairing()

  fun unpair()

  fun importFromAdb(): List<String>
}

/** Process-wide state shared by the service and the UI. */
object MuseHub {
  val connection = MutableStateFlow<ConnectionState>(ConnectionState.NotPaired)
  val ui = MutableStateFlow(UiState())
  val display = MutableStateFlow<DisplayContent?>(null)
  val pairing = MutableStateFlow<PairingStatus>(PairingStatus.Idle)

  /** Why replies can't reach this Portal, or null while the chat subscription is healthy. */
  val subscriptionError = MutableStateFlow<String?>(null)

  /** Set while the service runs. */
  @Volatile var actions: MuseActions? = null

  val uiState: StateFlow<UiState> = ui

  fun setFace(face: Face, caption: String? = null) =
    ui.update { it.copy(face = face, caption = caption ?: it.caption, level = if (face == Face.LISTENING) it.level else 0f) }
}

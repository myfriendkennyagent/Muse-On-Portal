package com.myfriendkennyagent.museportal.protocol.chat

import com.myfriendkennyagent.museportal.protocol.Bytes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Voice notes, the way the Muse phone app and the ESP32 firmware send speech:
 * a 16 kHz mono 16-bit WAV attached to a `/chat/stream` message. The server
 * transcribes it (the streaming dictation endpoint has no speech recognition
 * behind it yet). Muse stops listening after about 15 seconds.
 */
object VoiceNote {
  const val SAMPLE_RATE = 16_000
  const val MAX_SECONDS = 15
  const val MAX_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS
  const val WAV_HEADER_BYTES = 44

  /** Wraps little-endian 16-bit mono PCM in a WAV header. */
  fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
    val data = if (pcm.size > MAX_BYTES) pcm.copyOf(MAX_BYTES) else pcm
    val header =
      ByteBuffer.allocate(WAV_HEADER_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray())
        .putInt(36 + data.size)
        .put("WAVE".toByteArray())
        .put("fmt ".toByteArray())
        .putInt(16) // PCM fmt chunk size
        .putShort(1) // PCM
        .putShort(1) // mono
        .putInt(sampleRate)
        .putInt(sampleRate * 2) // byte rate
        .putShort(2) // block align
        .putShort(16) // bits per sample
        .put("data".toByteArray())
        .putInt(data.size)
        .array()
    return header + data
  }

  /** The `/chat/stream` body for a voice note. */
  fun chatBody(wav: ByteArray, sessionId: String? = null): JSONObject {
    val item =
      JSONObject()
        .put("type", "file")
        .put("mime_type", "audio/wav")
        .put("filename", "voice_note.wav")
        .put("data_base64", Bytes.b64(wav))
    val body = JSONObject().put("message", "").put("output_modality", "text").put("items", JSONArray().put(item))
    sessionId?.let { body.put("session_id", it) }
    return body
  }

  /** The `/chat/stream` body for a typed message. */
  fun textBody(message: String, sessionId: String? = null): JSONObject {
    val body = JSONObject().put("message", message).put("output_modality", "text")
    sessionId?.let { body.put("session_id", it) }
    return body
  }
}

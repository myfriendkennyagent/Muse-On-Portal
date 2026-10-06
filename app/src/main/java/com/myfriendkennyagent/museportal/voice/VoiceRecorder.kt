package com.myfriendkennyagent.museportal.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.myfriendkennyagent.museportal.protocol.chat.VoiceNote
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Why a recording stopped. */
enum class StopReason { END_OF_SPEECH, TAPPED, MAX_LENGTH, NO_SPEECH, MIC_ERROR }

class Recording(val pcm: ByteArray, val reason: StopReason, val heardSpeech: Boolean)

/**
 * Records one utterance from the Portal's single-channel `handset-mic` at
 * 16 kHz, and finds the end of speech with an adaptive energy detector. The
 * far-field array behind "Hey Portal" needs a Meta-signed permission, so this
 * is the mic sideloaded apps get.
 */
class VoiceRecorder {
  @Volatile private var stopRequested = false

  /** Ends the current recording at the next frame. */
  fun stop() {
    stopRequested = true
  }

  /**
   * Records until the speaker stops talking, [stop] is called, or the voice
   * note limit. [onLevel] gets a 0..1 level per 20 ms frame, for the face.
   */
  @SuppressLint("MissingPermission") // checked by the caller before starting a turn
  suspend fun record(onLevel: (Float) -> Unit): Recording =
    withContext(Dispatchers.IO) {
      stopRequested = false
      val rate = VoiceNote.SAMPLE_RATE
      val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      val record =
        try {
          AudioRecord(
            // MIC routes to the handset-mic on Portal (verified by the community).
            MediaRecorder.AudioSource.MIC,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_BYTES * 8),
          )
        } catch (e: Exception) {
          Log.w(TAG, "AudioRecord failed: $e")
          return@withContext Recording(ByteArray(0), StopReason.MIC_ERROR, false)
        }
      if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        return@withContext Recording(ByteArray(0), StopReason.MIC_ERROR, false)
      }
      val out = ByteArrayOutputStream(VoiceNote.MAX_BYTES)
      val frame = ByteArray(FRAME_BYTES)
      val vad = EnergyVad()
      var reason = StopReason.MAX_LENGTH
      try {
        record.startRecording()
        while (currentCoroutineContext().isActive) {
          if (stopRequested) {
            reason = StopReason.TAPPED
            break
          }
          val n = readFully(record, frame)
          if (n < 0) {
            reason = StopReason.MIC_ERROR
            break
          }
          out.write(frame, 0, n)
          val level = vad.feed(rms(frame, n))
          onLevel(level)
          if (vad.endOfSpeech) {
            reason = StopReason.END_OF_SPEECH
            break
          }
          if (!vad.heardSpeech && out.size() >= NO_SPEECH_GIVE_UP_BYTES) {
            reason = StopReason.NO_SPEECH
            break
          }
          if (out.size() >= VoiceNote.MAX_BYTES) break
        }
      } finally {
        try {
          record.stop()
        } catch (e: IllegalStateException) {}
        record.release()
      }
      Recording(out.toByteArray(), reason, vad.heardSpeech)
    }

  private fun readFully(record: AudioRecord, buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
      val n = record.read(buf, off, buf.size - off)
      if (n < 0) return if (off > 0) off else n
      if (n == 0) break
      off += n
    }
    return off
  }

  private fun rms(frame: ByteArray, n: Int): Double {
    val samples = ByteBuffer.wrap(frame, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var sum = 0.0
    var count = 0
    while (samples.hasRemaining()) {
      val s = samples.get().toDouble()
      sum += s * s
      count++
    }
    return if (count == 0) 0.0 else sqrt(sum / count)
  }

  companion object {
    private const val TAG = "MuseRecorder"
    const val FRAME_MS = 20
    const val FRAME_BYTES = VoiceNote.SAMPLE_RATE * 2 * FRAME_MS / 1000
    /** Give up if nobody starts talking within 6 s. */
    const val NO_SPEECH_GIVE_UP_BYTES = VoiceNote.SAMPLE_RATE * 2 * 6
  }
}

/**
 * Adaptive energy endpointing: tracks the noise floor, calls a frame speech
 * when it is well above it, and ends the utterance after a pause.
 */
class EnergyVad(
  private val trailingSilenceFrames: Int = 1200 / VoiceRecorder.FRAME_MS,
  private val minSpeechFrames: Int = 6,
) {
  private var floor = 200.0
  private var speechFrames = 0
  private var silenceFrames = 0
  private var frames = 0

  var heardSpeech = false
    private set

  var endOfSpeech = false
    private set

  /** Feeds one frame's RMS; returns a 0..1 display level. */
  fun feed(rms: Double): Float {
    frames++
    val threshold = maxOf(floor * 3.0, 450.0)
    val speech = rms > threshold
    if (!speech) {
      // Follow the floor down quickly and up slowly.
      floor = if (rms < floor) floor * 0.9 + rms * 0.1 else floor * 0.995 + rms * 0.005
    }
    if (speech) {
      speechFrames++
      silenceFrames = 0
      if (speechFrames >= minSpeechFrames) heardSpeech = true
    } else if (heardSpeech) {
      silenceFrames++
      if (silenceFrames >= trailingSilenceFrames) endOfSpeech = true
    }
    return (rms / 4000.0).coerceIn(0.0, 1.0).toFloat()
  }
}

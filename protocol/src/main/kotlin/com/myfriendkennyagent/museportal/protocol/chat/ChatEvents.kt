package com.myfriendkennyagent.museportal.protocol.chat

import java.io.ByteArrayOutputStream
import org.json.JSONException
import org.json.JSONObject

/**
 * One line of the `POST /chat/subscribe` NDJSON stream. Field names follow
 * the ESP32 firmware's parser (`muse_chat_link.c`, `muse_chat_session.cpp`).
 */
data class ChatEvent(
  /** "event" for chat events; anything else is the subscription ack. */
  val type: String,
  val seq: Long,
  /** e.g. `delta.message_start`, `delta.text_append`, `delta.message_done`, `message.assistant`, `message.user`, `agent.status`. */
  val event: String,
  val messageId: String,
  val replyTo: String,
  /** The text of an append delta. */
  val appendText: String,
  /** A finished message's text: `display_text`, else `content`. */
  val fullText: String,
  /** False while a persisted message's display text is still being prepared. */
  val displayTextReady: Boolean,
  val activityCode: String?,
  val status: String?,
) {
  val isEvent: Boolean
    get() = type == "event"

  companion object {
    fun parse(line: String): ChatEvent? {
      val root =
        try {
          JSONObject(line)
        } catch (e: JSONException) {
          return null
        }
      val payload = root.optJSONObject("payload") ?: JSONObject()
      val id =
        payload.optString("message_id").ifEmpty { root.optString("message_id") }.ifEmpty { payload.optString("id") }
      val replyTo = payload.optString("reply_to_message_id").ifEmpty { payload.optString("parent_message_id") }
      val full = payload.optString("display_text").ifEmpty { payload.optString("content") }
      return ChatEvent(
        type = root.optString("type"),
        seq = root.optLong("seq", 0),
        event = root.optString("event").ifEmpty { root.optString("event_name") },
        messageId = id,
        replyTo = replyTo,
        appendText = payload.optString("text"),
        fullText = full.ifEmpty { if (root.optString("event") == "message.user") payload.optString("text") else "" },
        displayTextReady = payload.optBoolean("display_text_ready", true),
        activityCode = payload.opt("activity_code") as? String,
        status = payload.opt("status") as? String,
      )
    }
  }
}

/** Splits a byte stream into UTF-8 lines. Oversize lines are dropped. */
class NdjsonSplitter(private val maxLine: Int = 256 * 1024) {
  private val buf = ByteArrayOutputStream()
  private var skipping = false

  fun feed(data: ByteArray): List<String> {
    val lines = ArrayList<String>()
    for (b in data) {
      if (b == '\n'.code.toByte()) {
        if (!skipping && buf.size() > 0) lines += String(buf.toByteArray(), Charsets.UTF_8).trimEnd('\r')
        buf.reset()
        skipping = false
      } else if (!skipping) {
        buf.write(b.toInt())
        if (buf.size() > maxLine) {
          buf.reset()
          skipping = true
        }
      }
    }
    return lines
  }
}

package com.myfriendkennyagent.museportal.protocol.chat

/** What the Portal should show or say as chat events arrive. */
sealed class ChatUpdate {
  /** The transcript of this device's voice note. */
  data class Heard(val text: String) : ChatUpdate()

  /** A reply still streaming in: its text so far, for captions. */
  data class Partial(val messageId: String, val text: String) : ChatUpdate()

  /** A finished assistant message in the current turn. */
  data class Reply(val messageId: String, val text: String) : ChatUpdate()

  /** Muse started or stopped working on something (tools, tasks). */
  data class Busy(val busy: Boolean) : ChatUpdate()

  data class TurnEnded(val reason: EndReason) : ChatUpdate()

  /**
   * An assistant message outside a turn that belongs to this device: a late
   * reply to an earlier turn (an agentic task finishing), or a message with
   * no parent, such as a scheduled check-in.
   */
  data class Announcement(val messageId: String, val text: String) : ChatUpdate()
}

enum class EndReason { SETTLED, NO_REPLY, TOO_LONG, CANCELLED }

/**
 * Correlates the subscription stream with this device's turns, after the
 * ESP32 firmware: replies are the assistant messages whose parent is the
 * turn's user message (or another reply in the turn), a turn settles once
 * every message is done and nothing has arrived for a few seconds, and a busy
 * agent keeps it open. Not thread-safe: call from one thread or under a lock.
 */
class ChatTracker(
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val settleMs: Long = 3_000,
  private val replyTimeoutMs: Long = 60_000,
  private val busyHoldMs: Long = 60_000,
  private val turnCapMs: Long = 180_000,
  private val emit: (ChatUpdate) -> Unit,
) {
  private class Msg(val id: String) {
    val text = StringBuilder()
    var done = false
  }

  private var active = false
  private var acked = false
  private var startedAt = 0L
  private var lastEventAt = 0L
  private var agentBusy = false
  private var heard = false
  private val userIds = LinkedHashSet<String>()
  private val msgs = LinkedHashMap<String, Msg>()
  private val early = ArrayList<ChatEvent>()

  private var lastSeq = 0L
  private val rejected = Bounded(64)
  private val foreignUserIds = Bounded(64)
  private val ownIds = Bounded(64)
  private val live = LinkedHashMap<String, Msg>()

  val turnActive: Boolean
    get() = active

  /** Call just before posting the user's message. */
  fun beginTurn() {
    if (active) end(EndReason.CANCELLED)
    active = true
    acked = false
    heard = false
    startedAt = clock()
    lastEventAt = startedAt
    agentBusy = false
    userIds.clear()
    msgs.clear()
    early.clear()
  }

  /** The `/chat/stream` ack named this turn's user message (and maybe its parent). */
  fun onAck(messageId: String?, replyToId: String?) {
    if (!active) return
    listOfNotNull(messageId, replyToId).filter { it.isNotEmpty() }.forEach {
      userIds += it
      ownIds.add(it)
    }
    acked = true
    val buffered = early.toList()
    early.clear()
    buffered.forEach { handle(it) }
  }

  fun cancelTurn() {
    if (active) end(EndReason.CANCELLED)
  }

  fun onEvent(e: ChatEvent) {
    if (!e.isEvent) return
    if (e.seq > 0) {
      if (e.seq <= lastSeq) return
      lastSeq = e.seq
    }
    if (active && !acked && e.event != "agent.status" && e.event != "task.status") {
      if (early.size < 64) early += e
      return
    }
    handle(e)
  }

  /** Evaluates timeouts; call a few times a second. */
  fun tick() {
    if (!active) return
    val now = clock()
    val busyHolding = agentBusy && now - lastEventAt < busyHoldMs
    when {
      now - startedAt > turnCapMs -> end(EndReason.TOO_LONG)
      msgs.isEmpty() && now - startedAt > replyTimeoutMs && !busyHolding -> end(EndReason.NO_REPLY)
      msgs.isNotEmpty() && msgs.values.all { it.done } && now - lastEventAt >= settleMs && !busyHolding ->
        end(EndReason.SETTLED)
    }
  }

  private fun handle(e: ChatEvent) {
    when (e.event) {
      "agent.status",
      "task.status" -> {
        val busy =
          when {
            e.activityCode != null -> e.activityCode.isNotEmpty() && e.activityCode != "online" && e.activityCode != "idle"
            e.status != null -> e.status.isNotEmpty() && e.status != "completed" && e.status != "failed"
            else -> return
          }
        if (active) {
          lastEventAt = clock()
          if (busy != agentBusy) emit(ChatUpdate.Busy(busy))
        }
        agentBusy = busy
      }
      "message.user" -> onUserMessage(e)
      "delta.message_start",
      "delta.text_append",
      "delta.message_done",
      "message.assistant" -> if (active) onTurnMessage(e) else onOutsideMessage(e)
    }
  }

  private fun onUserMessage(e: ChatEvent) {
    if (e.messageId.isEmpty()) return
    if (active && e.messageId in userIds) {
      // The row reads "<transcript>\n[file:audio/wav ...]", or "[Voice note]" before transcription.
      val heardText = e.fullText.substringBefore("\n[file:").trim()
      if (heardText.isNotEmpty() && heardText != "[Voice note]" && !heard) {
        heard = true
        emit(ChatUpdate.Heard(heardText))
      }
      lastEventAt = clock()
    } else if (!ownIds.contains(e.messageId)) {
      foreignUserIds.add(e.messageId)
    }
  }

  private fun onTurnMessage(e: ChatEvent) {
    val id = e.messageId.ifEmpty { return }
    if (rejected.contains(id)) return
    var m = msgs[id]
    if (m == null) {
      val parent = e.replyTo
      if (parent.isNotEmpty() && parent !in userIds && parent !in msgs) {
        // Once the ack names our message, replies to anything else are someone else's.
        rejected.add(id)
        return
      }
      if (msgs.size >= MAX_MSGS) return
      m = Msg(id).also { msgs[id] = it }
      ownIds.add(id)
    }
    lastEventAt = clock()
    applyDelta(m, e)?.let { emit(ChatUpdate.Reply(id, it)) }
  }

  private fun onOutsideMessage(e: ChatEvent) {
    val id = e.messageId.ifEmpty { return }
    if (rejected.contains(id)) return
    val parent = e.replyTo
    val ours = parent.isEmpty() || ownIds.contains(parent)
    if (!ours || foreignUserIds.contains(parent)) {
      rejected.add(id)
      return
    }
    val m =
      live[id]
        ?: run {
          // Only messages seen starting live: a reconnect must not replay old history aloud.
          if (e.event != "delta.message_start" && e.event != "delta.text_append") return
          Msg(id).also {
            live[id] = it
            ownIds.add(id)
            while (live.size > MAX_MSGS) live.remove(live.keys.first())
          }
        }
    applyDelta(m, e)?.let {
      live.remove(id)
      emit(ChatUpdate.Announcement(id, it))
    }
  }

  /** Applies a delta; returns the final text once the message is done. */
  private fun applyDelta(m: Msg, e: ChatEvent): String? {
    when (e.event) {
      "delta.message_start" -> {}
      "delta.text_append" -> {
        if (e.appendText.isNotEmpty() && !m.done) {
          m.text.append(e.appendText)
          if (active) emit(ChatUpdate.Partial(m.id, m.text.toString()))
        }
      }
      "delta.message_done",
      "message.assistant" -> {
        if (m.done) return null
        if (e.event == "message.assistant" && !e.displayTextReady) return null
        val text = e.fullText.ifEmpty { m.text.toString() }.trim()
        m.done = true
        if (text.isNotEmpty()) return text
      }
    }
    return null
  }

  private fun end(reason: EndReason) {
    active = false
    early.clear()
    emit(ChatUpdate.TurnEnded(reason))
  }

  private class Bounded(private val cap: Int) {
    private val set = LinkedHashSet<String>()

    fun add(id: String) {
      set.remove(id)
      set.add(id)
      while (set.size > cap) set.remove(set.first())
    }

    fun contains(id: String) = id in set
  }

  companion object {
    const val MAX_MSGS = 8
  }
}

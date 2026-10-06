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

  /**
   * The turn is over. [sawOwnMessage] says whether Muse acknowledged the
   * message at all (its transcript or any reply arrived): a turn that ends
   * without it suggests replies aren't reaching this device.
   */
  data class TurnEnded(val reason: EndReason, val sawOwnMessage: Boolean = false) : ChatUpdate()

  /**
   * An assistant message outside a turn that belongs to this device: a late
   * reply to an earlier turn (an agentic task finishing), or a message with
   * no parent, such as a scheduled check-in. Never a reply in a conversation
   * that started elsewhere, such as the phone app.
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
  /** This device's side chat, if it posts to one: events from other sessions are ignored. */
  private val ownSessionId: () -> String? = { null },
  /** After a message from another client, parentless replies are assumed to answer it for this long. */
  private val foreignQuietMs: Long = 120_000,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val settleMs: Long = 3_000,
  private val replyTimeoutMs: Long = 60_000,
  private val busyHoldMs: Long = 60_000,
  private val turnCapMs: Long = 180_000,
  /** After the subscription (re)opens, whole messages without live deltas may be history being replayed. */
  private val replayGuardMs: Long = 5_000,
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
  private var sawOwn = false
  private val userIds = LinkedHashSet<String>()
  private val msgs = LinkedHashMap<String, Msg>()
  private val early = ArrayList<ChatEvent>()

  private var lastSeq = 0L
  private var lastForeignUserAt = Long.MIN_VALUE / 2
  private val rejected = Bounded(64)
  private val foreignUserIds = Bounded(64)
  private val ownIds = Bounded(64)
  private val live = LinkedHashMap<String, Msg>()
  /** Messages already spoken (as a reply or an announcement), so a later copy is never spoken twice. */
  private val spoken = Bounded(128)
  private var subscribedAt = Long.MIN_VALUE / 2

  val turnActive: Boolean
    get() = active

  /** Call just before posting the user's message. */
  fun beginTurn() {
    if (active) end(EndReason.CANCELLED)
    active = true
    acked = false
    heard = false
    sawOwn = false
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

  /** Call when the subscription stream (re)opens. */
  fun onSubscribed() {
    subscribedAt = clock()
  }

  fun onEvent(e: ChatEvent) {
    if (!e.isEvent) return
    val own = ownSessionId()
    if (own != null && e.sessionId.isNotEmpty() && e.sessionId != own) return
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
      sawOwn = true
      // The row reads "<transcript>\n[file:audio/wav ...]", or "[Voice note]" before transcription.
      val heardText = e.fullText.substringBefore("\n[file:").trim()
      if (heardText.isNotEmpty() && heardText != "[Voice note]" && !heard) {
        heard = true
        emit(ChatUpdate.Heard(heardText))
      }
      lastEventAt = clock()
    } else if (!ownIds.contains(e.messageId)) {
      foreignUserIds.add(e.messageId)
      lastForeignUserAt = clock()
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
    sawOwn = true
    lastEventAt = clock()
    applyDelta(m, e)?.let {
      spoken.add(id)
      emit(ChatUpdate.Reply(id, it))
    }
  }

  private fun onOutsideMessage(e: ChatEvent) {
    val id = e.messageId.ifEmpty { return }
    // The streamed message and its persisted copy share an id: speak it once.
    if (rejected.contains(id) || spoken.contains(id)) return
    val parent = e.replyTo
    // A parentless message right after someone used Muse elsewhere is most
    // likely that conversation's reply: stay quiet rather than narrate it.
    val parentlessButBusyElsewhere = parent.isEmpty() && clock() - lastForeignUserAt < foreignQuietMs
    val ours = (parent.isEmpty() && !parentlessButBusyElsewhere) || ownIds.contains(parent)
    if (!ours || foreignUserIds.contains(parent)) {
      rejected.add(id)
      return
    }
    val m =
      live[id]
        ?: run {
          val streaming = e.event == "delta.message_start" || e.event == "delta.text_append"
          // A whole message with no live deltas is fine too (proactive messages may
          // arrive that way), except just after the subscription opened, when it
          // may be history being replayed: a reconnect must not read old news aloud.
          if (!streaming && clock() - subscribedAt < replayGuardMs) return
          Msg(id).also {
            ownIds.add(id)
            if (streaming) {
              live[id] = it
              while (live.size > MAX_MSGS) live.remove(live.keys.first())
            }
          }
        }
    applyDelta(m, e)?.let {
      live.remove(id)
      spoken.add(id)
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
    emit(ChatUpdate.TurnEnded(reason, sawOwn))
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

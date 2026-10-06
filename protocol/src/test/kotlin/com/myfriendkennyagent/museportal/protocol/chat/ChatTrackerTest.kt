package com.myfriendkennyagent.museportal.protocol.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTrackerTest {
  private var now = 0L
  private val updates = ArrayList<ChatUpdate>()
  private var sideChat: String? = null
  private val tracker = ChatTracker(ownSessionId = { sideChat }, clock = { now }, emit = { updates += it })
  private var seq = 0L

  private fun event(name: String, payload: JSONObject) {
    val line = JSONObject().put("type", "event").put("seq", ++seq).put("event", name).put("payload", payload).toString()
    tracker.onEvent(ChatEvent.parse(line)!!)
  }

  private fun delta(name: String, id: String, parent: String = "", text: String = "") {
    val p = JSONObject().put("message_id", id)
    if (parent.isNotEmpty()) p.put("reply_to_message_id", parent)
    if (text.isNotEmpty()) p.put(if (name == "delta.text_append") "text" else "display_text", text)
    event(name, p)
  }

  @Test
  fun `a voice turn hears, streams, replies and settles`() {
    tracker.beginTurn()
    // Events can arrive before the ack; they wait for it.
    delta("delta.message_start", "a1", parent = "u1")
    tracker.onAck("u1", "p0")
    event("message.user", JSONObject().put("message_id", "u1").put("content", "[Voice note]"))
    event("message.user", JSONObject().put("message_id", "u1").put("content", "what's the weather\n[file:audio/wav voice_note.wav]"))
    delta("delta.text_append", "a1", parent = "u1", text = "It's ")
    delta("delta.text_append", "a1", parent = "u1", text = "sunny.")
    delta("delta.message_done", "a1", parent = "u1")
    assertTrue(tracker.turnActive)
    now += 3_000
    tracker.tick()
    assertFalse(tracker.turnActive)
    assertEquals(
      listOf(
        ChatUpdate.Heard("what's the weather"),
        ChatUpdate.Partial("a1", "It's "),
        ChatUpdate.Partial("a1", "It's sunny."),
        ChatUpdate.Reply("a1", "It's sunny."),
        ChatUpdate.TurnEnded(EndReason.SETTLED, sawOwnMessage = true),
      ),
      updates,
    )
  }

  @Test
  fun `replies to someone else are ignored`() {
    tracker.beginTurn()
    tracker.onAck("u1", null)
    delta("delta.message_start", "other", parent = "phone-msg")
    delta("delta.message_done", "other", parent = "phone-msg", text = "not for us")
    delta("delta.message_done", "a1", parent = "u1", text = "for us")
    assertEquals(listOf(ChatUpdate.Reply("a1", "for us")), updates)
  }

  @Test
  fun `a busy agent holds the turn open, then a late reply is announced`() {
    tracker.beginTurn()
    tracker.onAck("u1", null)
    event("agent.status", JSONObject().put("activity_code", "working"))
    now += 61_000
    event("agent.status", JSONObject().put("activity_code", "working"))
    tracker.tick()
    assertTrue(tracker.turnActive)
    now += 61_000
    tracker.tick()
    assertFalse(tracker.turnActive)
    assertEquals(ChatUpdate.TurnEnded(EndReason.NO_REPLY), updates.last())
    updates.clear()
    // The agent finishes long after: the reply to our message is still ours.
    delta("delta.message_start", "a9", parent = "u1")
    delta("delta.message_done", "a9", parent = "u1", text = "Done: your files are backed up.")
    assertEquals(listOf(ChatUpdate.Announcement("a9", "Done: your files are backed up.")), updates)
  }

  @Test
  fun `parentless live messages are announced, replays and foreign replies are not`() {
    tracker.onSubscribed()
    // A persisted message replayed as the subscription opens is not spoken.
    event("message.assistant", JSONObject().put("message_id", "old").put("content", "old news"))
    // A phone conversation: the user message is seen, its reply is not ours.
    event("message.user", JSONObject().put("message_id", "phone-1").put("content", "hi from phone"))
    delta("delta.message_start", "r1", parent = "phone-1")
    delta("delta.message_done", "r1", parent = "phone-1", text = "hello phone")
    // A scheduled check-in with no parent, once the phone conversation is quiet.
    now += 121_000
    delta("delta.message_start", "c1")
    delta("delta.text_append", "c1", text = "Good morning! ")
    delta("delta.message_done", "c1")
    assertEquals(listOf(ChatUpdate.Announcement("c1", "Good morning!")), updates)
  }

  @Test
  fun `a parentless reply right after a phone message is not narrated`() {
    now = 1_000_000
    event("message.user", JSONObject().put("message_id", "phone-2").put("content", "what's on today?"))
    delta("delta.message_start", "r2")
    delta("delta.message_done", "r2", text = "Three meetings.")
    assertEquals(emptyList<ChatUpdate>(), updates)
    // Long after, a parentless message is a check-in again.
    now += 121_000
    delta("delta.message_start", "c2")
    delta("delta.message_done", "c2", text = "Your build finished.")
    assertEquals(listOf(ChatUpdate.Announcement("c2", "Your build finished.")), updates)
  }

  @Test
  fun `events from other sessions are ignored when using a side chat`() {
    sideChat = "portal-chat"
    tracker.beginTurn()
    tracker.onAck("u1", null)
    val other =
      """{"type":"event","seq":1,"event":"delta.message_done","session_id":"main","payload":{"message_id":"x","display_text":"phone stuff"}}"""
    val mine =
      """{"type":"event","seq":2,"event":"delta.message_done","payload":{"message_id":"a1","reply_to_message_id":"u1","display_text":"portal stuff","session_id":"portal-chat"}}"""
    tracker.onEvent(ChatEvent.parse(other)!!)
    tracker.onEvent(ChatEvent.parse(mine)!!)
    assertEquals(listOf(ChatUpdate.Reply("a1", "portal stuff")), updates)
  }

  @Test
  fun `a whole proactive message without deltas is announced once`() {
    tracker.onSubscribed()
    now += 6_000 // past the replay guard
    event("message.assistant", JSONObject().put("message_id", "g1").put("content", "A new paid gig just landed."))
    event("message.assistant", JSONObject().put("message_id", "g1").put("content", "A new paid gig just landed."))
    delta("delta.message_done", "g1", text = "A new paid gig just landed.")
    assertEquals(listOf(ChatUpdate.Announcement("g1", "A new paid gig just landed.")), updates)
  }

  @Test
  fun `a message not ready for display waits for the ready copy`() {
    event(
      "message.assistant",
      JSONObject().put("message_id", "g2").put("content", "draft").put("display_text_ready", false),
    )
    assertEquals(emptyList<ChatUpdate>(), updates)
    event("message.assistant", JSONObject().put("message_id", "g2").put("display_text", "Final text."))
    assertEquals(listOf(ChatUpdate.Announcement("g2", "Final text.")), updates)
  }

  @Test
  fun `a streamed message and its persisted copy are spoken once`() {
    delta("delta.message_start", "c3")
    delta("delta.text_append", "c3", text = "Backup finished.")
    delta("delta.message_done", "c3")
    event("message.assistant", JSONObject().put("message_id", "c3").put("content", "Backup finished."))
    assertEquals(listOf(ChatUpdate.Announcement("c3", "Backup finished.")), updates)
  }

  @Test
  fun `a turn reply is not announced again when its persisted copy arrives`() {
    tracker.beginTurn()
    tracker.onAck("u1", null)
    delta("delta.message_done", "a1", parent = "u1", text = "Sure.")
    now += 3_000
    tracker.tick()
    event("message.assistant", JSONObject().put("message_id", "a1").put("reply_to_message_id", "u1").put("content", "Sure."))
    assertEquals(
      listOf(ChatUpdate.Reply("a1", "Sure."), ChatUpdate.TurnEnded(EndReason.SETTLED, sawOwnMessage = true)),
      updates,
    )
  }

  @Test
  fun `duplicate sequence numbers are dropped`() {
    tracker.beginTurn()
    tracker.onAck("u1", null)
    val line =
      """{"type":"event","seq":10,"event":"delta.message_done","payload":{"message_id":"a1","reply_to_message_id":"u1","display_text":"once"}}"""
    tracker.onEvent(ChatEvent.parse(line)!!)
    tracker.onEvent(ChatEvent.parse(line)!!)
    assertEquals(listOf(ChatUpdate.Reply("a1", "once")), updates)
  }

  @Test
  fun `no reply times out`() {
    tracker.beginTurn()
    tracker.onAck("u1", null)
    now += 60_001
    tracker.tick()
    assertEquals(listOf(ChatUpdate.TurnEnded(EndReason.NO_REPLY)), updates)
  }

  @Test
  fun `ndjson splitter handles split lines`() {
    val s = NdjsonSplitter()
    assertEquals(emptyList<String>(), s.feed("""{"a":""".toByteArray()))
    assertEquals(listOf("""{"a":1}""", "x"), s.feed("1}\r\nx\n\n".toByteArray()))
  }

  @Test
  fun `voice note wav header`() {
    val wav = VoiceNote.wav(ByteArray(320))
    assertEquals("RIFF", String(wav, 0, 4))
    assertEquals("WAVE", String(wav, 8, 4))
    assertEquals(364, wav.size)
    val body = VoiceNote.chatBody(wav, "side-1")
    assertEquals("", body.getString("message"))
    assertEquals("side-1", body.getString("session_id"))
    assertEquals("voice_note.wav", body.getJSONArray("items").getJSONObject(0).getString("filename"))
  }
}

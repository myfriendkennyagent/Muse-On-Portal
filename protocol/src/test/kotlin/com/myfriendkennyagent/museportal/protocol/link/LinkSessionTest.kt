package com.myfriendkennyagent.museportal.protocol.link

import com.myfriendkennyagent.museportal.protocol.chat.ChatEvent
import com.myfriendkennyagent.museportal.protocol.chat.VoiceNote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSessionTest {
  private val commands =
    listOf(
        CommandSpec("display.show_text", "Show text.", required = listOf(Param("text", "string", "What to show."))),
        CommandSpec("device.health", "Health.", timeoutMs = 5000),
      )
      .toCommandsJson()

  private fun device() = DeviceDescription("homelink-abc123", "Kitchen Portal", "0.1.0", commands)

  private val runner = CommandRunner { command, params, _ ->
    when (command) {
      "display.show_text" -> CommandResult.ok(JSONObject().put("shown", params.getString("text")))
      "boom" -> throw IllegalStateException("kaput")
      else -> CommandResult.error("unknown command: $command")
    }
  }

  @Test
  fun `registers, runs commands and posts chat`(): Unit = runBlocking {
    withTimeout(10_000) {
      val vm = FakeVm(this)
      val registered = CompletableDeferred<LinkSession>()
      val session = LinkSession("vm.example", "vm 1/a", "bearer-xyz", device(), runner, vm.connector) { registered.complete(it) }
      val run = async { session.run() }

      val register = vm.controlMessages.receive()
      assertEquals("link.register", register.getString("method"))
      val params = register.getJSONObject("params")
      assertEquals("homelink-abc123", params.getString("node_id"))
      assertEquals("linux", params.getString("platform"))
      assertEquals("homehub", params.getString("device_family"))
      assertFalse(params.getBoolean("is_wakeup_supported"))
      assertEquals("Show text.", params.getJSONObject("commands_v2").getJSONObject("display.show_text").getString("description"))
      assertEquals(5000, params.getJSONObject("commands_v2").getJSONObject("device.health").getInt("timeout_ms"))
      assertEquals("wss://vm.example/v1/noise?vm_id=vm%201%2Fa", vm.connectedUrls.single())
      assertEquals("Bearer bearer-xyz", vm.connectedHeaders.single()["Authorization"])

      registered.await()
      assertNotNull(session.registeredAt)

      vm.invoke("inv-1", "display.show_text", JSONObject().put("text", "hello"))
      val result = vm.controlMessages.receive()
      assertEquals("link.result", result.getString("method"))
      assertEquals("inv-1", result.getString("id"))
      assertTrue(result.getBoolean("ok"))
      assertEquals("hello", result.getJSONObject("payload").getString("shown"))

      vm.invoke("inv-2", "boom")
      val failed = vm.controlMessages.receive()
      assertFalse(failed.getBoolean("ok"))
      assertTrue(failed.getString("error").contains("kaput"))

      val ack = session.sendChat(VoiceNote.textBody("hi muse"))
      assertTrue(ack.ok)
      assertEquals("user-1", ack.json()!!.getString("message_id"))
      val posted = vm.chatBodies.receive()
      assertEquals("hi muse", posted.getString("message"))
      assertEquals("text", posted.getString("output_modality"))
      assertEquals("homelink-abc123", posted.getString("device_id"))

      // A voice note big enough to go up in several body chunks.
      val pcm = ByteArray(VoiceNote.SAMPLE_RATE * 2 * 3) { (it % 7).toByte() }
      val noteAck = session.sendChat(VoiceNote.chatBody(VoiceNote.wav(pcm)))
      assertTrue(noteAck.ok)
      val note = vm.chatBodies.receive()
      val item = note.getJSONArray("items").getJSONObject(0)
      assertEquals("audio/wav", item.getString("mime_type"))
      val wav = java.util.Base64.getDecoder().decode(item.getString("data_base64"))
      assertEquals(44 + pcm.size, wav.size)

      vm.sendControl(JSONObject().put("event", "link.unpaired"))
      assertEquals(Outcome.UNPAIRED, run.await())
      assertTrue(session.closed)
    }
  }

  @Test
  fun `upgrade rejections map to outcomes`(): Unit = runBlocking {
    val vm = FakeVm(this)
    vm.rejectUpgradeWith = 401
    assertEquals(Outcome.AUTH_REJECTED, LinkSession("h", "v", "t", device(), runner, vm.connector).run())
    vm.rejectUpgradeWith = 403
    assertEquals(Outcome.FORBIDDEN, LinkSession("h", "v", "t", device(), runner, vm.connector).run())
  }

  @Test
  fun `closed connection ends the session`(): Unit = runBlocking {
    withTimeout(10_000) {
      val vm = FakeVm(this)
      val session = LinkSession("h", "v", "t", device(), runner, vm.connector)
      val run = async { session.run() }
      vm.controlMessages.receive()
      vm.hangUp()
      assertEquals(Outcome.CLOSED, run.await())
    }
  }

  @Test
  fun `subscription stream delivers chat events`(): Unit = runBlocking {
    withTimeout(10_000) {
      val vm = FakeVm(this)
      val events = Channel<ChatEvent>(Channel.UNLIMITED)
      val registered = CompletableDeferred<LinkSession>()
      val session = LinkSession("h", "v", "t", device(), runner, vm.connector) { registered.complete(it) }
      val run = async { session.run() }
      registered.await()
      val splitter = com.myfriendkennyagent.museportal.protocol.chat.NdjsonSplitter()
      session.openStream(
        "POST",
        LinkSession.SUBSCRIBE_PATH,
        LinkSession.jsonHeaders("application/x-ndjson"),
        object : StreamListener {
          override fun onData(data: ByteArray) {
            splitter.feed(data).mapNotNull(ChatEvent::parse).forEach { events.trySend(it) }
          }
        },
        "{}".toByteArray(),
        endBody = true,
      )
      assertEquals("subscribed", events.receive().type)
      vm.pushEvent(
        """{"type":"event","seq":4,"event":"delta.text_append","payload":{"message_id":"a1","reply_to_message_id":"user-1","text":"Hel"}}"""
      )
      val e = events.receive()
      assertEquals("delta.text_append", e.event)
      assertEquals("a1", e.messageId)
      assertEquals("user-1", e.replyTo)
      assertEquals("Hel", e.appendText)
      vm.hangUp()
      run.await()
    }
  }
}

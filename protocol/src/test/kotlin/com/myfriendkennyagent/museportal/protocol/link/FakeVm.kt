package com.myfriendkennyagent.museportal.protocol.link

import com.myfriendkennyagent.museportal.protocol.noise.ApplicationRequest
import com.myfriendkennyagent.museportal.protocol.noise.ApplicationResponse
import com.myfriendkennyagent.museportal.protocol.noise.BodyChunk
import com.myfriendkennyagent.museportal.protocol.noise.CipherState
import com.myfriendkennyagent.museportal.protocol.noise.NoiseFrameDecoder
import com.myfriendkennyagent.museportal.protocol.noise.NoiseFraming
import com.myfriendkennyagent.museportal.protocol.noise.NoiseTransport
import com.myfriendkennyagent.museportal.protocol.noise.NoiseXXResponder
import com.myfriendkennyagent.museportal.protocol.noise.Reset
import com.myfriendkennyagent.museportal.protocol.noise.ServiceFrame
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One end of an in-memory socket pair. */
class MemorySocket(private val outbox: Channel<ByteArray>, private val inbox: Channel<ByteArray>) : FrameSocket {
  override suspend fun send(data: ByteArray) {
    try {
      outbox.send(data)
    } catch (e: Exception) {
      throw java.io.IOException("closed")
    }
  }

  override suspend fun receive(): ByteArray? =
    try {
      inbox.receive()
    } catch (e: ClosedReceiveChannelException) {
      null
    }

  override fun close() {
    outbox.close()
    inbox.close()
  }
}

/**
 * A fake Muse VM: does a real Noise XX handshake, serves `/link-control`,
 * `/chat/stream` and `/chat/subscribe`, and records what the device sent.
 */
class FakeVm(private val scope: CoroutineScope) {
  val connectedUrls = ArrayList<String>()
  val connectedHeaders = ArrayList<Map<String, String>>()
  val controlMessages = Channel<JSONObject>(Channel.UNLIMITED)
  val chatBodies = Channel<JSONObject>(Channel.UNLIMITED)
  val subscribed = CompletableDeferred<Long>()
  var rejectUpgradeWith: Int? = null
  var chatAck: JSONObject = JSONObject().put("message_id", "user-1").put("reply_to_message_id", "parent-0")

  private lateinit var socket: MemorySocket
  private lateinit var send: CipherState
  private lateinit var recv: CipherState
  private val sendLock = Mutex()
  private var controlStream = 0L
  private val bodies = HashMap<Long, ByteArrayOutputStream>()
  private val paths = HashMap<Long, String>()

  val connector = SocketConnector { url, headers ->
    rejectUpgradeWith?.let { throw UpgradeRejected(it) }
    connectedUrls += url
    connectedHeaders += headers
    val toVm = Channel<ByteArray>(Channel.UNLIMITED)
    val toDevice = Channel<ByteArray>(Channel.UNLIMITED)
    socket = MemorySocket(toDevice, toVm)
    scope.launch { serve() }
    MemorySocket(toVm, toDevice)
  }

  private suspend fun serve() {
    val responder = NoiseXXResponder()
    responder.initialize()
    val msg1 = socket.receive() ?: return
    socket.send(responder.readMessage1AndWriteMessage2(msg1))
    val msg3 = socket.receive() ?: return
    responder.readMessage3(msg3)
    val (s, r) = responder.split()
    send = s
    recv = r
    val decoder = NoiseFrameDecoder()
    val control = MessageDecoder()
    while (true) {
      val raw = socket.receive() ?: return
      val plain = decoder.decode(recv.decryptWithAd(ByteArray(0), raw)) ?: continue
      val (_, frame) = NoiseTransport.decodeRequestEnvelope(plain)
      when (val v = frame.value) {
        is ApplicationRequest -> {
          paths[frame.streamId] = v.path
          when (v.path) {
            LinkSession.CONTROL_PATH -> controlStream = frame.streamId
            LinkSession.SUBSCRIBE_PATH -> {
              reply(frame.streamId, ApplicationResponse(200, body = "{\"type\":\"subscribed\"}\n".toByteArray()))
              subscribed.complete(frame.streamId)
            }
            else -> {
              bodies[frame.streamId] = ByteArrayOutputStream().apply { write(v.body) }
              if (v.endBody) finishRequest(frame.streamId)
            }
          }
        }
        is BodyChunk -> {
          if (frame.streamId == controlStream) {
            for (m in control.feed(v.data)) onControl(m)
          } else {
            bodies[frame.streamId]?.write(v.data)
            if (v.endBody) finishRequest(frame.streamId)
          }
        }
        is Reset -> bodies.remove(frame.streamId)
        else -> {}
      }
    }
  }

  private suspend fun onControl(message: JSONObject) {
    controlMessages.send(message)
    if (message.optString("method") == "link.register") {
      // The first frame on the control stream carries the response status.
      reply(controlStream, ApplicationResponse(200))
      sendControl(JSONObject().put("type", "res").put("id", message.getString("id")).put("result", JSONObject()))
    }
  }

  private suspend fun finishRequest(streamId: Long) {
    val body = bodies.remove(streamId)!!.toByteArray()
    if (paths[streamId] == LinkSession.CHAT_PATH) {
      chatBodies.send(JSONObject(String(body)))
      reply(streamId, ApplicationResponse(200, body = chatAck.toString().toByteArray(), endBody = true))
    } else {
      reply(streamId, ApplicationResponse(404, endBody = true))
    }
  }

  suspend fun reply(streamId: Long, value: com.myfriendkennyagent.museportal.protocol.noise.FrameValue) {
    sendLock.withLock {
      val envelope = NoiseTransport.encodeResponseEnvelope(ServiceFrame(streamId, value))
      for (frame in NoiseFraming.encodeFrames(envelope)) socket.send(send.encryptWithAd(ByteArray(0), frame))
    }
  }

  suspend fun sendControl(message: JSONObject) {
    reply(controlStream, BodyChunk(LinkSession.encodeMessage(message)))
  }

  suspend fun invoke(id: String, command: String, params: JSONObject = JSONObject()) {
    sendControl(
      JSONObject().put("type", "req").put("method", "link.invoke").put("id", id).put("command", command).put("params", params)
    )
  }

  suspend fun pushEvent(line: String) {
    reply(subscribed.await(), BodyChunk((line + "\n").toByteArray()))
  }

  fun hangUp() {
    socket.close()
  }
}

package com.myfriendkennyagent.museportal.protocol.link

import com.myfriendkennyagent.museportal.protocol.noise.ApplicationResponse
import com.myfriendkennyagent.museportal.protocol.noise.BodyChunk
import com.myfriendkennyagent.museportal.protocol.noise.DecryptedFrame
import com.myfriendkennyagent.museportal.protocol.noise.Header
import com.myfriendkennyagent.museportal.protocol.noise.NoiseTransport
import com.myfriendkennyagent.museportal.protocol.noise.NoiseXXInitiator
import com.myfriendkennyagent.museportal.protocol.noise.Reset
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/** What `link.register` tells the Muse about this device. */
class DeviceDescription(
  val nodeId: String,
  val displayName: String,
  val version: String,
  val commands: JSONObject,
  // The Linux SDK's known-good values. Never family "link" or a `device.ota`
  // command: the server pushes ESP32 firmware to every "link" device.
  val platform: String = "linux",
  val deviceFamily: String = "homehub",
  val modelId: String = "linux",
  val metadata: Map<String, String> = emptyMap(),
) {
  fun registerParams(): JSONObject {
    val params =
      JSONObject()
        .put("node_id", nodeId)
        .put("display_name", displayName)
        .put("platform", platform)
        .put("version", version)
        .put("device_family", deviceFamily)
        .put("model_id", modelId)
        .put("is_wakeup_supported", false)
        .put("commands_v2", commands)
    if (metadata.isNotEmpty()) params.put("metadata", JSONObject(metadata))
    return params
  }
}

enum class Outcome {
  /** The connection ended; reconnect normally. */
  CLOSED,
  /** The edge refused the VM bearer; fetch VMs again. */
  AUTH_REJECTED,
  /** Authenticated but not allowed right now. */
  FORBIDDEN,
  /** The Muse removed this device. */
  UNPAIRED,
}

/** Receives one request stream's response. Called on the session's read loop: keep it quick. */
interface StreamListener {
  fun onResponse(status: Int, headers: List<Header>) {}

  fun onData(data: ByteArray) {}

  fun onEnd() {}

  /** The stream was reset, or the session ended. */
  fun onError(reason: String) {}
}

class HttpResult(val status: Int, val body: ByteArray) {
  val ok: Boolean
    get() = status in 200..299

  fun json(): JSONObject? =
    try {
      JSONObject(String(body, Charsets.UTF_8))
    } catch (e: JSONException) {
      null
    }
}

/**
 * One connection to the Muse VM, ported from the Linux SDK's `link_client.py`.
 *
 * Opens a WebSocket to `/v1/noise` with the per-VM bearer, runs the Noise XX
 * handshake, then holds a long-lived `POST /link-control` stream: the device
 * sends `link.register` and a `link.result` per `link.invoke`. Other requests
 * (`/chat/stream`, `/chat/subscribe`) are multiplexed on the same session.
 */
class LinkSession(
  noiseHost: String,
  vmId: String,
  private val vmAuthToken: String,
  private val device: DeviceDescription,
  private val runner: CommandRunner,
  private val connector: SocketConnector,
  private val onRegistered: (LinkSession) -> Unit = {},
) {
  private val log = Logger.getLogger("LinkSession")
  val url = noiseUrl(noiseHost, vmId)

  private val sendLock = Mutex()
  private val invokes = Semaphore(MAX_CONCURRENT_INVOKES)
  private val streams = ConcurrentHashMap<Long, StreamListener>()
  private lateinit var socket: FrameSocket
  private lateinit var transport: NoiseTransport
  private lateinit var scope: CoroutineScope
  private var controlStreamId = 0L
  private var registerId = ""

  @Volatile
  var registeredAt: Long? = null
    private set

  @Volatile
  var closed = false
    private set

  /** Connects and serves until the connection ends. Cancel the caller to stop. */
  suspend fun run(): Outcome {
    socket =
      try {
        connector.connect(url, mapOf("Authorization" to "Bearer $vmAuthToken"))
      } catch (e: UpgradeRejected) {
        log.warning("VM refused connection: HTTP ${e.status}")
        return if (e.status == 401) Outcome.AUTH_REJECTED else Outcome.FORBIDDEN
      }
    try {
      transport =
        try {
          withTimeout(HANDSHAKE_TIMEOUT_MS) { handshake() }
        } catch (e: TimeoutCancellationException) {
          // A timeout is a failed connection, not a request to stop.
          throw IOException("Noise handshake timed out")
        }
      return coroutineScope {
        scope = this
        openControlStream()
        val outcome = readLoop()
        coroutineContext.cancelChildren()
        outcome
      }
    } finally {
      closed = true
      val pending = streams.values.toList()
      streams.clear()
      pending.forEach { it.onError("session ended") }
      socket.close()
    }
  }

  // -- Connection setup -------------------------------------------------------

  private suspend fun handshake(): NoiseTransport {
    val initiator = NoiseXXInitiator()
    initiator.initialize()
    socket.send(initiator.writeMessage1())
    val msg2 = socket.receive() ?: throw IOException("connection closed during the Noise handshake")
    initiator.readMessage2(msg2)
    // The bearer already authenticated us at the upgrade; message 3 carries an empty payload.
    socket.send(initiator.writeMessage3())
    val (send, recv) = initiator.split()
    log.info("Noise session established")
    return NoiseTransport(send, recv)
  }

  private suspend fun openControlStream() {
    controlStreamId = locked { t -> t.startStreamRequest("POST", CONTROL_PATH).let { it.streamId to it.frames } }
    registerId = UUID.randomUUID().toString()
    sendControl(
      JSONObject()
        .put("type", "req")
        .put("id", registerId)
        .put("method", "link.register")
        .put("params", device.registerParams())
    )
    log.info("sent link.register as ${device.nodeId}")
  }

  // -- Sending ----------------------------------------------------------------

  /** Encrypts and sends under one lock, so nonces reach the VM in order. */
  private suspend fun <T> locked(encrypt: (NoiseTransport) -> Pair<T, List<ByteArray>>): T =
    sendLock.withLock {
      if (closed) throw IOException("session closed")
      val (result, frames) = encrypt(transport)
      for (frame in frames) socket.send(frame)
      result
    }

  /** Sends one JSON message on the control stream. */
  suspend fun sendControl(message: JSONObject) {
    val data = encodeMessage(message)
    locked { t -> Unit to t.encryptBodyChunk(controlStreamId, data) }
  }

  /**
   * Opens a request stream. With [endBody] false the body follows through
   * [sendBody]. The listener is registered before anything is sent.
   */
  suspend fun openStream(
    verb: String,
    path: String,
    headers: List<Header>,
    listener: StreamListener,
    body: ByteArray = ByteArray(0),
    endBody: Boolean = false,
  ): Long =
    locked { t ->
      val frames =
        if (endBody) t.encryptHttpRequest(verb, path, body, headers) else t.startStreamRequest(verb, path, headers, body)
      streams[frames.streamId] = listener
      frames.streamId to frames.frames
    }

  suspend fun sendBody(streamId: Long, data: ByteArray, endBody: Boolean) {
    locked { t -> Unit to t.encryptBodyChunk(streamId, data, endBody) }
  }

  /** Cancels a request stream (for example a voice note the user abandoned). */
  suspend fun resetStream(streamId: Long, reason: String = "cancelled") {
    streams.remove(streamId)
    if (!closed) locked { t -> Unit to t.encryptReset(streamId, reason) }
  }

  /**
   * A whole request and its response. Bodies over [partBytes] go up as body
   * chunks after the request frame, as the ESP32 firmware streams voice notes.
   */
  suspend fun request(
    verb: String,
    path: String,
    headers: List<Header>,
    body: ByteArray,
    partBytes: Int = BODY_PART_BYTES,
    timeoutMs: Long = REQUEST_TIMEOUT_MS,
  ): HttpResult {
    val done = CompletableDeferred<HttpResult>()
    val collector =
      object : StreamListener {
        var status = 0
        val buf = ByteArrayOutputStream()

        override fun onResponse(status: Int, headers: List<Header>) {
          this.status = status
        }

        override fun onData(data: ByteArray) {
          buf.write(data)
          if (buf.size() > MAX_RESPONSE_BYTES) done.completeExceptionally(IOException("response too large"))
        }

        override fun onEnd() {
          done.complete(HttpResult(status, buf.toByteArray()))
        }

        override fun onError(reason: String) {
          done.completeExceptionally(IOException("stream reset: $reason"))
        }
      }
    val id: Long
    if (body.size <= partBytes) {
      id = openStream(verb, path, headers, collector, body, endBody = true)
    } else {
      id = openStream(verb, path, headers, collector)
      var off = 0
      while (off < body.size) {
        val end = minOf(off + partBytes, body.size)
        sendBody(id, body.copyOfRange(off, end), endBody = end == body.size)
        off = end
      }
    }
    return try {
      withTimeout(timeoutMs) { done.await() }
    } catch (e: TimeoutCancellationException) {
      throw IOException("request timed out: $verb $path")
    } finally {
      streams.remove(id)
    }
  }

  /**
   * Posts a user message to the Muse as coming from this device (`POST
   * /chat/stream`). [body] carries `message`, `output_modality` and any
   * `items`; `device_id` is set to this device so follow-up commands come
   * back here. The response is only the ack; replies arrive on the
   * subscription stream.
   */
  suspend fun sendChat(body: JSONObject): HttpResult {
    body.put("device_id", device.nodeId)
    return request("POST", CHAT_PATH, jsonHeaders(), body.toString().toByteArray(Charsets.UTF_8))
  }

  // -- Receiving --------------------------------------------------------------

  private suspend fun readLoop(): Outcome {
    val decoder = MessageDecoder()
    while (true) {
      val raw = socket.receive()
      if (raw == null) {
        log.info("control connection closed")
        return Outcome.CLOSED
      }
      val frame = transport.decryptFrame(raw) ?: continue
      if (frame.streamId != controlStreamId) {
        dispatchStream(frame)
        continue
      }
      val data: ByteArray
      val ended: Boolean
      when (val v = frame.value) {
        is Reset -> {
          log.warning("control stream reset: ${v.reason}")
          return Outcome.CLOSED
        }
        is ApplicationResponse -> {
          if (v.status >= 400) {
            log.warning("/link-control refused: HTTP ${v.status}")
            return if (v.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
          }
          data = v.body
          ended = v.endBody
        }
        is BodyChunk -> {
          data = v.data
          ended = v.endBody
        }
        else -> continue
      }
      for (message in decoder.feed(data)) {
        handle(message)?.let {
          return it
        }
      }
      if (ended) {
        log.info("control stream ended by VM")
        return Outcome.CLOSED
      }
    }
  }

  private fun dispatchStream(frame: DecryptedFrame) {
    val listener = streams[frame.streamId] ?: return
    when (val v = frame.value) {
      is ApplicationResponse -> {
        listener.onResponse(v.status, v.headers)
        if (v.body.isNotEmpty()) listener.onData(v.body)
        if (v.endBody) {
          streams.remove(frame.streamId)
          listener.onEnd()
        }
      }
      is BodyChunk -> {
        if (v.data.isNotEmpty()) listener.onData(v.data)
        if (v.endBody) {
          streams.remove(frame.streamId)
          listener.onEnd()
        }
      }
      is Reset -> {
        streams.remove(frame.streamId)
        listener.onError(v.reason.ifEmpty { "reset" })
      }
      else -> {}
    }
  }

  private fun handle(message: JSONObject): Outcome? {
    if (message.optString("id") == registerId && !message.has("method")) {
      if (message.has("error") && !message.isNull("error")) {
        log.severe("link.register rejected: ${message.opt("error")}")
      } else {
        registeredAt = System.nanoTime() / 1_000_000
        log.info("registered with the Muse")
        onRegistered(this)
      }
      return null
    }
    val event = message.optString("event")
    if (event == "link.unpaired" || event == "node.unpaired") {
      log.warning("the Muse removed this device")
      return Outcome.UNPAIRED
    }
    if (message.optString("method") == "link.invoke") {
      scope.launch { invoke(message) }
    }
    return null
  }

  private suspend fun invoke(message: JSONObject) {
    val id = message.optString("id")
    if (id.isEmpty()) return
    val command = message.optString("command")
    val params = message.optJSONObject("params") ?: JSONObject()
    val timeoutMs = message.optLong("timeout_ms", 0).takeIf { it > 0 }
    val shown = printable(command)
    log.info("invoke $shown")
    val started = System.nanoTime()
    val result =
      invokes.withPermit {
        try {
          withTimeoutOrNull(timeoutMs ?: DEFAULT_COMMAND_TIMEOUT_MS) { runner.run(command, params, timeoutMs) }
            ?: CommandResult.error("timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          CommandResult.error("${e.javaClass.simpleName}: ${e.message}")
        }
      }
    log.info("$shown ${if (result.optBoolean("ok")) "ok" else "failed"} in ${(System.nanoTime() - started) / 1_000_000} ms")
    val reply = JSONObject().put("method", "link.result").put("id", id)
    for (key in result.keys()) reply.put(key, result.get(key))
    try {
      sendControl(reply)
    } catch (e: IOException) {
      log.warning("could not send link.result: $e")
    }
  }

  companion object {
    const val NOISE_PATH = "/v1/noise"
    const val CONTROL_PATH = "/link-control"
    const val CHAT_PATH = "/chat/stream"
    const val SUBSCRIBE_PATH = "/chat/subscribe"
    const val APP_ID = "musegadget"
    const val REQUEST_TIMEOUT_MS = 60_000L
    const val HANDSHAKE_TIMEOUT_MS = 20_000L
    const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
    const val MAX_RESPONSE_BYTES = 1024 * 1024
    const val MAX_CONCURRENT_INVOKES = 4
    const val MAX_INBOUND_MESSAGE = 4 * 1024 * 1024
    /** Body chunk size for long uploads; frames stay well under the VM's limits. */
    const val BODY_PART_BYTES = 32 * 1024

    fun noiseUrl(noiseHost: String, vmId: String): String =
      "wss://$noiseHost$NOISE_PATH?vm_id=${encodeUriComponent(vmId)}"

    /** Matches JavaScript's encodeURIComponent, as the firmware does. */
    fun encodeUriComponent(s: String): String =
      URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~")

    fun jsonHeaders(accept: String? = null): List<Header> =
      buildList {
        add(Header("Content-Type", "application/json"))
        add(Header("x-request-id", UUID.randomUUID().toString()))
        add(Header("x-app-id", APP_ID))
        accept?.let { add(Header("accept", it)) }
      }

    fun encodeMessage(message: JSONObject): ByteArray {
      val data = message.toString().toByteArray(Charsets.UTF_8)
      return ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).put(data).array()
    }

    /** Control characters replaced, so a command name can't forge log lines. */
    fun printable(text: String): String = text.map { if (it.isISOControl()) '?' else it }.joinToString("")
  }
}

/**
 * Splits the control stream into length-prefixed JSON messages. A message may
 * span body chunks, so bytes are buffered until complete.
 */
class MessageDecoder {
  private var buf = ByteArray(0)

  fun feed(data: ByteArray): List<JSONObject> {
    buf += data
    val messages = ArrayList<JSONObject>()
    var off = 0
    while (buf.size - off >= 4) {
      val length = ByteBuffer.wrap(buf, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
      if (length > LinkSession.MAX_INBOUND_MESSAGE) throw IOException("inbound message too large: $length")
      if (buf.size - off - 4 < length) break
      val raw = buf.copyOfRange(off + 4, off + 4 + length.toInt())
      off += 4 + length.toInt()
      if (raw.isEmpty()) continue // keepalive
      try {
        messages += JSONObject(String(raw, Charsets.UTF_8))
      } catch (e: JSONException) {
        Logger.getLogger("LinkSession").warning("dropping malformed control message (${raw.size} bytes)")
      }
    }
    buf = buf.copyOfRange(off, buf.size)
    return messages
  }
}

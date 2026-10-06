package com.myfriendkennyagent.museportal.protocol.noise

/** Frames ready for the socket, in send order, for one stream. */
class EncryptedFrames(val streamId: Long, val frames: List<ByteArray>)

/** A response, body chunk or reset that arrived for [streamId]. */
class DecryptedFrame(val streamId: Long, val value: FrameValue)

/**
 * HTTP requests multiplexed over an established Noise session, ported from
 * `noise/transport.py`. Not thread-safe: callers must encrypt and send under
 * one lock so nonces reach the VM in order.
 */
class NoiseTransport(
  private val send: CipherState,
  private val recv: CipherState,
  private val chunkIds: () -> Long? = { null },
) {
  private val decoder = NoiseFrameDecoder()
  private var nextStreamId = 1L
  private var dead = false

  private fun assertAlive() {
    if (dead) throw NoiseProtocolException("NoiseTransport: dead after prior failure")
  }

  private inline fun <T> orDie(block: () -> T): T =
    try {
      block()
    } catch (t: Throwable) {
      dead = true
      throw t
    }

  /** A complete request: headers and the whole body in one go. */
  fun encryptHttpRequest(
    verb: String,
    path: String,
    body: ByteArray = ByteArray(0),
    headers: List<Header> = emptyList(),
    service: Int = ServiceType.DAEMON,
  ): EncryptedFrames = request(verb, path, body, headers, service, endBody = true)

  /** Opens a request whose body follows in [encryptBodyChunk] calls. */
  fun startStreamRequest(
    verb: String,
    path: String,
    headers: List<Header> = emptyList(),
    body: ByteArray = ByteArray(0),
    service: Int = ServiceType.DAEMON,
  ): EncryptedFrames = request(verb, path, body, headers, service, endBody = false)

  private fun request(
    verb: String,
    path: String,
    body: ByteArray,
    headers: List<Header>,
    service: Int,
    endBody: Boolean,
  ): EncryptedFrames {
    assertAlive()
    return orDie {
      val streamId = nextStreamId++
      val frame = ServiceFrame(streamId, ApplicationRequest(verb, path, headers, body, endBody))
      EncryptedFrames(streamId, encrypt(service, frame))
    }
  }

  fun encryptBodyChunk(
    streamId: Long,
    data: ByteArray,
    endBody: Boolean = false,
    service: Int = ServiceType.DAEMON,
  ): List<ByteArray> {
    assertAlive()
    return orDie { encrypt(service, ServiceFrame(streamId, BodyChunk(data, endBody))) }
  }

  fun encryptReset(
    streamId: Long,
    reason: String = "",
    code: Int = ResetCode.CANCELLED,
    service: Int = ServiceType.DAEMON,
  ): List<ByteArray> {
    assertAlive()
    return orDie { encrypt(service, ServiceFrame(streamId, Reset(code, reason))) }
  }

  /** Decrypts one socket message; null until a multi-frame message completes. */
  fun decryptFrame(ciphertext: ByteArray): DecryptedFrame? {
    assertAlive()
    return orDie {
      val plain = recv.decryptWithAd(EMPTY_AD, ciphertext)
      val reassembled = decoder.decode(plain) ?: return@orDie null
      val response = Envelope.decodeServiceResponse(reassembled)
      if (response.payload.isEmpty()) throw NoiseProtocolException("empty ServiceResponse payload")
      val frame = Envelope.decodeServiceFrame(response.payload)
      when (val v = frame.value) {
        is ApplicationResponse, is BodyChunk, is Reset -> DecryptedFrame(frame.streamId, v)
        is ApplicationRequest ->
          throw NoiseProtocolException("NoiseTransport: unexpected request frame from server")
        null -> null
      }
    }
  }

  private fun encrypt(service: Int, frame: ServiceFrame): List<ByteArray> {
    val requestBytes =
      Envelope.encodeServiceRequest(ServiceRequest(service, Envelope.encodeServiceFrame(frame)))
    return NoiseFraming.encodeFrames(requestBytes, chunkIds()).map { send.encryptWithAd(EMPTY_AD, it) }
  }

  companion object {
    private val EMPTY_AD = ByteArray(0)

    /** VM side: decodes a request envelope (tests and the fake VM). */
    fun decodeRequestEnvelope(data: ByteArray): Pair<Int, ServiceFrame> {
      val req = Envelope.decodeServiceRequest(data)
      return req.service to Envelope.decodeServiceFrame(req.payload)
    }

    /** VM side: wraps a frame in a response envelope (tests and the fake VM). */
    fun encodeResponseEnvelope(frame: ServiceFrame): ByteArray =
      Envelope.encodeServiceResponse(ServiceResponse(Envelope.encodeServiceFrame(frame)))
  }
}

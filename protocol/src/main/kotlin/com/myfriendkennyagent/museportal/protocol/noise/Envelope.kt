package com.myfriendkennyagent.museportal.protocol.noise

/**
 * The HTTP-over-Noise service envelopes, ported from `noise/envelope.py`.
 * A request is a [ServiceRequest] wrapping a [ServiceFrame]; responses come
 * back as a [ServiceResponse] wrapping one.
 */
object ServiceType {
  const val DAEMON = 0
  const val SENTINEL = 1
  const val VAULT = 2
  const val AUTHD = 3

  fun check(value: Long): Int {
    if (value < DAEMON || value > AUTHD) throw ProtoException("unknown service type")
    return value.toInt()
  }
}

object ResetCode {
  const val CODE_UNSPECIFIED = 0
  const val CANCELLED = 1
  const val TIMEOUT = 2
  const val PROTOCOL_ERROR = 3
  const val REFUSED_STREAM = 4
  const val INTERNAL_ERROR = 5
  const val SERVICE_UNAVAILABLE = 6

  fun check(value: Int): Int {
    if (value < CODE_UNSPECIFIED || value > SERVICE_UNAVAILABLE) throw ProtoException("unknown reset code")
    return value
  }
}

data class Header(val key: String = "", val value: String = "")

sealed class FrameValue

class ApplicationRequest(
  val verb: String = "",
  val path: String = "",
  val headers: List<Header> = emptyList(),
  val body: ByteArray = ByteArray(0),
  val endBody: Boolean = false,
) : FrameValue()

class ApplicationResponse(
  val status: Int = 0,
  val headers: List<Header> = emptyList(),
  val body: ByteArray = ByteArray(0),
  val endBody: Boolean = false,
) : FrameValue()

class BodyChunk(val data: ByteArray = ByteArray(0), val endBody: Boolean = false) : FrameValue()

class Reset(val code: Int = ResetCode.CODE_UNSPECIFIED, val reason: String = "") : FrameValue()

class ServiceFrame(val streamId: Long = 0, val value: FrameValue? = null)

class ServiceRequest(val service: Int = ServiceType.DAEMON, val payload: ByteArray = ByteArray(0))

class ServiceResponse(val payload: ByteArray = ByteArray(0))

object Envelope {
  fun encodeHeader(h: Header): ByteArray {
    val w = Proto.Writer()
    if (h.key.isNotEmpty()) w.string(1, h.key)
    if (h.value.isNotEmpty()) w.string(2, h.value)
    return w.bytes()
  }

  fun decodeHeader(data: ByteArray): Header {
    var key = ""
    var value = ""
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "Header.key")
          key = r.string()
        }
        2 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "Header.value")
          value = r.string()
        }
        else -> r.skip(wire)
      }
    }
    return Header(key, value)
  }

  fun encodeRequest(req: ApplicationRequest): ByteArray {
    val w = Proto.Writer()
    if (req.verb.isNotEmpty()) w.string(1, req.verb)
    if (req.path.isNotEmpty()) w.string(2, req.path)
    for (h in req.headers) w.delimited(3, encodeHeader(h))
    if (req.body.isNotEmpty()) w.delimited(4, req.body)
    if (req.endBody) w.bool(5, true)
    return w.bytes()
  }

  fun decodeRequest(data: ByteArray): ApplicationRequest {
    var verb = ""
    var path = ""
    val headers = ArrayList<Header>()
    var body = ByteArray(0)
    var endBody = false
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ApplicationRequest.verb")
          verb = r.string()
        }
        2 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ApplicationRequest.path")
          path = r.string()
        }
        3 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ApplicationRequest.headers")
          headers += decodeHeader(r.delimited())
        }
        4 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ApplicationRequest.body")
          body = r.delimited()
        }
        5 -> {
          r.expect(wire, Proto.WIRE_VARINT, "ApplicationRequest.end_body")
          endBody = r.varint() != 0L
        }
        else -> r.skip(wire)
      }
    }
    return ApplicationRequest(verb, path, headers, body, endBody)
  }

  fun encodeResponse(resp: ApplicationResponse): ByteArray {
    val w = Proto.Writer()
    if (resp.status != 0) w.int32(1, resp.status)
    for (h in resp.headers) w.delimited(2, encodeHeader(h))
    if (resp.body.isNotEmpty()) w.delimited(3, resp.body)
    if (resp.endBody) w.bool(4, true)
    return w.bytes()
  }

  fun decodeResponse(data: ByteArray): ApplicationResponse {
    var status = 0
    val headers = ArrayList<Header>()
    var body = ByteArray(0)
    var endBody = false
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_VARINT, "ApplicationResponse.status")
          status = Proto.decodeInt32(r.varint())
        }
        2 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ApplicationResponse.headers")
          headers += decodeHeader(r.delimited())
        }
        3 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ApplicationResponse.body")
          body = r.delimited()
        }
        4 -> {
          r.expect(wire, Proto.WIRE_VARINT, "ApplicationResponse.end_body")
          endBody = r.varint() != 0L
        }
        else -> r.skip(wire)
      }
    }
    return ApplicationResponse(status, headers, body, endBody)
  }

  fun encodeBodyChunk(chunk: BodyChunk): ByteArray {
    val w = Proto.Writer()
    if (chunk.data.isNotEmpty()) w.delimited(1, chunk.data)
    if (chunk.endBody) w.bool(2, true)
    return w.bytes()
  }

  fun decodeBodyChunk(data: ByteArray): BodyChunk {
    var bytes = ByteArray(0)
    var endBody = false
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "BodyChunk.data")
          bytes = r.delimited()
        }
        2 -> {
          r.expect(wire, Proto.WIRE_VARINT, "BodyChunk.end_body")
          endBody = r.varint() != 0L
        }
        else -> r.skip(wire)
      }
    }
    return BodyChunk(bytes, endBody)
  }

  fun encodeReset(reset: Reset): ByteArray {
    val w = Proto.Writer()
    val code = ResetCode.check(reset.code)
    if (code != ResetCode.CODE_UNSPECIFIED) w.int32(1, code)
    if (reset.reason.isNotEmpty()) w.string(2, reset.reason)
    return w.bytes()
  }

  fun decodeReset(data: ByteArray): Reset {
    var code = ResetCode.CODE_UNSPECIFIED
    var reason = ""
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_VARINT, "Reset.code")
          code = ResetCode.check(Proto.decodeInt32(r.varint()))
        }
        2 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "Reset.reason")
          reason = r.string()
        }
        else -> r.skip(wire)
      }
    }
    return Reset(code, reason)
  }

  fun encodeServiceFrame(frame: ServiceFrame): ByteArray {
    val w = Proto.Writer()
    if (frame.streamId != 0L) w.int64(1, frame.streamId)
    when (val v = frame.value) {
      null -> {}
      is ApplicationRequest -> w.delimited(2, encodeRequest(v))
      is ApplicationResponse -> w.delimited(3, encodeResponse(v))
      is BodyChunk -> w.delimited(4, encodeBodyChunk(v))
      is Reset -> w.delimited(5, encodeReset(v))
    }
    return w.bytes()
  }

  fun decodeServiceFrame(data: ByteArray): ServiceFrame {
    var streamId = 0L
    var value: FrameValue? = null
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_VARINT, "ServiceFrame.stream_id")
          streamId = r.varint()
        }
        2 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ServiceFrame.request")
          value = decodeRequest(r.delimited())
        }
        3 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ServiceFrame.response")
          value = decodeResponse(r.delimited())
        }
        4 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ServiceFrame.body_chunk")
          value = decodeBodyChunk(r.delimited())
        }
        5 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ServiceFrame.reset")
          value = decodeReset(r.delimited())
        }
        else -> r.skip(wire)
      }
    }
    return ServiceFrame(streamId, value)
  }

  fun encodeServiceRequest(req: ServiceRequest): ByteArray {
    val w = Proto.Writer()
    val service = ServiceType.check(req.service.toLong())
    if (service != ServiceType.DAEMON) w.varintField(1, service.toLong())
    if (req.payload.isNotEmpty()) w.delimited(2, req.payload)
    return w.bytes()
  }

  fun decodeServiceRequest(data: ByteArray): ServiceRequest {
    var service = ServiceType.DAEMON
    var payload = ByteArray(0)
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_VARINT, "ServiceRequest.service")
          service = ServiceType.check(r.varint())
        }
        2 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ServiceRequest.payload")
          payload = r.delimited()
        }
        else -> r.skip(wire)
      }
    }
    return ServiceRequest(service, payload)
  }

  fun encodeServiceResponse(resp: ServiceResponse): ByteArray {
    if (resp.payload.isEmpty()) return ByteArray(0)
    return Proto.Writer().delimited(1, resp.payload).bytes()
  }

  fun decodeServiceResponse(data: ByteArray): ServiceResponse {
    var payload = ByteArray(0)
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "ServiceResponse.payload")
          payload = r.delimited()
        }
        else -> r.skip(wire)
      }
    }
    return ServiceResponse(payload)
  }
}

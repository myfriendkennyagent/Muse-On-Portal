package com.myfriendkennyagent.museportal.protocol.noise

import java.io.ByteArrayOutputStream

/** Raised when a Noise protobuf message is malformed. */
class ProtoException(message: String) : IllegalArgumentException(message)

/**
 * The minimal protobuf wire format the Muse Noise envelopes use. Ported from
 * the Linux Device SDK's `noise/_proto.py`. uint64 values travel as the raw
 * bits of a [Long].
 */
internal object Proto {
  const val WIRE_VARINT = 0
  const val WIRE_FIXED64 = 1
  const val WIRE_DELIMITED = 2
  const val WIRE_FIXED32 = 5

  private const val MAX_FIELD_NUMBER = (1L shl 29) - 1

  private fun validFieldNumber(n: Long): Boolean =
    n != 0L && n <= MAX_FIELD_NUMBER && n !in 19000L..19999L

  private fun validWireType(w: Int): Boolean =
    w == WIRE_VARINT || w == WIRE_FIXED64 || w == WIRE_DELIMITED || w == WIRE_FIXED32

  class Writer {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    private fun varint(value: Long) {
      var v = value
      while (true) {
        val b = (v and 0x7F).toInt()
        v = v ushr 7
        if (v != 0L) {
          out.write(b or 0x80)
        } else {
          out.write(b)
          return
        }
      }
    }

    private fun key(field: Int, wire: Int) {
      if (!validFieldNumber(field.toLong())) throw ProtoException("invalid field number")
      if (!validWireType(wire)) throw ProtoException("invalid wire type")
      varint((field.toLong() shl 3) or wire.toLong())
    }

    fun varintField(field: Int, value: Long) = apply {
      key(field, WIRE_VARINT)
      varint(value)
    }

    /** int64: two's complement bits, ten bytes when negative. */
    fun int64(field: Int, value: Long) = varintField(field, value)

    /** int32: sign-extended to 64 bits, as protobuf encodes it. */
    fun int32(field: Int, value: Int) = varintField(field, value.toLong())

    fun uint32(field: Int, value: Long) = apply {
      if (value < 0 || value > 0xFFFFFFFFL) throw ProtoException("uint32 value out of range")
      varintField(field, value)
    }

    fun bool(field: Int, value: Boolean) = varintField(field, if (value) 1 else 0)

    fun delimited(field: Int, payload: ByteArray) = apply {
      key(field, WIRE_DELIMITED)
      varint(payload.size.toLong())
      out.write(payload)
    }

    fun string(field: Int, value: String) = delimited(field, value.toByteArray(Charsets.UTF_8))
  }

  class Reader(private val data: ByteArray) {
    var pos = 0
      private set

    fun hasMore(): Boolean = pos < data.size

    fun varint(): Long {
      var value = 0L
      var shift = 0
      for (i in 0 until 10) {
        if (pos >= data.size) throw ProtoException("truncated varint")
        val b = data[pos++].toInt() and 0xFF
        if (i == 9 && (b and 0xFE) != 0) throw ProtoException("malformed varint")
        value = value or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return value
        shift += 7
      }
      throw ProtoException("malformed varint")
    }

    /** Returns (field number, wire type). */
    fun key(): Pair<Int, Int> {
      val k = varint()
      val field = k ushr 3
      val wire = (k and 0x07).toInt()
      if (!validFieldNumber(field)) throw ProtoException("invalid field number")
      if (!validWireType(wire)) throw ProtoException("invalid wire type")
      return field.toInt() to wire
    }

    fun delimited(): ByteArray {
      val length = varint()
      if (length < 0 || length > data.size - pos) throw ProtoException("truncated delimited field")
      val end = pos + length.toInt()
      val out = data.copyOfRange(pos, end)
      pos = end
      return out
    }

    fun string(): String {
      val raw = delimited()
      val decoder = Charsets.UTF_8.newDecoder()
      return try {
        decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
      } catch (e: java.nio.charset.CharacterCodingException) {
        throw ProtoException("invalid utf-8 string")
      }
    }

    fun skip(wire: Int) {
      when (wire) {
        WIRE_VARINT -> varint()
        WIRE_FIXED64 -> advance(8, "truncated fixed64 field")
        WIRE_DELIMITED -> delimited()
        WIRE_FIXED32 -> advance(4, "truncated fixed32 field")
        else -> throw ProtoException("invalid wire type")
      }
    }

    private fun advance(n: Int, error: String) {
      if (data.size - pos < n) throw ProtoException(error)
      pos += n
    }

    fun expect(actual: Int, wanted: Int, what: String) {
      if (actual != wanted) throw ProtoException("$what wrong wire type")
    }
  }

  fun decodeInt32(raw: Long): Int {
    if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) throw ProtoException("int32 value out of range")
    return raw.toInt()
  }

  fun decodeUint32(raw: Long): Long {
    if (raw < 0 || raw > 0xFFFFFFFFL) throw ProtoException("uint32 value out of range")
    return raw
  }
}

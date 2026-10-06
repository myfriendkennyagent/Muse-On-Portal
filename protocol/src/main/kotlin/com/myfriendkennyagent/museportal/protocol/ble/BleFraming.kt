package com.myfriendkennyagent.museportal.protocol.ble

/**
 * Chunked framing for setup messages larger than one BLE packet, ported from
 * `ble_framing.py`. Both directions use `0xFE`, chunk index, total chunks,
 * then a fragment. A packet that does not start with `0xFE` is a complete,
 * unchunked message.
 */
object BleFraming {
  const val CHUNK_MAGIC = 0xFE
  const val HEADER_BYTES = 3
  const val MAX_PACKET_BYTES = 160
  const val MAX_CHUNKS = 255
  const val MAX_MESSAGE_BYTES = 8192
  const val DEFAULT_ATT_MTU = 23
  const val CHUNK_STAGGER_MS = 50L

  /** Splits [data] into framed packets that each fit one notification at [mtu]. */
  fun encodeChunks(data: ByteArray, mtu: Int = DEFAULT_ATT_MTU): List<ByteArray> {
    val notifyMax = minOf(if (mtu > 3) mtu - 3 else 20, MAX_PACKET_BYTES)
    val usable = notifyMax - HEADER_BYTES
    val fragments =
      if (data.isEmpty()) listOf(ByteArray(0))
      else (data.indices step usable).map { data.copyOfRange(it, minOf(it + usable, data.size)) }
    require(fragments.size <= MAX_CHUNKS) { "message needs ${fragments.size} chunks (max $MAX_CHUNKS)" }
    val total = fragments.size
    return fragments.mapIndexed { i, frag ->
      byteArrayOf(CHUNK_MAGIC.toByte(), i.toByte(), total.toByte()) + frag
    }
  }
}

/**
 * Reassembles chunked writes strictly in order. Index 0, or a change in the
 * total, starts a new message; an out-of-order chunk or an oversize message
 * discards what has been collected.
 */
class ChunkAssembler(private val maxBytes: Int = BleFraming.MAX_MESSAGE_BYTES) {
  private var buf = java.io.ByteArrayOutputStream()
  private var total = 0
  private var next = 0

  @Synchronized
  fun reset() {
    buf = java.io.ByteArrayOutputStream()
    total = 0
    next = 0
  }

  /** Adds one write; returns a complete message once one is available. */
  @Synchronized
  fun feed(packet: ByteArray): ByteArray? {
    if (packet.size < BleFraming.HEADER_BYTES || (packet[0].toInt() and 0xFF) != BleFraming.CHUNK_MAGIC) {
      return packet.copyOf()
    }
    val index = packet[1].toInt() and 0xFF
    val count = packet[2].toInt() and 0xFF
    val fragment = packet.copyOfRange(BleFraming.HEADER_BYTES, packet.size)
    if (count == 0) {
      reset()
      return null
    }
    if (index == 0 || count != total) {
      reset()
      total = count
    }
    if (index != next || index >= total) {
      reset()
      return null
    }
    if (buf.size() + fragment.size > maxBytes) {
      reset()
      return null
    }
    buf.write(fragment)
    next = index + 1
    if (next < total) return null
    val message = buf.toByteArray()
    reset()
    return message
  }
}

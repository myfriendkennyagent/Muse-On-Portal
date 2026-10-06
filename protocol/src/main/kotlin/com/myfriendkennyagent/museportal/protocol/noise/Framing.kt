package com.myfriendkennyagent.museportal.protocol.noise

import java.security.SecureRandom

/**
 * Noise transport framing: a message is split into protobuf frames of at most
 * [MAX_CHUNK_PAYLOAD] bytes sharing a random chunk id. Ported from
 * `noise/framing.py`.
 */
class NoiseTransportFrame(
  val chunkId: Long = 0,
  val chunkIndex: Long = 0,
  val totalChunks: Long = 1,
  val payload: ByteArray = ByteArray(0),
)

object NoiseFraming {
  const val MAX_CHUNK_PAYLOAD = 65489
  const val MAX_PENDING_ASSEMBLIES = 16
  const val MAX_TOTAL_CHUNKS = 256
  const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024
  const val ASSEMBLY_TTL_MS = 60_000L

  private val random = SecureRandom()

  fun encodeFrame(frame: NoiseTransportFrame): ByteArray {
    val w = Proto.Writer()
    if (frame.chunkId != 0L) w.int64(1, frame.chunkId)
    if (frame.chunkIndex != 0L) w.uint32(2, frame.chunkIndex)
    if (frame.totalChunks != 0L) w.uint32(3, frame.totalChunks)
    if (frame.payload.isNotEmpty()) w.delimited(4, frame.payload)
    return w.bytes()
  }

  fun decodeFrame(data: ByteArray): NoiseTransportFrame {
    var chunkId = 0L
    var chunkIndex = 0L
    var totalChunks = 1L
    var payload = ByteArray(0)
    val r = Proto.Reader(data)
    while (r.hasMore()) {
      val (field, wire) = r.key()
      when (field) {
        1 -> {
          r.expect(wire, Proto.WIRE_VARINT, "NoiseTransportFrame.chunk_id")
          chunkId = r.varint()
        }
        2 -> {
          r.expect(wire, Proto.WIRE_VARINT, "NoiseTransportFrame.chunk_index")
          chunkIndex = Proto.decodeUint32(r.varint())
        }
        3 -> {
          r.expect(wire, Proto.WIRE_VARINT, "NoiseTransportFrame.total_chunks")
          totalChunks = Proto.decodeUint32(r.varint())
        }
        4 -> {
          r.expect(wire, Proto.WIRE_DELIMITED, "NoiseTransportFrame.payload")
          payload = r.delimited()
        }
        else -> r.skip(wire)
      }
    }
    return NoiseTransportFrame(chunkId, chunkIndex, totalChunks, payload)
  }

  fun encodeFrames(data: ByteArray, chunkId: Long? = null): List<ByteArray> {
    val id = chunkId ?: random.nextLong()
    val total = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
    require(total <= MAX_TOTAL_CHUNKS) {
      "payload too large for noise framing (${data.size} bytes, $total chunks > $MAX_TOTAL_CHUNKS)"
    }
    if (data.isEmpty()) return listOf(encodeFrame(NoiseTransportFrame(id, 0, 1, ByteArray(0))))
    return (0 until total).map { i ->
      val start = i * MAX_CHUNK_PAYLOAD
      val end = minOf(start + MAX_CHUNK_PAYLOAD, data.size)
      encodeFrame(NoiseTransportFrame(id, i.toLong(), total.toLong(), data.copyOfRange(start, end)))
    }
  }
}

/** Reassembles framed messages; any malformed input poisons it, as upstream. */
class NoiseFrameDecoder(private val clock: () -> Long = System::currentTimeMillis) {
  private class Assembly(val total: Long, val createdAt: Long) {
    val chunks = HashMap<Long, ByteArray>()
    var totalBytes = 0L
  }

  private val pending = HashMap<Long, Assembly>()
  private var poisoned = false

  @Synchronized
  fun decode(frameBytes: ByteArray): ByteArray? {
    if (poisoned) throw IllegalStateException("NoiseFrameDecoder: poisoned after prior failure")
    try {
      val frame = NoiseFraming.decodeFrame(frameBytes)
      if (frame.totalChunks < 1 || frame.totalChunks > NoiseFraming.MAX_TOTAL_CHUNKS) {
        throw IllegalArgumentException("invalid totalChunks: ${frame.totalChunks}")
      }
      if (frame.chunkIndex < 0 || frame.chunkIndex >= frame.totalChunks) {
        throw IllegalArgumentException("chunkIndex ${frame.chunkIndex} out of range [0, ${frame.totalChunks})")
      }
      if (frame.payload.size > NoiseFraming.MAX_CHUNK_PAYLOAD) {
        throw IllegalArgumentException("payload too large for noise frame (${frame.payload.size} bytes)")
      }
      evictExpired()
      val assembly =
        pending[frame.chunkId]
          ?: run {
            if (pending.size >= NoiseFraming.MAX_PENDING_ASSEMBLIES) {
              throw IllegalArgumentException("too many pending noise frame assemblies")
            }
            Assembly(frame.totalChunks, clock()).also { pending[frame.chunkId] = it }
          }
      if (assembly.total != frame.totalChunks) {
        pending.remove(frame.chunkId)
        throw IllegalArgumentException(
          "inconsistent totalChunks for chunkId: expected ${assembly.total}, got ${frame.totalChunks}"
        )
      }
      if (frame.chunkIndex in assembly.chunks) {
        pending.remove(frame.chunkId)
        throw IllegalArgumentException("duplicate chunkIndex ${frame.chunkIndex}")
      }
      assembly.totalBytes += frame.payload.size
      if (assembly.totalBytes > NoiseFraming.MAX_ASSEMBLY_BYTES) {
        pending.remove(frame.chunkId)
        throw IllegalArgumentException("assembly exceeded byte budget")
      }
      assembly.chunks[frame.chunkIndex] = frame.payload
      if (assembly.chunks.size < assembly.total) return null
      pending.remove(frame.chunkId)
      if (assembly.total == 1L) return assembly.chunks.getValue(0)
      val out = java.io.ByteArrayOutputStream(assembly.totalBytes.toInt())
      for (i in 0 until assembly.total) out.write(assembly.chunks.getValue(i))
      return out.toByteArray()
    } catch (e: Exception) {
      poisoned = true
      throw e
    }
  }

  private fun evictExpired() {
    val now = clock()
    pending.entries.removeAll { now - it.value.createdAt > NoiseFraming.ASSEMBLY_TTL_MS }
  }
}

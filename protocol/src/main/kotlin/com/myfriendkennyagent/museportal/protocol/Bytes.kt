package com.myfriendkennyagent.museportal.protocol

import java.security.MessageDigest
import java.util.Base64

/** Byte helpers shared by the pairing and Noise code. */
object Bytes {
  private val B64URL_CHARS = Regex("[A-Za-z0-9_-]+")

  fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

  fun unhex(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd-length hex" }
    return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
  }

  /** Unpadded base64url, as the pairing protocol writes it. */
  fun b64url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

  /**
   * Decodes unpadded base64url, rejecting anything the firmware rejects:
   * empty or oversize input, padding, and characters outside the alphabet.
   */
  fun unb64url(text: Any?, maxChars: Int = 4096): ByteArray {
    require(text is String && text.isNotEmpty() && text.length <= maxChars) { "invalid base64url length" }
    require(text.length % 4 != 1 && B64URL_CHARS.matches(text)) { "invalid base64url" }
    return Base64.getUrlDecoder().decode(text)
  }

  fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

  fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
  }

  fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var off = 0
    for (p in parts) {
      p.copyInto(out, off)
      off += p.size
    }
    return out
  }

  fun u64be(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }

  /** Constant-time comparison. */
  fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

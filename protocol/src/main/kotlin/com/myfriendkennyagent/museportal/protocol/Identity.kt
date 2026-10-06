package com.myfriendkennyagent.museportal.protocol

import java.security.SecureRandom

/**
 * Stable device identity, compatible with the Linux Device SDK's
 * `identity.json`: a random, locally administered MAC-shaped value generated
 * once and kept across unpairing. The node id and the BLE name end in the
 * same six hex digits, which the Muse apps rely on.
 */
data class Identity(val mac: String) {
  init {
    require(MAC_RE.matches(mac)) { "invalid identity mac" }
  }

  val suffix: String
    get() = mac.replace(":", "").takeLast(6)

  val nodeId: String
    get() = NODE_ID_PREFIX + suffix

  val deviceId: String
    get() = "hatch-link:$mac"

  /** No separator: the apps compare the text after the prefix with the node id's. */
  val bleName: String
    get() = BLE_NAME_PREFIX + suffix.uppercase()

  companion object {
    const val NODE_ID_PREFIX = "homelink-"
    const val BLE_NAME_PREFIX = "MuseGadget"
    private val MAC_RE = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    fun isValidMac(mac: Any?): Boolean = mac is String && MAC_RE.matches(mac)

    fun generate(random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } }): Identity {
      val octets = random(6)
      octets[0] = ((octets[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
      return Identity(octets.joinToString(":") { "%02x".format(it.toInt() and 0xFF) })
    }
  }
}

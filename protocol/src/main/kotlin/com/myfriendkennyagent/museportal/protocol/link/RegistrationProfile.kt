package com.myfriendkennyagent.museportal.protocol.link

/**
 * How this device describes itself in `link.register`. This is the one place
 * those strings live.
 *
 * There is no Android or Portal profile in the Muse Gadget SDK, so the device
 * registers with the Linux SDK's values, which the server is known to accept.
 * That is an impersonation Meta could stop accepting at any time: if
 * registration starts failing after a server change, this is the first thing
 * to check (and `tools/check_upstream.py` flags upstream protocol changes).
 */
data class RegistrationProfile(val platform: String, val deviceFamily: String, val modelId: String) {
  companion object {
    /** The Linux Device SDK's values. Never family "link" or `device.ota`: the server pushes ESP32 firmware to "link" devices. */
    val LINUX_COMPAT = RegistrationProfile(platform = "linux", deviceFamily = "homehub", modelId = "linux")

    val DEFAULT = LINUX_COMPAT
  }
}

package com.myfriendkennyagent.museportal.protocol.pairing

import com.myfriendkennyagent.museportal.protocol.Bytes
import com.myfriendkennyagent.museportal.protocol.Identity
import com.myfriendkennyagent.museportal.protocol.TestSupport
import com.myfriendkennyagent.museportal.protocol.ble.BleFraming
import com.myfriendkennyagent.museportal.protocol.ble.ChunkAssembler
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole BLE setup conversation, driven the way the Muse app drives it. */
class SetupControllerTest {
  private val v = TestSupport.pairingVector("community_app_v5")
  private val identity = Identity("02:00:00:00:00:01")

  private class FakeTransport : SetupTransport {
    val assembler = ChunkAssembler()
    val messages = LinkedBlockingQueue<String>()
    var disconnects = 0

    override fun sendPackets(packets: List<ByteArray>) {
      for (p in packets) assembler.feed(p)?.let { messages.put(String(it)) }
    }

    override fun mtu() = 185

    override fun disconnect(delayMs: Long) {
      disconnects++
    }

    fun next(): String = messages.poll(5, TimeUnit.SECONDS) ?: error("no message from device")
  }

  private class Mobile(v: JSONObject) {
    val tx = Bytes.unhex(v.getString("mobile_tx_key_hex"))
    val rx = Bytes.unhex(v.getString("mobile_rx_key_hex"))
    val sid: String = v.getString("session_id")
    var counter = 1L // the client-finished record from the vector used counter 0

    fun seal(command: JSONObject): JSONObject {
      val c = counter++
      val sealed =
        Pairing.gcm(Cipher.ENCRYPT_MODE, tx, Pairing.recordNonce(0, c), "hatch-link ble setup v1|$sid|m2d|$c".toByteArray(), command.toString().toByteArray())
      return JSONObject()
        .put("action", "pairing_encrypted")
        .put("session_id", sid)
        .put("counter", c.toString())
        .put("ciphertext", Bytes.b64url(sealed.copyOfRange(0, sealed.size - 16)))
        .put("tag", Bytes.b64url(sealed.copyOfRange(sealed.size - 16, sealed.size)))
    }

    fun open(text: String): JSONObject {
      val e = JSONObject(text)
      val c = e.getString("counter").toLong()
      val plain =
        Pairing.gcm(
          Cipher.DECRYPT_MODE,
          rx,
          Pairing.recordNonce(1, c),
          "hatch-link ble setup v1|$sid|d2m|$c".toByteArray(),
          Bytes.unb64url(e.getString("ciphertext"), 100000) + Bytes.unb64url(e.getString("tag")),
        )
      return JSONObject(String(plain))
    }
  }

  private fun write(controller: SetupController, message: JSONObject) {
    // The app chunks its writes too; exercise the reassembly path.
    BleFraming.encodeChunks(message.toString().toByteArray(), 64).forEach(controller::onWrite)
  }

  @Test
  fun `app pairs and provisions the device`() {
    val transport = FakeTransport()
    var saved: Credentials? = null
    val completed = java.util.concurrent.CountDownLatch(1)
    val pairing =
      PairingSession(
        nodeId = identity.nodeId,
        deviceId = v.getString("device_id"),
        mac = identity.mac,
        firmwareVersion = v.getString("firmware_version"),
        sdkToken = "mgst_" + "B".repeat(43),
        generateKey = { P256Key(BigInteger(v.getString("device_private_scalar_hex"), 16)) },
        randomBytes = { Bytes.unb64url(v.getString("device_nonce")) },
      )
    val controller =
      SetupController(
        pairing = pairing,
        identity = identity,
        version = "1.0.0",
        transport = transport,
        network =
          object : SetupNetwork {
            override fun isOnline() = true

            override fun currentConnectionEntry() =
              JSONObject().put("ssid", "HomeWiFi").put("rssi", -40).put("secure", false)
          },
        provision = { creds, commit ->
          if (creds.accessToken != "acc") throw ProvisionFailed("auth_failed")
          check(commit {
            saved = creds
            true
          })
        },
        onComplete = { completed.countDown() },
      )
    controller.start()

    write(controller, JSONObject().put("action", "get_device_info"))
    val info = JSONObject(transport.next())
    assertEquals("device_info", info.getString("type"))
    assertEquals("homelink-000001", info.getString("node_id"))
    assertEquals("hatch_link", info.getString("model"))
    assertEquals("confirm_app", info.getString("pairing_policy"))

    // Sensitive actions need encryption first.
    write(controller, JSONObject().put("action", "wifi_scan"))
    assertEquals("error_encryption_required", transport.next())

    write(
      controller,
      JSONObject()
        .put("action", "pairing_client_hello")
        .put("version", 5)
        .put("pairing_auth", "none")
        .put("pairing_policy", "confirm_app")
        .put("mobile_pub", v.getString("mobile_pub"))
        .put("mobile_nonce", v.getString("mobile_nonce")),
    )
    val ready = JSONObject(transport.next())
    assertEquals("pairing_ready", ready.getString("type"))
    assertEquals(v.getString("session_id"), ready.getString("session_id"))

    write(
      controller,
      JSONObject()
        .put("action", "pairing_encrypted")
        .put("session_id", v.getString("session_id"))
        .put("counter", "0")
        .put("ciphertext", v.getString("client_finished_ciphertext"))
        .put("tag", v.getString("client_finished_tag")),
    )
    val mobile = Mobile(v)
    val confirmed = mobile.open(transport.next())
    assertEquals("pairing_confirmed", confirmed.getString("status"))
    assertEquals("mgst_" + "B".repeat(43), confirmed.getString("sdk_token"))

    write(controller, mobile.seal(JSONObject().put("action", "wifi_scan")))
    val scan = mobile.open(transport.next())
    assertEquals("HomeWiFi", scan.getJSONArray("networks").getJSONObject(0).getString("ssid"))

    write(
      controller,
      mobile.seal(
        JSONObject()
          .put("action", "provision_v2")
          .put("ssid", "HomeWiFi")
          .put("password", "")
          .put("access_token", "acc")
          .put("refresh_token", "ref")
          .put("token_type", "device")
          .put("username", "kenny")
          .put("api_url_v2", "https://api.muse.ai")
          .put("noise_host", "hatch.metaaivm.com")
      ),
    )
    assertEquals("wifi_connecting", mobile.open(transport.next()).getString("status"))
    assertEquals("wifi_connected", mobile.open(transport.next()).getString("status"))
    assertEquals("auth_ok", mobile.open(transport.next()).getString("status"))
    assertTrue(completed.await(5, TimeUnit.SECONDS))
    assertEquals("ref", saved!!.refreshToken)
    assertEquals("hatch.metaaivm.com", saved!!.noiseHost)
    controller.stop()
  }

  @Test
  fun `ble framing round trips and enforces order`() {
    val data = ByteArray(1000) { it.toByte() }
    val packets = BleFraming.encodeChunks(data, 185)
    assertTrue(packets.all { it.size <= 160 })
    val a = ChunkAssembler()
    packets.dropLast(1).forEach { assertNull(a.feed(it)) }
    assertArrayEquals(data, a.feed(packets.last()))

    val b = ChunkAssembler()
    assertNull(b.feed(packets[0]))
    assertNull(b.feed(packets[2])) // out of order discards
    assertNull(b.feed(packets[1]))
    assertArrayEquals("plain".toByteArray(), b.feed("plain".toByteArray()))
  }

  @Test
  fun `identity names follow the app conventions`() {
    val id = Identity.generate { ByteArray(it) { 0xAB.toByte() } }
    assertEquals("aa:ab:ab:ab:ab:ab", id.mac)
    assertEquals("homelink-ababab", id.nodeId)
    assertEquals("MuseGadgetABABAB", id.bleName)
    assertEquals("hatch-link:${id.mac}", id.deviceId)
  }
}

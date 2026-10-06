package com.myfriendkennyagent.museportal.protocol.pairing

import com.myfriendkennyagent.museportal.protocol.Bytes
import com.myfriendkennyagent.museportal.protocol.TestSupport
import java.math.BigInteger
import javax.crypto.Cipher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the Linux SDK's `tests/test_pairing.py` against the published vectors. */
class PairingTest {
  private val app = TestSupport.pairingVector("community_app_v5")

  private class FakeClock(var now: Long = 1_000_000) : () -> Long {
    override fun invoke() = now
  }

  /** The mobile side of the record layer, written from the contract, not the device code. */
  private class Mobile(v: JSONObject) {
    val tx = Bytes.unhex(v.getString("mobile_tx_key_hex"))
    val rx = Bytes.unhex(v.getString("mobile_rx_key_hex"))
    val sessionId: String = v.getString("session_id")
    var txCounter = 0L

    fun seal(command: JSONObject, counter: Long? = null): JSONObject {
      val c = counter ?: txCounter++
      val sealed =
        Pairing.gcm(
          Cipher.ENCRYPT_MODE,
          tx,
          Pairing.recordNonce(0, c),
          "hatch-link ble setup v1|$sessionId|m2d|$c".toByteArray(),
          command.toString().toByteArray(),
        )
      return JSONObject()
        .put("action", "pairing_encrypted")
        .put("session_id", sessionId)
        .put("counter", c.toString())
        .put("ciphertext", Bytes.b64url(sealed.copyOfRange(0, sealed.size - 16)))
        .put("tag", Bytes.b64url(sealed.copyOfRange(sealed.size - 16, sealed.size)))
    }

    fun open(envelope: JSONObject): JSONObject {
      val c = envelope.getString("counter").toLong()
      val plain =
        Pairing.gcm(
          Cipher.DECRYPT_MODE,
          rx,
          Pairing.recordNonce(1, c),
          "hatch-link ble setup v1|$sessionId|d2m|$c".toByteArray(),
          Bytes.unb64url(envelope.getString("ciphertext"), 100000) + Bytes.unb64url(envelope.getString("tag")),
        )
      return JSONObject(String(plain))
    }
  }

  private fun makeDevice(v: JSONObject = app, clock: FakeClock = FakeClock(), sdkToken: String? = null) =
    PairingSession(
      nodeId = v.getString("node_id"),
      deviceId = v.getString("device_id"),
      mac = v.getString("mac"),
      firmwareVersion = v.getString("firmware_version"),
      sdkToken = sdkToken,
      clock = clock,
      generateKey = { P256Key(BigInteger(v.getString("device_private_scalar_hex"), 16)) },
      randomBytes = { Bytes.unb64url(v.getString("device_nonce")) },
    )

  private fun hello(v: JSONObject = app, overrides: Map<String, Any?> = emptyMap()): JSONObject {
    val m =
      JSONObject()
        .put("action", "pairing_client_hello")
        .put("version", 5)
        .put("pairing_auth", "none")
        .put("pairing_policy", "confirm_app")
        .put("mobile_pub", v.getString("mobile_pub"))
        .put("mobile_nonce", v.getString("mobile_nonce"))
    overrides.forEach { (k, value) -> if (value == null) m.remove(k) else m.put(k, value) }
    return m
  }

  private fun clientFinishedRecord(v: JSONObject = app) =
    JSONObject()
      .put("action", "pairing_encrypted")
      .put("session_id", v.getString("session_id"))
      .put("counter", "0")
      .put("ciphertext", v.getString("client_finished_ciphertext"))
      .put("tag", v.getString("client_finished_tag"))

  private fun confirmedDevice(clock: FakeClock = FakeClock()): Triple<PairingSession, Mobile, Int> {
    val device = makeDevice(clock = clock)
    device.handleHello(hello())
    val mobile = Mobile(app)
    mobile.txCounter = 1
    val gen = device.handleClientFinished(JSONObject(device.decrypt(clientFinishedRecord())))
    assertTrue(gen != 0)
    return Triple(device, mobile, gen)
  }

  @Test
  fun `transcript matches every vector`() {
    for (v in TestSupport.pairingVectors()) {
      val transcript =
        Pairing.buildTranscript(
          community = v.getBoolean("community"),
          authEpoch = v.getInt("pairing_auth_epoch"),
          policy = v.getString("pairing_policy"),
          deviceId = v.getString("device_id"),
          nodeId = v.getString("node_id"),
          mac = v.getString("mac"),
          firmwareVersion = v.getString("firmware_version"),
          mobilePub = v.getString("mobile_pub"),
          devicePub = v.getString("device_pub"),
          mobileNonce = v.getString("mobile_nonce"),
          deviceNonce = v.getString("device_nonce"),
        )
      assertEquals(v.getString("name"), v.getString("transcript"), transcript)
      assertEquals(v.getString("transcript_hash"), Bytes.b64url(Bytes.sha256(transcript.toByteArray())))
    }
  }

  @Test
  fun `key schedule matches every vector`() {
    for (v in TestSupport.pairingVectors()) {
      val ecdh = Bytes.unhex(v.getString("ecdh_secret_hex"))
      val mobileNonce = Bytes.unb64url(v.getString("mobile_nonce"))
      val deviceNonce = Bytes.unb64url(v.getString("device_nonce"))
      val hash = Bytes.unb64url(v.getString("transcript_hash"))
      assertEquals(v.getString("session_secret_hex"), Bytes.hex(Pairing.sessionSecret(ecdh, mobileNonce, deviceNonce, hash)))
      val (tx, rx, sid) = Pairing.deriveSessionKeys(ecdh, mobileNonce, deviceNonce, hash)
      assertEquals(v.getString("mobile_tx_key_hex"), Bytes.hex(tx))
      assertEquals(v.getString("mobile_rx_key_hex"), Bytes.hex(rx))
      assertEquals(v.getString("session_id"), Bytes.b64url(sid))
    }
  }

  @Test
  fun `ecdh and public keys match the vectors`() {
    for (v in TestSupport.pairingVectors()) {
      val device = P256Key(BigInteger(v.getString("device_private_scalar_hex"), 16))
      val mobile = P256Key(BigInteger(v.getString("mobile_private_scalar_hex"), 16))
      assertEquals(v.getString("device_pub"), Bytes.b64url(device.publicPoint))
      assertEquals(v.getString("mobile_pub"), Bytes.b64url(mobile.publicPoint))
      assertEquals(v.getString("ecdh_secret_hex"), Bytes.hex(device.exchange(Pairing.decodePoint(mobile.publicPoint))))
    }
  }

  @Test
  fun `transcript rejects app policy for official devices`() {
    assertThrows(IllegalArgumentException::class.java) {
      Pairing.buildTranscript(false, 1, "confirm_app", "d", "n", "m", "f", "a", "b", "c", "e")
    }
  }

  @Test
  fun `hello produces the vector pairing_ready`() {
    val ready = makeDevice().handleHello(hello())
    val expected =
      mapOf(
        "type" to "pairing_ready",
        "version" to 5,
        "device_id" to app.getString("device_id"),
        "node_id" to app.getString("node_id"),
        "mac" to app.getString("mac"),
        "model" to "hatch_link",
        "firmware_version" to app.getString("firmware_version"),
        "pairing_auth" to "none",
        "pairing_auth_epoch" to 0,
        "pairing_policy" to "confirm_app",
        "device_pub" to app.getString("device_pub"),
        "device_nonce" to app.getString("device_nonce"),
        "transcript_hash" to app.getString("transcript_hash"),
        "session_id" to app.getString("session_id"),
      )
    assertEquals(expected, ready.toMap())
  }

  @Test
  fun `full app confirmed handshake`() {
    val device = makeDevice()
    device.handleHello(hello())
    assertEquals(PairingState.WAIT_CLIENT_FINISHED, device.state())
    val plaintext = device.decrypt(clientFinishedRecord())
    assertEquals(app.getString("client_finished_plaintext"), plaintext)
    val gen = device.handleClientFinished(JSONObject(plaintext))
    assertTrue(gen != 0)
    assertTrue(device.confirmed)
    val mobile = Mobile(app)
    assertEquals(
      mapOf("type" to "status", "status" to "pairing_confirmed"),
      mobile.open(device.encryptStatus("pairing_confirmed", gen)!!).toMap(),
    )
    val scan = device.decrypt(mobile.seal(JSONObject().put("action", "wifi_scan"), counter = 1))
    assertEquals("wifi_scan", JSONObject(scan).getString("action"))
  }

  @Test
  fun `pairing_confirmed carries the sdk token`() {
    val token = "mgst_" + "A".repeat(43)
    val device = makeDevice(sdkToken = token)
    device.handleHello(hello())
    val gen = device.handleClientFinished(JSONObject(device.decrypt(clientFinishedRecord())))
    val mobile = Mobile(app)
    assertEquals(
      mapOf("type" to "status", "status" to "pairing_confirmed", "sdk_token" to token),
      mobile.open(device.encryptStatus("pairing_confirmed", gen)!!).toMap(),
    )
    assertEquals(
      mapOf("type" to "status", "status" to "wifi_connecting"),
      mobile.open(device.encryptStatus("wifi_connecting", gen)!!).toMap(),
    )
  }

  @Test
  fun `device records count up from zero`() {
    val (device, mobile, gen) = confirmedDevice()
    val first = device.encryptStatus("pairing_confirmed", gen)!!
    val second = device.encryptJson("""{"type":"wifi_scan_result","networks":[]}""", gen)!!
    assertEquals("0" to "1", first.getString("counter") to second.getString("counter"))
    assertEquals("wifi_scan_result", mobile.open(second).getString("type"))
  }

  @Test
  fun `hello rejects invalid input`() {
    val cases: List<Map<String, Any?>> =
      listOf(
        mapOf("version" to 4),
        mapOf("version" to "5"),
        mapOf("version" to true),
        mapOf("pairing_auth" to "fleet_ecdsa_p256_v1"),
        mapOf("pairing_policy" to "confirm_press"),
        mapOf("pairing_policy" to null),
        mapOf("mobile_pub" to ""),
        mapOf("mobile_pub" to Bytes.b64url(byteArrayOf(4) + ByteArray(64))),
        mapOf("mobile_pub" to Bytes.b64url(byteArrayOf(2) + ByteArray(32))),
        mapOf("mobile_pub" to app.getString("mobile_pub") + "="),
        mapOf("mobile_nonce" to Bytes.b64url(ByteArray(15))),
        mapOf("mobile_nonce" to 7),
      )
    for (overrides in cases) {
      val device = makeDevice()
      val err = assertThrows(PairingException::class.java) { device.handleHello(hello(overrides = overrides)) }
      assertEquals(overrides.toString(), "error_pairing_invalid_hello", err.status)
      assertEquals(PairingState.IDLE, device.state())
    }
  }

  @Test
  fun `new hello replaces the previous session`() {
    val (device, _, gen) = confirmedDevice()
    device.handleHello(hello())
    assertFalse(device.isCurrent(gen))
    assertEquals(PairingState.WAIT_CLIENT_FINISHED, device.state())
  }

  private fun expectDecryptFailure(device: PairingSession, envelope: JSONObject) {
    val err = assertThrows(PairingException::class.java) { device.decrypt(envelope) }
    assertEquals("error_pairing_decrypt", err.status)
    assertEquals(PairingState.IDLE, device.state())
  }

  @Test
  fun `replayed record clears the session`() {
    val (device, mobile, _) = confirmedDevice()
    val record = mobile.seal(JSONObject().put("action", "wifi_scan"))
    device.decrypt(record)
    expectDecryptFailure(device, record)
  }

  @Test
  fun `skipped counter clears the session`() {
    val (device, mobile, _) = confirmedDevice()
    expectDecryptFailure(device, mobile.seal(JSONObject().put("action", "wifi_scan"), counter = 2))
  }

  @Test
  fun `malformed counter clears the session`() {
    for (counter in listOf<Any>("-1", "+1", " 1", "1.0", "", "18446744073709551616", 1)) {
      val (device, mobile, _) = confirmedDevice()
      val record = mobile.seal(JSONObject().put("action", "wifi_scan"))
      record.put("counter", counter)
      expectDecryptFailure(device, record)
    }
  }

  @Test
  fun `tampered ciphertext clears the session`() {
    val (device, mobile, _) = confirmedDevice()
    val record = mobile.seal(JSONObject().put("action", "wifi_scan"))
    val raw = Bytes.unb64url(record.getString("ciphertext"))
    raw[0] = (raw[0].toInt() xor 1).toByte()
    record.put("ciphertext", Bytes.b64url(raw))
    expectDecryptFailure(device, record)
  }

  @Test
  fun `client finished must be exact and first`() {
    val device = makeDevice()
    device.handleHello(hello())
    val mobile = Mobile(app)
    val record = mobile.seal(JSONObject().put("action", "pairing_client_finished").put("extra", 1))
    val gen = device.handleClientFinished(JSONObject(device.decrypt(record)))
    assertEquals(0, gen)
    assertEquals(PairingState.IDLE, device.state())
  }

  @Test
  fun `sessions expire`() {
    val clock = FakeClock()
    val device = makeDevice(clock = clock)
    device.handleHello(hello())
    clock.now += Pairing.CLIENT_FINISHED_TIMEOUT_MS + 1
    expectDecryptFailure(device, clientFinishedRecord())

    val (confirmed, _, gen) = confirmedDevice(clock)
    clock.now += Pairing.CONFIRMED_TIMEOUT_MS + 1
    assertFalse(confirmed.confirmed)
    assertNull(confirmed.encryptStatus("pairing_confirmed", gen))
    assertTrue(confirmed.isCurrent(gen)) // expiry keeps the generation, as upstream
  }

  @Test
  fun `provisioning is guarded by generation`() {
    val (device, _, _) = confirmedDevice()
    val gen = device.markProvisioning()
    assertTrue(gen != 0)
    assertTrue(device.commitProvisioning(gen) { true })
    assertFalse(device.commitProvisioning(gen + 1) { true })
    device.reset()
    assertFalse(device.commitProvisioning(gen) { true })
  }
}

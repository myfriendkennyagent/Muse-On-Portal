package com.myfriendkennyagent.museportal.protocol.pairing

import com.myfriendkennyagent.museportal.protocol.Bytes
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.BigIntegers
import org.json.JSONObject

/** A handshake step failed; [status] is the wire error to report. */
class PairingException(val status: String) : Exception(status)

enum class PairingState { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

/** A P-256 key for the pairing ECDH. */
class P256Key(val d: BigInteger) {
  val publicPoint: ByteArray = Pairing.G.multiply(d).normalize().getEncoded(false)

  /** The ECDH shared secret: the X coordinate of d * peer, 32 bytes. */
  fun exchange(peer: ECPoint): ByteArray {
    val p = peer.multiply(d).normalize()
    if (p.isInfinity) throw IllegalArgumentException("ECDH produced the point at infinity")
    return p.affineXCoord.encoded
  }

  companion object {
    private val random = SecureRandom()

    fun generate(): P256Key = P256Key(BigIntegers.createRandomInRange(BigInteger.ONE, Pairing.N.subtract(BigInteger.ONE), random))
  }
}

/**
 * Muse Gadget BLE pairing, protocol version 5, ported from the Linux Device
 * SDK's `pairing.py`. Community mode only: `pairing_auth` is `"none"` and the
 * policy is `confirm_app`, so a valid client-finished record confirms the
 * session without a physical button.
 *
 * Community pairing protects setup secrets from passive observers. It does not
 * authenticate the ECDH peer, so it cannot stop an active man-in-the-middle.
 */
object Pairing {
  const val VERSION = 5
  const val MODEL = "hatch_link"
  const val SUITE = "p256-hkdf-sha256-aes-gcm-v1"
  const val POLICY_BUTTON = "confirm_press"
  const val POLICY_APP = "confirm_app"
  const val AUTH_OFFICIAL = "fleet_ecdsa_p256_v1"
  const val AUTH_COMMUNITY = "none"
  const val BUTTON_CONFIRM_TIMEOUT_S = 60

  const val RECORD_LABEL = "hatch-link ble setup v1"
  val SESSION_ID_LABEL = "hatch-link session id v1".toByteArray()

  const val CLIENT_FINISHED_TIMEOUT_MS = 60_000L
  const val CONFIRMED_TIMEOUT_MS = 120_000L
  const val PROVISIONING_TIMEOUT_MS = 120_000L

  const val ERROR_INVALID_HELLO = "error_pairing_invalid_hello"
  const val ERROR_DECRYPT = "error_pairing_decrypt"

  internal const val P256_POINT_BYTES = 65
  internal const val NONCE_BYTES = 16
  internal const val SESSION_ID_BYTES = 16
  internal const val TAG_BYTES = 16
  internal const val MAX_CIPHERTEXT_B64_CHARS = 16384
  internal const val TO_DEVICE = 0
  internal const val FROM_DEVICE = 1

  private val P256 = CustomNamedCurves.getByName("secp256r1")
  internal val G: ECPoint = P256.g
  internal val N: BigInteger = P256.n

  fun decodePoint(encoded: ByteArray): ECPoint {
    val p = P256.curve.decodePoint(encoded)
    require(p.isValid && !p.isInfinity) { "invalid P-256 point" }
    return p
  }

  private val DECIMAL = Regex("[0-9]+")
  private val U64_LIMIT = BigInteger.ONE.shiftLeft(64)

  fun parseCounter(text: Any?): Long {
    require(text is String && DECIMAL.matches(text)) { "invalid counter" }
    val v = BigInteger(text)
    require(v < U64_LIMIT) { "counter overflow" }
    require(v.bitLength() < 63) { "counter too large" }
    return v.toLong()
  }

  /** The canonical v5 transcript; SHA-256 of it is the `transcript_hash`. */
  fun buildTranscript(
    community: Boolean,
    authEpoch: Int,
    policy: String,
    deviceId: String,
    nodeId: String,
    mac: String,
    firmwareVersion: String,
    mobilePub: String,
    devicePub: String,
    mobileNonce: String,
    deviceNonce: String,
  ): String {
    val button = policy == POLICY_BUTTON
    require(button || (community && policy == POLICY_APP)) { "unsupported pairing policy: $policy" }
    require(if (community) authEpoch == 0 else authEpoch > 0) { "invalid auth epoch: $authEpoch" }
    val fields = listOf(deviceId, nodeId, mac, firmwareVersion, mobilePub, devicePub, mobileNonce, deviceNonce)
    require(fields.all { it.isNotEmpty() }) { "transcript fields must be non-empty" }
    return listOf(
        "hatch-link-pairing-v$VERSION",
        "version=$VERSION",
        "initiator_role=mobile",
        "responder_role=link",
        "device_id=$deviceId",
        "node_id=$nodeId",
        "mac=$mac",
        "model=$MODEL",
        "firmware_version=$firmwareVersion",
        "selected_cipher_suite=$SUITE",
        "pairing_auth=${if (community) AUTH_COMMUNITY else AUTH_OFFICIAL}",
        "pairing_auth_epoch=$authEpoch",
        "pairing_policy=$policy",
        "confirm_timeout_seconds=${if (button) BUTTON_CONFIRM_TIMEOUT_S else 0}",
        "mobile_pub=$mobilePub",
        "device_pub=$devicePub",
        "mobile_nonce=$mobileNonce",
        "device_nonce=$deviceNonce",
      )
      .joinToString("\n")
  }

  private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    com.myfriendkennyagent.museportal.protocol.noise.NoiseXX.hmacSha256(key, data)

  /** HKDF-Expand for a single 32-byte block. */
  private fun expand32(prk: ByteArray, info: ByteArray): ByteArray = hmac(prk, info + byteArrayOf(1))

  /** Session secret (exposed for the vector tests). */
  fun sessionSecret(ecdhSecret: ByteArray, mobileNonce: ByteArray, deviceNonce: ByteArray, transcriptHash: ByteArray): ByteArray {
    val salt = Bytes.sha256(mobileNonce, deviceNonce, transcriptHash)
    return expand32(hmac(salt, ecdhSecret), RECORD_LABEL.toByteArray())
  }

  /**
   * Returns (mobile_tx_key, mobile_rx_key, session_id). `mobile_tx_key`
   * decrypts records the device receives; `mobile_rx_key` encrypts the ones it
   * sends.
   */
  fun deriveSessionKeys(
    ecdhSecret: ByteArray,
    mobileNonce: ByteArray,
    deviceNonce: ByteArray,
    transcriptHash: ByteArray,
  ): Triple<ByteArray, ByteArray, ByteArray> {
    val secret = sessionSecret(ecdhSecret, mobileNonce, deviceNonce, transcriptHash)
    val mobileTx = expand32(secret, "mobile->device".toByteArray())
    val mobileRx = expand32(secret, "device->mobile".toByteArray())
    val sessionId = Bytes.sha256(SESSION_ID_LABEL, transcriptHash, ecdhSecret).copyOf(SESSION_ID_BYTES)
    return Triple(mobileTx, mobileRx, sessionId)
  }

  fun recordNonce(direction: Int, counter: Long): ByteArray =
    byteArrayOf(direction.toByte(), 0, 0, 0) + Bytes.u64be(counter)

  fun recordAad(sessionIdB64: String, direction: Int, counter: Long): ByteArray {
    val arrow = if (direction == TO_DEVICE) "m2d" else "d2m"
    return "$RECORD_LABEL|$sessionIdB64|$arrow|$counter".toByteArray()
  }

  internal fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray =
    Cipher.getInstance("AES/GCM/NoPadding")
      .apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        updateAAD(aad)
      }
      .doFinal(input)
}

/**
 * One device's pairing state; safe to call from several threads. Methods that
 * advance the handshake return a nonzero generation. Deferred work holds on to
 * it and checks [isCurrent] before acting, so work from an abandoned attempt
 * can't act on a newer one.
 */
class PairingSession(
  private val nodeId: String,
  private val deviceId: String,
  private val mac: String,
  firmwareVersion: String,
  private val sdkToken: String? = null,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val generateKey: () -> P256Key = P256Key::generate,
  private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) {
  private val firmwareVersion = firmwareVersion.ifEmpty { "unknown" }
  private var generation = 0
  private var state = PairingState.IDLE
  private var deadline = 0L
  private var rxKey: ByteArray? = null
  private var txKey: ByteArray? = null
  private var sessionIdB64 = ""
  private var rxCounter = 0L
  private var txCounter = 0L

  init {
    reset()
  }

  /** Pairing fields for the `get_device_info` response. */
  fun deviceInfo(): JSONObject =
    JSONObject()
      .put("device_id", deviceId)
      .put("mac", mac)
      .put("model", Pairing.MODEL)
      .put("pairing_protocol", Pairing.VERSION)
      .put("pairing_auth", Pairing.AUTH_COMMUNITY)
      .put("pairing_auth_epoch", 0)
      .put("pairing_policy", Pairing.POLICY_APP)

  @Synchronized
  fun state(): PairingState {
    expire()
    return state
  }

  val confirmed: Boolean
    get() = state().let { it == PairingState.READY || it == PairingState.PROVISIONING }

  @Synchronized fun isCurrent(gen: Int): Boolean = gen != 0 && gen == generation

  @Synchronized
  fun reset() {
    generation++
    clear()
  }

  /** Starts a session from `pairing_client_hello`; returns `pairing_ready`. */
  @Synchronized
  fun handleHello(message: JSONObject): JSONObject {
    val version = message.opt("version")
    if (
      version !is Number ||
        version.toDouble() != Pairing.VERSION.toDouble() ||
        message.opt("pairing_auth") != Pairing.AUTH_COMMUNITY ||
        message.opt("pairing_policy") != Pairing.POLICY_APP
    ) {
      throw PairingException(Pairing.ERROR_INVALID_HELLO)
    }
    reset()
    val mobilePub: ByteArray
    val mobileNonce: ByteArray
    val peer: org.bouncycastle.math.ec.ECPoint
    try {
      mobilePub = Bytes.unb64url(message.opt("mobile_pub"))
      mobileNonce = Bytes.unb64url(message.opt("mobile_nonce"))
      require(mobilePub.size == Pairing.P256_POINT_BYTES && mobilePub[0] == 0x04.toByte()) { "bad key" }
      require(mobileNonce.size == Pairing.NONCE_BYTES) { "bad nonce" }
      peer = Pairing.decodePoint(mobilePub)
    } catch (e: IllegalArgumentException) {
      reset()
      throw PairingException(Pairing.ERROR_INVALID_HELLO)
    }

    val deviceKey = generateKey()
    val devicePub = deviceKey.publicPoint
    val deviceNonce = randomBytes(Pairing.NONCE_BYTES)
    val transcript =
      Pairing.buildTranscript(
        community = true,
        authEpoch = 0,
        policy = Pairing.POLICY_APP,
        deviceId = deviceId,
        nodeId = nodeId,
        mac = mac,
        firmwareVersion = firmwareVersion,
        mobilePub = Bytes.b64url(mobilePub),
        devicePub = Bytes.b64url(devicePub),
        mobileNonce = Bytes.b64url(mobileNonce),
        deviceNonce = Bytes.b64url(deviceNonce),
      )
    val transcriptHash = Bytes.sha256(transcript.toByteArray())
    val secret = deviceKey.exchange(peer)
    val (mobileTx, mobileRx, sessionId) = Pairing.deriveSessionKeys(secret, mobileNonce, deviceNonce, transcriptHash)
    rxKey = mobileTx
    txKey = mobileRx
    sessionIdB64 = Bytes.b64url(sessionId)
    rxCounter = 0
    txCounter = 0
    state = PairingState.WAIT_CLIENT_FINISHED
    deadline = clock() + Pairing.CLIENT_FINISHED_TIMEOUT_MS
    return JSONObject()
      .put("type", "pairing_ready")
      .put("version", Pairing.VERSION)
      .put("device_id", deviceId)
      .put("node_id", nodeId)
      .put("mac", mac)
      .put("model", Pairing.MODEL)
      .put("firmware_version", firmwareVersion)
      .put("pairing_auth", Pairing.AUTH_COMMUNITY)
      .put("pairing_auth_epoch", 0)
      .put("pairing_policy", Pairing.POLICY_APP)
      .put("device_pub", Bytes.b64url(devicePub))
      .put("device_nonce", Bytes.b64url(deviceNonce))
      .put("transcript_hash", Bytes.b64url(transcriptHash))
      .put("session_id", sessionIdB64)
  }

  /**
   * Opens one mobile-to-device `pairing_encrypted` record. Any failure (wrong
   * session, skipped or replayed counter, bad tag, expiry) clears the session.
   */
  @Synchronized
  fun decrypt(envelope: JSONObject): String {
    if (expire() || state == PairingState.IDLE) {
      reset()
      throw PairingException(Pairing.ERROR_DECRYPT)
    }
    val plaintext =
      try {
        require(envelope.opt("session_id") == sessionIdB64) { "wrong session" }
        val counter = Pairing.parseCounter(envelope.opt("counter"))
        require(counter == rxCounter) { "unexpected counter" }
        val ciphertext = Bytes.unb64url(envelope.opt("ciphertext"), Pairing.MAX_CIPHERTEXT_B64_CHARS)
        val tag = Bytes.unb64url(envelope.opt("tag"))
        require(tag.size == Pairing.TAG_BYTES) { "invalid tag length" }
        val plain =
          Pairing.gcm(
            Cipher.DECRYPT_MODE,
            rxKey!!,
            Pairing.recordNonce(Pairing.TO_DEVICE, counter),
            Pairing.recordAad(sessionIdB64, Pairing.TO_DEVICE, counter),
            ciphertext + tag,
          )
        strictUtf8(plain)
      } catch (e: IllegalArgumentException) {
        reset()
        throw PairingException(Pairing.ERROR_DECRYPT)
      } catch (e: AEADBadTagException) {
        reset()
        throw PairingException(Pairing.ERROR_DECRYPT)
      }
    rxCounter++
    return plaintext
  }

  /**
   * Confirms the session after the first decrypted record, which must be
   * exactly `{"action": "pairing_client_finished"}`. Under `confirm_app` the
   * app already collected consent. Returns the new generation, or 0 after
   * clearing the session if the record is invalid.
   */
  @Synchronized
  fun handleClientFinished(command: JSONObject): Int {
    val ok =
      command.length() == 1 &&
        command.opt("action") == "pairing_client_finished" &&
        !expire() &&
        state == PairingState.WAIT_CLIENT_FINISHED &&
        rxCounter == 1L
    if (!ok) {
      reset()
      return 0
    }
    generation++
    state = PairingState.READY
    deadline = clock() + Pairing.CONFIRMED_TIMEOUT_MS
    return generation
  }

  /** Enters provisioning from a confirmed session; returns its generation, or 0. */
  @Synchronized
  fun markProvisioning(): Int {
    if (!expire() && state == PairingState.READY) {
      generation++
      state = PairingState.PROVISIONING
      deadline = clock() + Pairing.PROVISIONING_TIMEOUT_MS
    }
    return if (state == PairingState.PROVISIONING) generation else 0
  }

  @Synchronized
  fun extendProvisioning(gen: Int): Boolean {
    val valid = provisioning(gen)
    if (valid) deadline = clock() + Pairing.PROVISIONING_TIMEOUT_MS
    return valid
  }

  /** Runs [commit] under the pairing lock if the session is still valid. Local state only. */
  @Synchronized
  fun commitProvisioning(gen: Int, commit: () -> Boolean): Boolean = provisioning(gen) && commit()

  /** Seals a device-to-mobile record, or null with no session or a stale [gen]. */
  @Synchronized
  fun encryptJson(plaintext: String, gen: Int = 0): JSONObject? {
    if ((gen != 0 && gen != generation) || expire() || state == PairingState.IDLE) return null
    val counter = txCounter
    val sealed =
      Pairing.gcm(
        Cipher.ENCRYPT_MODE,
        txKey!!,
        Pairing.recordNonce(Pairing.FROM_DEVICE, counter),
        Pairing.recordAad(sessionIdB64, Pairing.FROM_DEVICE, counter),
        plaintext.toByteArray(Charsets.UTF_8),
      )
    txCounter++
    return JSONObject()
      .put("type", "pairing_encrypted")
      .put("session_id", sessionIdB64)
      .put("counter", counter.toString())
      .put("ciphertext", Bytes.b64url(sealed.copyOfRange(0, sealed.size - Pairing.TAG_BYTES)))
      .put("tag", Bytes.b64url(sealed.copyOfRange(sealed.size - Pairing.TAG_BYTES, sealed.size)))
  }

  fun encryptStatus(status: String, gen: Int = 0): JSONObject? {
    val message = JSONObject().put("type", "status").put("status", status)
    // Apps read only type and status, so older ones ignore the token.
    if (sdkToken != null && status == "pairing_confirmed") message.put("sdk_token", sdkToken)
    return encryptJson(message.toString(), gen)
  }

  private fun provisioning(gen: Int): Boolean =
    gen != 0 && gen == generation && !expire() && state == PairingState.PROVISIONING

  private fun clear() {
    state = PairingState.IDLE
    deadline = 0
    rxKey = null
    txKey = null
    sessionIdB64 = ""
    rxCounter = 0
    txCounter = 0
  }

  /** Drops the keys but keeps the generation, so the owner can still recognise it. */
  private fun expire(): Boolean {
    if (state == PairingState.IDLE || clock() <= deadline) return false
    clear()
    return true
  }

  private fun strictUtf8(bytes: ByteArray): String =
    try {
      Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
      throw IllegalArgumentException("invalid utf-8")
    }
}

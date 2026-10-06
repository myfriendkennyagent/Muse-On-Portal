package com.myfriendkennyagent.museportal.protocol.noise

import com.myfriendkennyagent.museportal.protocol.Bytes
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.math.ec.rfc7748.X25519

/** Raised when a Noise handshake or transport state machine is violated. */
class NoiseProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * `Noise_XX_25519_AESGCM_SHA256`, ported from the Linux Device SDK's
 * `noise/noise_xx.py`. Android 10's JCA has no X25519, so the DH comes from
 * Bouncy Castle; AES-GCM and HMAC use the platform providers.
 */
object NoiseXX {
  val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256".toByteArray(Charsets.US_ASCII)
  const val DH_KEY_LEN = 32
  const val AEAD_TAG_LEN = 16
  const val MIN_MSG2_LEN = DH_KEY_LEN + (DH_KEY_LEN + AEAD_TAG_LEN) + AEAD_TAG_LEN
  const val MIN_MSG3_LEN = DH_KEY_LEN + AEAD_TAG_LEN + AEAD_TAG_LEN
  const val MAX_SAFE_NONCE = (1L shl 53) - 1

  private val LOW_ORDER_POINTS: List<ByteArray> =
    listOf(
        "0000000000000000000000000000000000000000000000000000000000000000",
        "0100000000000000000000000000000000000000000000000000000000000000",
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
        "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
      )
      .map(Bytes::unhex)

  internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
  }

  internal fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
    require(outputs == 2 || outputs == 3) { "hkdf supports 2 or 3 outputs" }
    val temp = hmacSha256(chainingKey, ikm)
    val o1 = hmacSha256(temp, byteArrayOf(1))
    val o2 = hmacSha256(temp, Bytes.concat(o1, byteArrayOf(2)))
    if (outputs == 2) return listOf(o1, o2)
    val o3 = hmacSha256(temp, Bytes.concat(o2, byteArrayOf(3)))
    return listOf(o1, o2, o3)
  }

  fun nonceIv(nonce: Long): ByteArray {
    if (nonce < 0) throw NoiseProtocolException("nonce outside uint64 range")
    return Bytes.concat(ByteArray(4), Bytes.u64be(nonce))
  }

  internal fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    if (publicKey.size != DH_KEY_LEN) throw NoiseProtocolException("x25519: invalid public key length")
    if (LOW_ORDER_POINTS.any { Bytes.equal(it, publicKey) }) {
      throw NoiseProtocolException("x25519: rejected low-order public key")
    }
    val shared = ByteArray(DH_KEY_LEN)
    X25519.scalarMult(privateKey, 0, publicKey, 0, shared, 0)
    if (Bytes.equal(shared, ByteArray(DH_KEY_LEN))) {
      throw NoiseProtocolException("x25519: DH produced all-zeros output")
    }
    return shared
  }
}

/** An X25519 key pair; [privateKey] is the 32-byte scalar. */
class X25519KeyPair(val privateKey: ByteArray) {
  val publicKey: ByteArray = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(privateKey, 0, it, 0) }

  companion object {
    private val random = SecureRandom()

    fun generate(): X25519KeyPair {
      val sk = ByteArray(X25519.SCALAR_SIZE)
      X25519.generatePrivateKey(random, sk)
      return X25519KeyPair(sk)
    }
  }
}

/** One direction of an established Noise session. */
class CipherState {
  private var key: SecretKeySpec? = null
  private var nonce = 0L
  private var poisoned = false

  @Synchronized
  fun initializeKey(raw: ByteArray) {
    assertAlive()
    if (raw.size != 32) throw NoiseProtocolException("CipherState: AES-GCM key must be 32 bytes")
    key = SecretKeySpec(raw.copyOf(), "AES")
    nonce = 0
  }

  fun hasKey(): Boolean = key != null

  @Synchronized
  fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
    assertAlive()
    val k = key ?: return plaintext
    val n = nextNonce()
    return try {
      cipher(Cipher.ENCRYPT_MODE, k, n, ad).doFinal(plaintext)
    } catch (e: Exception) {
      poisoned = true
      throw e
    }
  }

  @Synchronized
  fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
    assertAlive()
    val k = key ?: return ciphertext
    val n = nextNonce()
    return try {
      cipher(Cipher.DECRYPT_MODE, k, n, ad).doFinal(ciphertext)
    } catch (e: AEADBadTagException) {
      poisoned = true
      throw NoiseProtocolException("CipherState: decrypt failed", e)
    } catch (e: Exception) {
      poisoned = true
      throw e
    }
  }

  private fun cipher(mode: Int, k: SecretKeySpec, n: Long, ad: ByteArray): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
      init(mode, k, GCMParameterSpec(NoiseXX.AEAD_TAG_LEN * 8, NoiseXX.nonceIv(n)))
      updateAAD(ad)
    }

  private fun assertAlive() {
    if (poisoned) throw NoiseProtocolException("CipherState: poisoned after prior failure")
  }

  private fun nextNonce(): Long {
    if (nonce >= NoiseXX.MAX_SAFE_NONCE) {
      poisoned = true
      throw NoiseProtocolException("CipherState: nonce exhausted")
    }
    return nonce++
  }
}

internal class SymmetricState {
  private var ck = ByteArray(32)
  private var h = ByteArray(32)
  private var cipher = CipherState()

  fun initialize() {
    val padded = ByteArray(32)
    NoiseXX.PROTOCOL_NAME.copyInto(padded)
    h = padded
    ck = h.copyOf()
    mixHash(ByteArray(0))
  }

  fun mixHash(data: ByteArray) {
    h = Bytes.sha256(h, data)
  }

  fun mixKey(ikm: ByteArray) {
    val (newCk, tempK) = NoiseXX.hkdf(ck, ikm, 2)
    ck = newCk
    cipher = CipherState().also { it.initializeKey(tempK) }
  }

  fun encryptAndHash(plaintext: ByteArray): ByteArray {
    val ct = cipher.encryptWithAd(h, plaintext)
    mixHash(ct)
    return ct
  }

  fun decryptAndHash(ciphertext: ByteArray): ByteArray {
    val pt = cipher.decryptWithAd(h, ciphertext)
    mixHash(ciphertext)
    return pt
  }

  fun split(): Pair<CipherState, CipherState> {
    val (k1, k2) = NoiseXX.hkdf(ck, ByteArray(0), 2)
    ck = ByteArray(32)
    h = ByteArray(32)
    return CipherState().also { it.initializeKey(k1) } to CipherState().also { it.initializeKey(k2) }
  }

  fun handshakeHash(): ByteArray = h.copyOf()
}

private enum class Phase { CREATED, INITIALIZED, MSG1_SENT, MSG2_READ, MSG3_SENT, SPLIT, DEAD }

/**
 * The device side of the handshake. The device uses a fresh static key per
 * connection: the VM bearer already authenticated it at the WebSocket upgrade.
 */
class NoiseXXInitiator(private val generateKey: () -> X25519KeyPair = X25519KeyPair::generate) {
  private val ss = SymmetricState()
  private var e: X25519KeyPair? = null
  private var re: ByteArray? = null
  private var rs: ByteArray? = null
  private var phase = Phase.CREATED

  private fun require(expected: Phase, method: String) {
    if (phase == Phase.DEAD) throw NoiseProtocolException("NoiseXX: $method called on dead handshake")
    if (phase != expected) {
      throw NoiseProtocolException("NoiseXX: $method called in wrong phase (expected $expected, got $phase)")
    }
  }

  private inline fun <T> orDie(block: () -> T): T =
    try {
      block()
    } catch (t: Throwable) {
      phase = Phase.DEAD
      throw t
    }

  fun initialize() {
    require(Phase.CREATED, "initialize")
    ss.initialize()
    phase = Phase.INITIALIZED
  }

  fun writeMessage1(): ByteArray {
    require(Phase.INITIALIZED, "writeMessage1")
    return orDie {
      val eph = generateKey().also { e = it }
      ss.mixHash(eph.publicKey)
      ss.encryptAndHash(ByteArray(0))
      phase = Phase.MSG1_SENT
      eph.publicKey.copyOf()
    }
  }

  fun readMessage2(msg: ByteArray): ByteArray {
    require(Phase.MSG1_SENT, "readMessage2")
    if (msg.size < NoiseXX.MIN_MSG2_LEN) {
      phase = Phase.DEAD
      throw NoiseProtocolException("NoiseXX: message 2 too short (${msg.size} < ${NoiseXX.MIN_MSG2_LEN})")
    }
    return orDie {
      val eph = e ?: throw NoiseProtocolException("NoiseXX: missing initiator ephemeral key")
      var off = 0
      val remoteE = msg.copyOfRange(off, off + NoiseXX.DH_KEY_LEN).also { re = it }
      ss.mixHash(remoteE)
      off += NoiseXX.DH_KEY_LEN
      ss.mixKey(NoiseXX.dh(eph.privateKey, remoteE))
      val sLen = NoiseXX.DH_KEY_LEN + NoiseXX.AEAD_TAG_LEN
      val remoteS = ss.decryptAndHash(msg.copyOfRange(off, off + sLen)).also { rs = it }
      off += sLen
      ss.mixKey(NoiseXX.dh(eph.privateKey, remoteS))
      val payload = ss.decryptAndHash(msg.copyOfRange(off, msg.size))
      phase = Phase.MSG2_READ
      payload
    }
  }

  fun writeMessage3(): ByteArray {
    require(Phase.MSG2_READ, "writeMessage3")
    return orDie {
      val s = generateKey()
      val encS = ss.encryptAndHash(s.publicKey)
      val remoteE = re ?: throw NoiseProtocolException("NoiseXX: missing responder ephemeral key")
      ss.mixKey(NoiseXX.dh(s.privateKey, remoteE))
      val encPayload = ss.encryptAndHash(ByteArray(0))
      phase = Phase.MSG3_SENT
      Bytes.concat(encS, encPayload)
    }
  }

  /** Returns (send, receive) cipher states. */
  fun split(): Pair<CipherState, CipherState> {
    require(Phase.MSG3_SENT, "split")
    phase = Phase.SPLIT
    val result = ss.split()
    e = null
    re = null
    rs = null
    return result
  }

  fun remoteStaticPublicKey(): ByteArray? = rs?.copyOf()

  fun handshakeHash(): ByteArray = ss.handshakeHash()
}

/** The VM side of the handshake, for tests and the fake VM. */
class NoiseXXResponder(
  private val payload: ByteArray = ByteArray(0),
  private val generateKey: () -> X25519KeyPair = X25519KeyPair::generate,
) {
  private val ss = SymmetricState()
  private var e: X25519KeyPair? = null
  private var phase = Phase.CREATED

  fun initialize() {
    check(phase == Phase.CREATED)
    ss.initialize()
    phase = Phase.INITIALIZED
  }

  fun readMessage1AndWriteMessage2(msg1: ByteArray): ByteArray {
    check(phase == Phase.INITIALIZED)
    if (msg1.size < NoiseXX.DH_KEY_LEN) throw NoiseProtocolException("NoiseXX: message 1 too short")
    val re = msg1.copyOfRange(0, NoiseXX.DH_KEY_LEN)
    ss.mixHash(re)
    ss.decryptAndHash(msg1.copyOfRange(NoiseXX.DH_KEY_LEN, msg1.size))
    val eph = generateKey().also { e = it }
    ss.mixHash(eph.publicKey)
    ss.mixKey(NoiseXX.dh(eph.privateKey, re))
    val s = generateKey()
    val encS = ss.encryptAndHash(s.publicKey)
    ss.mixKey(NoiseXX.dh(s.privateKey, re))
    val encPayload = ss.encryptAndHash(payload)
    phase = Phase.MSG2_READ
    return Bytes.concat(eph.publicKey, encS, encPayload)
  }

  fun readMessage3(msg3: ByteArray) {
    check(phase == Phase.MSG2_READ)
    if (msg3.size < NoiseXX.MIN_MSG3_LEN) throw NoiseProtocolException("NoiseXX: message 3 too short")
    val sLen = NoiseXX.DH_KEY_LEN + NoiseXX.AEAD_TAG_LEN
    val rs = ss.decryptAndHash(msg3.copyOfRange(0, sLen))
    ss.mixKey(NoiseXX.dh(e!!.privateKey, rs))
    ss.decryptAndHash(msg3.copyOfRange(sLen, msg3.size))
    phase = Phase.MSG3_SENT
  }

  /** Returns (send, receive) from the responder's point of view. */
  fun split(): Pair<CipherState, CipherState> {
    check(phase == Phase.MSG3_SENT)
    phase = Phase.SPLIT
    val (c1, c2) = ss.split()
    return c2 to c1
  }

  fun handshakeHash(): ByteArray = ss.handshakeHash()
}

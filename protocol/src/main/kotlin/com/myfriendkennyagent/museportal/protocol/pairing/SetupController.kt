package com.myfriendkennyagent.museportal.protocol.pairing

import com.myfriendkennyagent.museportal.protocol.Identity
import com.myfriendkennyagent.museportal.protocol.ble.BleFraming
import com.myfriendkennyagent.museportal.protocol.ble.ChunkAssembler
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.logging.Logger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Device-side BLE link: notifies packets to the phone. */
interface SetupTransport {
  /** Notifies each packet in order; blocks until all are queued. */
  fun sendPackets(packets: List<ByteArray>)

  fun mtu(): Int

  fun disconnect(delayMs: Long)
}

interface SetupNetwork {
  fun isOnline(): Boolean

  /** The single Wi-Fi scan entry offered when the device is already online. */
  fun currentConnectionEntry(): JSONObject
}

/** Device credentials handed over by the Muse app in `provision_v2`. */
data class Credentials(
  val accessToken: String,
  val refreshToken: String,
  val username: String,
  val apiUrl: String,
  val apiUrlV2: String,
  val noiseHost: String,
)

/** Setup could not finish; [status] is what the app is told. */
class ProvisionFailed(val status: String) : Exception(status)

/**
 * Verifies [Credentials] and saves them. It must persist only through the
 * `commit` function it is given (which runs under the pairing lock) and throw
 * [ProvisionFailed] on failure.
 */
typealias Provisioner = (credentials: Credentials, commit: (save: () -> Boolean) -> Boolean) -> Unit

/**
 * The BLE setup commands behind the GATT characteristics, ported from
 * `ble_setup.py`. Independent of the Bluetooth stack: a transport delivers
 * writes and sends framed notifications. Messages are handled one at a time,
 * in arrival order, and every send happens under one lock so encrypted record
 * counters reach the phone in order.
 */
class SetupController(
  private val pairing: PairingSession,
  private val identity: Identity,
  private val version: String,
  private val transport: SetupTransport,
  private val network: SetupNetwork,
  private val provision: Provisioner,
  private val onComplete: () -> Unit = {},
) {
  private val log = Logger.getLogger("SetupController")
  private val assembler = ChunkAssembler()
  private val inbox = LinkedBlockingQueue<Any>()
  private val txLock = Any()
  private val stateLock = Any()
  private var plaintextBlocked = false
  private var provisioning = false
  private var worker: Thread? = null

  // -- Transport callbacks (any thread) --------------------------------------

  fun onWrite(packet: ByteArray) {
    assembler.feed(packet)?.let { inbox.put(it) }
  }

  fun onDisconnect() {
    log.info("BLE client disconnected; clearing pairing session")
    assembler.reset()
    synchronized(stateLock) { plaintextBlocked = false }
    pairing.reset()
  }

  // -- Lifecycle --------------------------------------------------------------

  fun start() {
    worker =
      Thread({
          while (true) {
            val message = inbox.take()
            if (message === STOP) return@Thread
            try {
              handleMessage(message as ByteArray)
            } catch (e: Exception) {
              log.warning("setup command failed: $e")
            }
          }
        }, "ble-setup")
        .apply {
          isDaemon = true
          start()
        }
  }

  fun stop() {
    inbox.put(STOP)
  }

  // -- Dispatch ---------------------------------------------------------------

  fun handleMessage(raw: ByteArray, decrypted: Boolean = false) {
    val command =
      try {
        JSONObject(String(raw, Charsets.UTF_8))
      } catch (e: JSONException) {
        log.warning("invalid command JSON (${raw.size} bytes)")
        sendStatus("error_invalid_command")
        return
      }
    val action = command.opt("action") as? String ?: ""
    log.info("RX action: ${action.ifEmpty { "?" }}${if (decrypted) " (encrypted)" else ""}")
    val blocked = synchronized(stateLock) { plaintextBlocked }

    when {
      !decrypted && action == "pairing_client_hello" -> handleHello(command)
      !decrypted && action == "pairing_encrypted" -> handleRecord(command)
      // Public metadata, safe in plaintext at any point. Apps re-read it when
      // they restart a handshake on the same connection.
      action == "get_device_info" -> sendJson(deviceInfo())
      !decrypted && blocked -> log.warning("plaintext command ignored after pairing started: $action")
      !decrypted && action in SENSITIVE_ACTIONS -> sendStatus("error_encryption_required")
      decrypted && action == "pairing_client_finished" -> handleClientFinished(command)
      decrypted && action in SENSITIVE_ACTIONS && !pairing.confirmed -> sendStatus("error_pairing_confirm_required")
      decrypted && action == "wifi_scan" -> handleWifiScan()
      decrypted && action == "provision_v2" -> handleProvision(command)
      else -> sendStatus("error_unknown_action")
    }
  }

  fun deviceInfo(): JSONObject {
    val info =
      JSONObject().put("type", "device_info").put("node_id", identity.nodeId).put("version", version)
    val pairingInfo = pairing.deviceInfo()
    for (key in pairingInfo.keys()) info.put(key, pairingInfo.get(key))
    return info.put("build_sha", "").put("network_ready", network.isOnline())
  }

  private fun handleHello(command: JSONObject) {
    val ready =
      try {
        pairing.handleHello(command)
      } catch (e: PairingException) {
        sendStatus(e.status)
        return
      }
    synchronized(stateLock) { plaintextBlocked = true }
    sendJson(ready)
  }

  private fun handleRecord(envelope: JSONObject) {
    val plaintext =
      try {
        pairing.decrypt(envelope)
      } catch (e: PairingException) {
        sendStatus(e.status)
        transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
        return
      }
    handleMessage(plaintext.toByteArray(Charsets.UTF_8), decrypted = true)
  }

  private fun handleClientFinished(command: JSONObject) {
    val gen = pairing.handleClientFinished(command)
    if (gen == 0) {
      sendStatus("error_pairing_decrypt")
      transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
      return
    }
    log.info("pairing confirmed (app consent)")
    sendStatus("pairing_confirmed", gen)
  }

  private fun handleWifiScan() {
    val networks = JSONArray()
    if (network.isOnline()) networks.put(network.currentConnectionEntry())
    sendEncryptedJson(JSONObject().put("type", "wifi_scan_result").put("networks", networks))
  }

  private fun handleProvision(command: JSONObject) {
    fun text(key: String): String = command.opt(key) as? String ?: ""
    if (
      command.opt("ssid") !is String ||
        command.opt("password") !is String ||
        text("access_token").isEmpty() ||
        text("refresh_token").isEmpty() ||
        text("token_type") != "device"
    ) {
      sendStatus("error_missing_credentials")
      return
    }
    var gen = 0
    val refusal: String? =
      synchronized(stateLock) {
        if (provisioning) {
          "error_operation_in_progress"
        } else {
          gen = pairing.markProvisioning()
          provisioning = gen != 0
          if (gen != 0) null else "error_pairing_confirm_required"
        }
      }
    if (refusal != null) {
      sendStatus(refusal)
      return
    }
    // The Wi-Fi fields are deliberately dropped: this device only sets up
    // when it is already online.
    val credentials =
      Credentials(
        accessToken = text("access_token"),
        refreshToken = text("refresh_token"),
        username = text("username"),
        apiUrl = text("api_url"),
        apiUrlV2 = text("api_url_v2"),
        noiseHost = text("noise_host"),
      )
    val generation = gen
    Executors.newSingleThreadExecutor().apply {
      execute { runProvision(credentials, generation) }
      shutdown()
    }
  }

  private fun runProvision(credentials: Credentials, gen: Int) {
    try {
      sendStatus("wifi_connecting", gen)
      if (!network.isOnline()) {
        // Stay in provisioning so the app can retry, as the firmware does.
        pairing.extendProvisioning(gen)
        sendStatus("wifi_failed", gen)
        return
      }
      sendStatus("wifi_connected", gen)
      try {
        provision(credentials) { save -> pairing.commitProvisioning(gen, save) }
      } catch (e: ProvisionFailed) {
        log.warning("provisioning failed: ${e.status}")
        sendStatus(e.status, gen)
        transport.disconnect(500)
        return
      }
      sendStatus("auth_ok", gen)
      log.info("setup complete")
      onComplete()
    } finally {
      synchronized(stateLock) { provisioning = false }
    }
  }

  // -- Sending ----------------------------------------------------------------

  fun sendStatus(status: String, gen: Int = 0) {
    synchronized(txLock) {
      val envelope = pairing.encryptStatus(status, gen)
      if (envelope != null) {
        send(envelope.toString())
        log.info("TX status (encrypted #${envelope.optString("counter")}): $status")
        return
      }
      val blocked = synchronized(stateLock) { plaintextBlocked }
      if (gen != 0 || blocked || status !in PLAINTEXT_STATUSES) {
        log.info("TX status suppressed: $status")
        return
      }
      transport.sendPackets(listOf(status.toByteArray(Charsets.UTF_8)))
      log.info("TX status: $status")
    }
  }

  fun sendJson(obj: JSONObject) {
    synchronized(txLock) {
      send(obj.toString())
      log.info("TX ${obj.optString("type")}")
    }
  }

  fun sendEncryptedJson(obj: JSONObject, gen: Int = 0) {
    synchronized(txLock) {
      val envelope = pairing.encryptJson(obj.toString(), gen)
      if (envelope == null) {
        log.info("TX ${obj.optString("type")} suppressed: no session")
        return
      }
      send(envelope.toString())
    }
  }

  private fun send(text: String) {
    transport.sendPackets(BleFraming.encodeChunks(text.toByteArray(Charsets.UTF_8), transport.mtu()))
  }

  companion object {
    private val STOP = Any()
    val SENSITIVE_ACTIONS =
      setOf("provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth")
    val PLAINTEXT_STATUSES =
      setOf(
        "error_encryption_required",
        "error_pairing_invalid_hello",
        "error_pairing_unavailable",
        "error_pairing_decrypt",
      )
    const val DISCONNECT_AFTER_ERROR_MS = 300L

    /** GATT layout the Muse apps look for. */
    const val SERVICE_UUID = "7fdd3d1c-38ea-46cf-8b46-314ecf5f240c"
    const val RX_UUID = "4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"
    const val TX_UUID = "d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c"

    /** Manufacturer data the apps read as the device's paired flag. */
    const val PAIRED_FLAG_COMPANY_ID = 0xFFFF
  }
}

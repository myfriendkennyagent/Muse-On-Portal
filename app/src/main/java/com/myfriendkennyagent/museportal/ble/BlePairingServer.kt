package com.myfriendkennyagent.museportal.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import com.myfriendkennyagent.museportal.BuildConfig
import com.myfriendkennyagent.museportal.MuseHub
import com.myfriendkennyagent.museportal.PairingStatus
import com.myfriendkennyagent.museportal.protocol.Identity
import com.myfriendkennyagent.museportal.protocol.PairingRecord
import com.myfriendkennyagent.museportal.protocol.api.MuseApi
import com.myfriendkennyagent.museportal.protocol.pairing.Credentials
import com.myfriendkennyagent.museportal.protocol.pairing.PairingSession
import com.myfriendkennyagent.museportal.protocol.pairing.ProvisionFailed
import com.myfriendkennyagent.museportal.protocol.pairing.SetupController
import com.myfriendkennyagent.museportal.protocol.pairing.SetupNetwork
import com.myfriendkennyagent.museportal.protocol.pairing.SetupTransport
import com.myfriendkennyagent.museportal.store.FileDeviceStore
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Lets the Muse phone app pair with the Portal over Bluetooth LE, the way it
 * pairs a Raspberry Pi running the Linux SDK: the Portal advertises as
 * `MuseGadgetXXXXXX` with the setup service, and [SetupController] runs the
 * pairing v5 handshake over two characteristics.
 *
 * Whether a Portal can advertise as a BLE peripheral is checked at start; if
 * not, pair on a laptop instead (docs/PAIRING.md).
 */
@SuppressLint("MissingPermission") // BLUETOOTH and BLUETOOTH_ADMIN are install-time on Android 10
class BlePairingServer(
  private val context: Context,
  private val store: FileDeviceStore,
  private val api: MuseApi,
  private val onPaired: () -> Unit,
) : SetupTransport {
  private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  private val adapter: BluetoothAdapter? = manager.adapter
  private val thread = HandlerThread("ble-setup").apply { start() }
  private val handler = Handler(thread.looper)

  private var server: BluetoothGattServer? = null
  private var tx: BluetoothGattCharacteristic? = null
  private var controller: SetupController? = null
  private var savedName: String? = null
  private var running = false

  @Volatile private var device: BluetoothDevice? = null
  @Volatile private var mtu = DEFAULT_MTU
  private val notifySent = Semaphore(0)
  private val prepared = HashMap<String, ByteArrayOutputStream>()

  /** Starts advertising for [windowMs]; returns a reason it can't, or null. */
  fun start(windowMs: Long = 10 * 60_000L): String? {
    if (running) return null
    val adapter = adapter ?: return "This Portal has no Bluetooth adapter."
    if (!adapter.isEnabled) {
      @Suppress("DEPRECATION") adapter.enable()
      // Turning the radio on takes a moment.
      repeat(50) { if (!adapter.isEnabled) Thread.sleep(100) }
      if (!adapter.isEnabled) return "Bluetooth is off and couldn't be turned on."
    }
    val advertiser = adapter.bluetoothLeAdvertiser
    if (advertiser == null || !adapter.isMultipleAdvertisementSupported) {
      return "This Portal can't advertise over Bluetooth LE. Pair from a laptop instead (see docs/PAIRING.md)."
    }
    val identity = store.identity()
    val pairing =
      PairingSession(
        nodeId = identity.nodeId,
        deviceId = identity.deviceId,
        mac = identity.mac,
        firmwareVersion = BuildConfig.VERSION_NAME,
        sdkToken = store.sdkToken(),
      )
    controller =
      SetupController(
        pairing = pairing,
        identity = identity,
        version = BuildConfig.VERSION_NAME,
        transport = this,
        network = AndroidNetwork(context),
        provision = ::provision,
        onComplete = {
          MuseHub.pairing.value = PairingStatus.Paired
          handler.postDelayed({ stop() }, 1500)
          onPaired()
        },
      )
        .also { it.start() }

    server = manager.openGattServer(context, callback)?.also { it.addService(buildService()) }
    if (server == null) {
      stop()
      return "Couldn't open a Bluetooth GATT server."
    }
    // The phone reads the GAP name as well as the advertised one.
    savedName = adapter.name
    adapter.name = identity.bleName

    val settings =
      AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .setTimeout(0)
        .build()
    val data =
      AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(UUID.fromString(SetupController.SERVICE_UUID)))
        .addManufacturerData(SetupController.PAIRED_FLAG_COMPANY_ID, byteArrayOf(0))
        .setIncludeDeviceName(false)
        .build()
    val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
    advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    running = true
    val deadline = System.currentTimeMillis() + windowMs
    MuseHub.pairing.value = PairingStatus.Advertising(identity.bleName, windowMs / 1000)
    tickWindow(identity.bleName, deadline)
    Log.i(TAG, "pairing open as ${identity.bleName}")
    return null
  }

  private fun tickWindow(name: String, deadline: Long) {
    handler.postDelayed(
      {
        if (!running) return@postDelayed
        val left = (deadline - System.currentTimeMillis()) / 1000
        if (left <= 0) {
          stop()
          MuseHub.pairing.value = PairingStatus.Failed("Pairing window closed. Start it again to retry.")
        } else {
          if (MuseHub.pairing.value is PairingStatus.Advertising) {
            MuseHub.pairing.value = PairingStatus.Advertising(name, left)
          }
          tickWindow(name, deadline)
        }
      },
      1000,
    )
  }

  fun stop() {
    if (!running && server == null) return
    running = false
    try {
      adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    } catch (e: Exception) {}
    server?.close()
    server = null
    controller?.stop()
    controller = null
    savedName?.let { adapter?.name = it }
    savedName = null
    if (MuseHub.pairing.value is PairingStatus.Advertising || MuseHub.pairing.value is PairingStatus.InProgress) {
      MuseHub.pairing.value = PairingStatus.Idle
    }
  }

  private fun provision(credentials: Credentials, commit: (() -> Boolean) -> Boolean) {
    MuseHub.pairing.value = PairingStatus.InProgress("Checking the device token with Muse…")
    val apiUrlV2 = credentials.apiUrlV2.takeIf { it.startsWith("https://") } ?: ""
    val (vms, status) = api.fetchVms(credentials.accessToken, MuseApi.apiRoot(apiUrlV2))
    if (vms.isEmpty()) {
      Log.w(TAG, "device token check failed (HTTP $status)")
      MuseHub.pairing.value = PairingStatus.Failed("Muse didn't accept the device token (HTTP $status).")
      throw ProvisionFailed("auth_failed")
    }
    val record =
      PairingRecord(
        accessToken = credentials.accessToken,
        refreshToken = credentials.refreshToken,
        username = credentials.username,
        apiUrl = credentials.apiUrl.takeIf { it.startsWith("https://") } ?: "",
        apiUrlV2 = apiUrlV2,
        noiseHost = credentials.noiseHost,
      )
    if (!commit { store.savePairing(record).let { true } }) throw ProvisionFailed("error_storage")
  }

  private fun buildService(): BluetoothGattService {
    val service = BluetoothGattService(UUID.fromString(SetupController.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
    val rx =
      BluetoothGattCharacteristic(
        UUID.fromString(SetupController.RX_UUID),
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
      )
    val txChar =
      BluetoothGattCharacteristic(
        UUID.fromString(SetupController.TX_UUID),
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
      )
    txChar.addDescriptor(
      BluetoothGattDescriptor(
        CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
      )
    )
    service.addCharacteristic(rx)
    service.addCharacteristic(txChar)
    tx = txChar
    return service
  }

  private val advertiseCallback =
    object : AdvertiseCallback() {
      override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
        Log.i(TAG, "advertising")
      }

      override fun onStartFailure(errorCode: Int) {
        Log.w(TAG, "advertising failed: $errorCode")
        MuseHub.pairing.value = PairingStatus.Failed("Bluetooth advertising failed (error $errorCode). Pair from a laptop instead.")
        handler.post { stop() }
      }
    }

  private val callback =
    object : BluetoothGattServerCallback() {
      override fun onConnectionStateChange(dev: BluetoothDevice, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
          Log.i(TAG, "phone connected")
          device = dev
          MuseHub.pairing.value = PairingStatus.InProgress("Phone connected…")
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED && dev.address == device?.address) {
          Log.i(TAG, "phone disconnected")
          device = null
          mtu = DEFAULT_MTU
          prepared.clear()
          controller?.onDisconnect()
        }
      }

      override fun onMtuChanged(dev: BluetoothDevice, newMtu: Int) {
        Log.i(TAG, "ATT MTU $newMtu")
        mtu = newMtu
      }

      override fun onCharacteristicWriteRequest(
        dev: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
      ) {
        val data = value ?: ByteArray(0)
        if (preparedWrite) {
          prepared.getOrPut(dev.address) { ByteArrayOutputStream() }.write(data)
        } else if (characteristic.uuid.toString() == SetupController.RX_UUID) {
          controller?.onWrite(data)
        }
        if (responseNeeded) server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
      }

      override fun onExecuteWrite(dev: BluetoothDevice, requestId: Int, execute: Boolean) {
        val data = prepared.remove(dev.address)?.toByteArray()
        if (execute && data != null) controller?.onWrite(data)
        server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
      }

      override fun onCharacteristicReadRequest(dev: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
        server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
      }

      override fun onDescriptorWriteRequest(
        dev: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
      ) {
        descriptor.value = value
        if (responseNeeded) server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
      }

      override fun onDescriptorReadRequest(dev: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
        server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, 0, descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
      }

      override fun onNotificationSent(dev: BluetoothDevice, status: Int) {
        notifySent.release()
      }
    }

  // -- SetupTransport ---------------------------------------------------------

  override fun mtu(): Int = mtu

  override fun sendPackets(packets: List<ByteArray>) {
    val dev = device ?: return
    val s = server ?: return
    val characteristic = tx ?: return
    for ((i, packet) in packets.withIndex()) {
      if (i > 0) Thread.sleep(com.myfriendkennyagent.museportal.protocol.ble.BleFraming.CHUNK_STAGGER_MS)
      notifySent.drainPermits()
      characteristic.value = packet
      if (!s.notifyCharacteristicChanged(dev, characteristic, false)) {
        Log.w(TAG, "notify failed")
        return
      }
      // Android allows one notification in flight at a time.
      notifySent.tryAcquire(1, TimeUnit.SECONDS)
    }
  }

  override fun disconnect(delayMs: Long) {
    handler.postDelayed({ device?.let { server?.cancelConnection(it) } }, delayMs)
  }

  private class AndroidNetwork(private val context: Context) : SetupNetwork {
    override fun isOnline(): Boolean {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
      return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** One open network, "the current connection": the device is already online and ignores Wi-Fi fields. */
    override fun currentConnectionEntry(): JSONObject {
      @Suppress("DEPRECATION")
      val ssid =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
          ?.trim('"')
          ?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
      return JSONObject().put("ssid", ssid ?: "Use current connection").put("rssi", -40).put("secure", false)
    }
  }

  companion object {
    private const val TAG = "MuseBle"
    /** Until the phone negotiates, assume full 160-byte packets fit, as upstream. */
    private const val DEFAULT_MTU = 163
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }
}

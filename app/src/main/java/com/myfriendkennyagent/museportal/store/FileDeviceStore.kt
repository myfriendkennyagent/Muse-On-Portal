package com.myfriendkennyagent.museportal.store

import android.content.Context
import android.util.Log
import com.myfriendkennyagent.museportal.protocol.DeviceStore
import com.myfriendkennyagent.museportal.protocol.Identity
import com.myfriendkennyagent.museportal.protocol.PairingRecord
import com.myfriendkennyagent.museportal.protocol.SdkToken
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/**
 * Identity, pairing and SDK token in the app's private files, in the same
 * JSON formats as the Linux Device SDK's state directory. The pairing (device
 * tokens) and the SDK token are sealed with a Keystore key ([SecretBox]); the
 * identity is not secret (its suffix is in the BLE name) and stays plain.
 *
 * Files pushed with adb to the app's external `import/` folder (identity.json,
 * pairing.json, sdk_token) are moved in on [importPending]. That is how the
 * SDK token gets onto the Portal without typing, and how a pairing made on a
 * laptop with `musegadget pair` is carried over.
 */
class FileDeviceStore(private val context: Context) : DeviceStore {
  private val dir: File = File(context.filesDir, "muse").apply { mkdirs() }
  private val identityFile = File(dir, "identity.json")
  private val pairingFile = File(dir, "pairing.json")
  private val tokenFile = File(dir, "sdk_token")

  /** Where `adb push` drops files to import: /sdcard/Android/data/<pkg>/files/import. */
  val importDir: File?
    get() = context.getExternalFilesDir("import")

  @Synchronized
  override fun loadIdentity(): Identity? {
    val mac = readJson(identityFile)?.optString("mac")
    return if (Identity.isValidMac(mac)) Identity(mac!!) else null
  }

  @Synchronized
  override fun saveIdentity(identity: Identity) = writeJson(identityFile, JSONObject().put("mac", identity.mac))

  @Synchronized
  override fun loadPairing(): PairingRecord? = PairingRecord.fromJson(readSealedJson(pairingFile))

  @Synchronized
  override fun savePairing(record: PairingRecord) = atomicWrite(pairingFile, SecretBox.seal(record.toJson().toString()))

  @Synchronized
  override fun deletePairing() {
    pairingFile.delete()
  }

  @Synchronized
  override fun sdkToken(): String? =
    tokenFile.takeIf { it.exists() }?.readText()?.let(SecretBox::open)?.trim()?.takeIf { SdkToken.isValid(it) }

  /** Saves the SDK token; returns false if it isn't one gadgets.muse.ai could have issued. */
  @Synchronized
  fun saveSdkToken(token: String): Boolean {
    val t = token.trim()
    if (!SdkToken.isValid(t)) return false
    atomicWrite(tokenFile, SecretBox.seal(t))
    return true
  }

  /** Moves files pushed over adb into private storage. Returns what was imported. */
  @Synchronized
  fun importPending(): List<String> {
    val src = importDir ?: return emptyList()
    val imported = ArrayList<String>()
    File(src, "sdk_token").takeIf { it.exists() }?.let { f ->
      if (saveSdkToken(f.readText())) imported += "SDK token" else Log.w(TAG, "ignored an invalid sdk_token file")
      f.delete()
    }
    File(src, "identity.json").takeIf { it.exists() }?.let { f ->
      val mac = readJson(f)?.optString("mac")
      if (Identity.isValidMac(mac)) {
        saveIdentity(Identity(mac!!))
        imported += "identity"
      }
      f.delete()
    }
    File(src, "pairing.json").takeIf { it.exists() }?.let { f ->
      PairingRecord.fromJson(readJson(f))?.let {
        savePairing(it)
        imported += "pairing"
      }
      f.delete()
    }
    if (imported.isNotEmpty()) Log.i(TAG, "imported ${imported.joinToString()}")
    return imported
  }

  private fun readJson(file: File): JSONObject? =
    try {
      if (file.exists()) JSONObject(file.readText()) else null
    } catch (e: JSONException) {
      null
    }

  private fun readSealedJson(file: File): JSONObject? =
    try {
      if (file.exists()) SecretBox.open(file.readText())?.let { JSONObject(it) } else null
    } catch (e: JSONException) {
      null
    }

  private fun writeJson(file: File, json: JSONObject) = atomicWrite(file, json.toString(2))

  /** A crash leaves either the old file or the new one, never a partial file. */
  private fun atomicWrite(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use {
      it.write(text.toByteArray())
      it.fd.sync()
    }
    if (!tmp.renameTo(file)) {
      file.delete()
      tmp.renameTo(file)
    }
  }

  companion object {
    private const val TAG = "MuseStore"
  }
}

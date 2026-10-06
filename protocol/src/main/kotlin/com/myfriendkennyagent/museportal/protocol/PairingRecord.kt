package com.myfriendkennyagent.museportal.protocol

import org.json.JSONObject

/**
 * Device credentials from setup. The JSON form matches the Linux Device SDK's
 * `pairing.json`, so a pairing made with `musegadget pair` on a laptop can be
 * imported unchanged.
 */
data class PairingRecord(
  val accessToken: String,
  val refreshToken: String,
  val username: String = "",
  val apiUrl: String = "",
  val apiUrlV2: String = "",
  val noiseHost: String = "",
  val accessTokenSavedAt: Long = System.currentTimeMillis() / 1000,
) {
  fun toJson(): JSONObject =
    JSONObject()
      .put("access_token", accessToken)
      .put("refresh_token", refreshToken)
      .put("token_type", "device")
      .put("username", username)
      .put("api_url", apiUrl)
      .put("api_url_v2", apiUrlV2)
      .put("noise_host", noiseHost)
      .put("access_token_saved_at", accessTokenSavedAt)

  override fun toString(): String = "PairingRecord(username=$username, noiseHost=$noiseHost, savedAt=$accessTokenSavedAt)"

  companion object {
    /** Null unless [json] holds both tokens. */
    fun fromJson(json: JSONObject?): PairingRecord? {
      json ?: return null
      val access = json.optString("access_token")
      val refresh = json.optString("refresh_token")
      if (access.isEmpty() || refresh.isEmpty()) return null
      return PairingRecord(
        accessToken = access,
        refreshToken = refresh,
        username = json.optString("username"),
        apiUrl = json.optString("api_url"),
        apiUrlV2 = json.optString("api_url_v2"),
        noiseHost = json.optString("noise_host"),
        accessTokenSavedAt = json.optLong("access_token_saved_at", 0),
      )
    }
  }
}

/** Where identity, pairing and the SDK token live (app files on Android). */
interface DeviceStore {
  fun loadIdentity(): Identity?

  fun saveIdentity(identity: Identity)

  fun loadPairing(): PairingRecord?

  fun savePairing(record: PairingRecord)

  fun deletePairing()

  fun sdkToken(): String?

  /** Loads the identity, creating and saving one the first time. */
  fun identity(): Identity = loadIdentity() ?: Identity.generate().also { saveIdentity(it) }
}

object SdkToken {
  /** `mgst_` plus 43 canonical base64url characters, as issued by gadgets.muse.ai. */
  private val PATTERN = Regex("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")

  fun isValid(token: String?): Boolean = token != null && PATTERN.matches(token.trim())
}

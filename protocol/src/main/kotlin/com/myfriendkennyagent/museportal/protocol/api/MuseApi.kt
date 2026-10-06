package com.myfriendkennyagent.museportal.protocol.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/** One leased VM: where the Muse runs, and the per-VM bearer for it. */
data class VmInfo(
  val vmUrl: String,
  val vmAuthToken: String,
  val vmName: String,
  val vmId: String,
  val isDefault: Boolean,
)

data class TokenPair(val accessToken: String, val refreshToken: String)

/** A result plus the HTTP status that ended the attempt; null status means a transport failure. */
data class ApiResult<T>(val value: T, val status: Int?)

/**
 * Client for the Muse device API, ported from `muse_api.py`: leased VM lookup
 * and device token rotation.
 */
open class MuseApi(
  private val client: OkHttpClient = defaultClient(),
  private val userAgent: String = "museportal",
) {
  private val log = Logger.getLogger("MuseApi")

  /** Leased VMs for the device token. A 401 means the token was rejected. */
  open fun fetchVms(accessToken: String, root: String = API_BASE): ApiResult<List<VmInfo>> {
    val request =
      Request.Builder()
        .url(root + FETCH_PATH)
        .get()
        .header("Authorization", "Bearer $accessToken")
        .header("X-API-Version", "1.0.0")
        .header("User-Agent", userAgent)
        .build()
    val (status, text) =
      try {
        client.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
      } catch (e: IOException) {
        log.warning("VM fetch failed: $e")
        return ApiResult(emptyList(), null)
      }
    if (status !in 200..299) {
      log.warning("VM fetch failed: HTTP $status")
      return ApiResult(emptyList(), status)
    }
    val data =
      try {
        JSONObject(text)
      } catch (e: JSONException) {
        log.warning("VM fetch: unexpected response")
        return ApiResult(emptyList(), status)
      }
    if (data.optString("error_title").isNotEmpty() || data.optString("backend_error_code").isNotEmpty()) {
      log.warning("VM fetch error: ${data.optString("error_title")} ${data.optString("backend_error_code")}")
      return ApiResult(emptyList(), status)
    }
    val list = data.optJSONArray("vm_list") ?: return ApiResult(emptyList(), status)
    val vms = ArrayList<VmInfo>()
    for (i in 0 until list.length()) {
      val entry = list.optJSONObject(i) ?: continue
      val url = entry.optString("vm_ws_url").ifEmpty { entry.optString("vm_url") }
      val token = entry.optString("vm_auth_token")
      if (url.isEmpty() || token.isEmpty()) continue
      vms +=
        VmInfo(
          vmUrl = url,
          vmAuthToken = token,
          vmName = entry.optString("vm_name"),
          vmId = entry.optString("vm_id"),
          isDefault = entry.optBoolean("default", false),
        )
    }
    log.info("VM fetch: ${vms.size} VMs")
    return ApiResult(vms, status)
  }

  /**
   * Rotates the device token pair with the refresh token. A 401 means the
   * pairing is gone. The access token is never presented: the server can
   * accept it and answer with tokens every endpoint then rejects.
   */
  open fun refreshDeviceToken(
    refreshToken: String,
    deviceId: String,
    root: String = API_BASE,
    sdkToken: String? = null,
  ): ApiResult<TokenPair?> {
    // Apps hand over refresh tokens that may already carry the prefix; doubling it fails.
    val raw = refreshToken.substringAfterLast(":")
    val body = JSONObject().put("device_id", deviceId)
    if (!sdkToken.isNullOrEmpty()) body.put("sdk_token", sdkToken)
    val request =
      Request.Builder()
        .url(root + REFRESH_PATH)
        .post(body.toString().toRequestBody(JSON))
        .header("Authorization", "Bearer hatch_refresh:$raw")
        .header("User-Agent", userAgent)
        .build()
    return try {
      client.newCall(request).execute().use { response ->
        val text = response.body?.string() ?: ""
        if (!response.isSuccessful) {
          log.warning(
            if (response.code == 401) "token refresh rejected: device must be paired again"
            else "token refresh failed: HTTP ${response.code}"
          )
          return ApiResult(null, response.code)
        }
        var data = JSONObject(text)
        data.optJSONObject("payload")?.let { data = it }
        val access = data.optString("access_token")
        val refresh = data.optString("refresh_token")
        if (access.isEmpty() || refresh.isEmpty()) {
          log.warning("token refresh response missing tokens")
          ApiResult(null, response.code)
        } else {
          ApiResult(TokenPair(access, refresh), response.code)
        }
      }
    } catch (e: IOException) {
      log.warning("token refresh failed: $e")
      ApiResult(null, null)
    } catch (e: JSONException) {
      log.warning("token refresh failed: bad JSON")
      ApiResult(null, null)
    }
  }

  companion object {
    const val API_BASE = "https://api.muse.ai"
    const val FETCH_PATH = "/fetch_vms"
    const val REFRESH_PATH = "/device_token/refresh"
    private val JSON = "application/json".toMediaType()

    /** `api_url_v2` if it is HTTPS, else the Muse API. `api_url` is for older firmware only. */
    fun apiRoot(apiUrlV2: String = ""): String = (apiUrlV2.takeIf { it.startsWith("https://") } ?: API_BASE).trimEnd('/')

    fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
  }
}

package com.myfriendkennyagent.museportal.protocol.link

import com.myfriendkennyagent.museportal.protocol.DeviceStore
import com.myfriendkennyagent.museportal.protocol.PairingRecord
import com.myfriendkennyagent.museportal.protocol.api.MuseApi
import com.myfriendkennyagent.museportal.protocol.api.VmInfo
import com.myfriendkennyagent.museportal.protocol.chat.ChatEvent
import com.myfriendkennyagent.museportal.protocol.chat.NdjsonSplitter
import com.myfriendkennyagent.museportal.protocol.noise.Header
import java.io.IOException
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class ConnectionState {
  data object NotPaired : ConnectionState()

  data class Connecting(val vm: String) : ConnectionState()

  data object Connected : ConnectionState()

  data class Waiting(val seconds: Long, val reason: String) : ConnectionState()

  /** The Muse removed this device, or the pairing was revoked. */
  data object Unpaired : ConnectionState()
}

interface ConnectionListener {
  fun onState(state: ConnectionState) {}

  /** A line from the chat subscription. Called on the session's read loop: keep it quick. */
  fun onChatEvent(event: ChatEvent) {}

  /**
   * The chat subscription opened ([error] null) or failed. Replies to this
   * device's messages only arrive through it, so a refusal (say, if the
   * server stops offering it to Linux-profile devices) must be visible.
   */
  fun onSubscription(error: String?) {}
}

/** Exponential backoff as upstream: 2 s doubling to 60 s, with an optional floor. */
class Backoff {
  var failures = 0
    private set

  var floorMs = 0L

  fun nextDelayMs(): Long {
    val delay = minOf((BASE_MS * Math.pow(2.0, minOf(failures, 16).toDouble())).toLong(), MAX_MS)
    failures++
    return maxOf(delay, floorMs)
  }

  fun reset() {
    failures = 0
    floorMs = 0
  }

  companion object {
    const val BASE_MS = 2_000L
    const val MAX_MS = 60_000L
  }
}

/**
 * Keeps a paired device connected to its Muse, ported from the Linux SDK's
 * `service.py`. Each round fetches the leased VMs with the device token
 * (which also yields a fresh per-VM bearer), connects to the default VM and
 * serves commands until the connection ends. The device token is rotated
 * before it expires, and immediately if the API rejects it.
 *
 * While a session is up it also holds `/chat/subscribe` open, so replies to
 * this device's messages (and Muse's own check-ins) stream back.
 */
class MuseConnection(
  private val store: DeviceStore,
  private val api: MuseApi,
  private val connector: SocketConnector,
  private val device: () -> DeviceDescription,
  private val runner: CommandRunner,
  private val listener: ConnectionListener,
  private val wallClock: () -> Long = { System.currentTimeMillis() / 1000 },
  private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  private val log = Logger.getLogger("MuseConnection")
  private var lastRefreshAttempt = Long.MIN_VALUE / 2
  private var sdkTokenReportAttempted = false

  @Volatile
  var current: LinkSession? = null
    private set

  /** Runs until cancelled. */
  suspend fun run() {
    val backoff = Backoff()
    while (true) {
      var pairing = store.loadPairing()
      if (pairing == null) {
        listener.onState(ConnectionState.NotPaired)
        delay(UNPAIRED_POLL_MS)
        continue
      }
      pairing = maybeRefresh(pairing)
      if (pairing == null) {
        wait(TOKEN_RETRY_MS, "couldn't refresh the device token")
        continue
      }
      val root = MuseApi.apiRoot(pairing.apiUrlV2)
      val (vms, status) = withContext(Dispatchers.IO) { api.fetchVms(pairing.accessToken, root) }
      if (status == 401) {
        log.warning("device token rejected by the API; refreshing")
        if (maybeRefresh(pairing, force = true) == null) wait(TOKEN_RETRY_MS, "device token rejected")
        continue
      }
      val vm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
      if (vm == null) {
        wait(backoff.nextDelayMs(), if (status == null) "can't reach Muse" else "no Muse VM available")
        continue
      }
      val (outcome, lastedMs) = session(vm, pairing)
      if (outcome == Outcome.UNPAIRED) {
        store.deletePairing()
        listener.onState(ConnectionState.Unpaired)
        log.warning("pairing removed; pair again to set up")
        continue
      }
      if (lastedMs >= HEALTHY_SESSION_MS) backoff.reset()
      if (outcome == Outcome.AUTH_REJECTED || outcome == Outcome.FORBIDDEN) backoff.floorMs = AUTH_BACKOFF_MIN_MS
      wait(backoff.nextDelayMs(), "reconnecting")
    }
  }

  private suspend fun wait(ms: Long, reason: String) {
    listener.onState(ConnectionState.Waiting(ms / 1000, reason))
    log.info("$reason; next try in ${ms / 1000}s")
    delay(ms)
  }

  private suspend fun session(vm: VmInfo, pairing: PairingRecord): Pair<Outcome, Long> = coroutineScope {
    val name = vm.vmName.ifEmpty { vm.vmId }
    listener.onState(ConnectionState.Connecting(name))
    log.info("connecting to $name")
    val session =
      LinkSession(
        noiseHost = pairing.noiseHost.ifEmpty { DEFAULT_NOISE_HOST },
        vmId = vm.vmId.ifEmpty { vm.vmName },
        vmAuthToken = vm.vmAuthToken,
        device = device(),
        runner = runner,
        connector = connector,
        onRegistered = { s ->
          listener.onState(ConnectionState.Connected)
          launch { subscribeLoop(s) }
        },
      )
    current = session
    val outcome =
      try {
        session.run()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.warning("session failed: ${e.javaClass.simpleName}: ${e.message}")
        Outcome.CLOSED
      } finally {
        current = null
      }
    val registered = session.registeredAt
    val lasted = if (registered == null) 0 else monotonic() - registered
    log.info("session ended: $outcome")
    coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
    outcome to lasted
  }

  /**
   * Holds `/chat/subscribe` open while [session] lives, reopening it if it
   * ends. Failures are told apart, because the fixes differ: the server
   * refusing the stream (a 4xx: likely not offered to this registration
   * profile) versus the network or the VM hiccuping (reconnect and carry on).
   */
  private suspend fun subscribeLoop(session: LinkSession) {
    while (!session.closed) {
      val ended = CompletableDeferred<SubscriptionEnd>()
      val splitter = NdjsonSplitter()
      val streamListener =
        object : StreamListener {
          override fun onResponse(status: Int, headers: List<Header>) {
            if (status >= 400) {
              ended.complete(SubscriptionEnd.Http(status))
            } else {
              log.info("chat subscription open (HTTP $status)")
              listener.onSubscription(null)
            }
          }

          override fun onData(data: ByteArray) {
            for (line in splitter.feed(data)) {
              ChatEvent.parse(line)?.let { listener.onChatEvent(it) }
            }
          }

          override fun onEnd() {
            ended.complete(SubscriptionEnd.Closed)
          }

          override fun onError(reason: String) {
            ended.complete(SubscriptionEnd.Transport(reason))
          }
        }
      val end =
        try {
          session.openStream(
            "POST",
            LinkSession.SUBSCRIBE_PATH,
            LinkSession.jsonHeaders(accept = "application/x-ndjson"),
            streamListener,
            body = "{}".toByteArray(),
            endBody = true,
          )
          ended.await()
        } catch (e: IOException) {
          SubscriptionEnd.Transport(e.message ?: e.javaClass.simpleName)
        }
      if (session.closed) return
      when (end) {
        is SubscriptionEnd.Http ->
          if (end.status in 400..499) {
            val why = "Muse refused the reply stream (HTTP ${end.status}). Replies won't reach this Portal."
            log.severe(
              "REPLY STREAM REFUSED: /chat/subscribe answered HTTP ${end.status}. The server is likely not " +
                "offering it to this registration profile (${device().profile}); see RegistrationProfile.kt " +
                "and the upstream SDK for changes. Retrying every ${SUBSCRIBE_REFUSED_RETRY_MS / 1000}s."
            )
            listener.onSubscription(why)
            delay(SUBSCRIBE_REFUSED_RETRY_MS)
            continue
          } else {
            log.warning("REPLY STREAM SERVER ERROR: /chat/subscribe answered HTTP ${end.status}; retrying")
            listener.onSubscription("Muse's reply stream had a server error (HTTP ${end.status}); retrying.")
          }
        is SubscriptionEnd.Transport -> {
          log.warning("REPLY STREAM DROPPED (network or VM): ${end.reason}; reopening")
          listener.onSubscription("Reconnecting the reply stream…")
        }
        SubscriptionEnd.Closed -> log.info("chat subscription ended by the VM; reopening")
      }
      delay(SUBSCRIBE_RETRY_MS)
    }
  }

  private sealed class SubscriptionEnd {
    data class Http(val status: Int) : SubscriptionEnd()

    data class Transport(val reason: String) : SubscriptionEnd()

    data object Closed : SubscriptionEnd()
  }

  /** Posts a chat message on the live session. Throws IOException when not connected. */
  suspend fun sendChat(body: JSONObject): HttpResult {
    val session = current?.takeIf { it.registeredAt != null && !it.closed } ?: throw IOException("not connected to Muse")
    return session.sendChat(body)
  }

  /**
   * Returns the current pairing, rotating tokens first if due. Null only when
   * a due refresh failed; the pairing is deleted if it was revoked.
   */
  private suspend fun maybeRefresh(pairing: PairingRecord, force: Boolean = false): PairingRecord? {
    val age = wallClock() - pairing.accessTokenSavedAt
    val sdkToken = store.sdkToken()
    // The SDK token reaches Muse only in refresh bodies, so each start reports it once.
    val reportDue = sdkToken != null && !sdkTokenReportAttempted
    val due = force || age >= TOKEN_REFRESH_AGE_S
    if (!due && !reportDue) return pairing
    if (!force && monotonic() - lastRefreshAttempt < TOKEN_RETRY_MS) return pairing
    lastRefreshAttempt = monotonic()
    if (reportDue) sdkTokenReportAttempted = true
    val identity = store.identity()
    val (tokens, status) =
      withContext(Dispatchers.IO) {
        api.refreshDeviceToken(pairing.refreshToken, identity.nodeId, MuseApi.apiRoot(pairing.apiUrlV2), sdkToken)
      }
    if (tokens != null) {
      val updated =
        pairing.copy(
          accessToken = tokens.accessToken,
          refreshToken = tokens.refreshToken,
          accessTokenSavedAt = wallClock(),
        )
      store.savePairing(updated)
      log.info("device token rotated")
      return updated
    }
    if (!due) {
      // Only reporting the SDK token: a refusal here must never unpair the device.
      log.warning("SDK token report refresh failed (HTTP $status); keeping the pairing")
      return pairing
    }
    if (status == 401) {
      store.deletePairing()
      listener.onState(ConnectionState.Unpaired)
      log.severe("pairing revoked; pair again to set up")
      return null
    }
    // Transient failure: keep using the current token while it still works.
    return if (force) null else pairing
  }

  companion object {
    const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"
    const val AUTH_BACKOFF_MIN_MS = 15_000L
    const val HEALTHY_SESSION_MS = 30_000L
    const val UNPAIRED_POLL_MS = 5_000L
    const val SUBSCRIBE_RETRY_MS = 3_000L
    const val SUBSCRIBE_REFUSED_RETRY_MS = 60_000L
    /** Device access tokens live about 4 hours; rotate at 3. */
    const val TOKEN_REFRESH_AGE_S = 3 * 3600L
    const val TOKEN_RETRY_MS = 300_000L
  }
}

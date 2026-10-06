package com.myfriendkennyagent.museportal.protocol.link

import com.myfriendkennyagent.museportal.protocol.DeviceStore
import com.myfriendkennyagent.museportal.protocol.Identity
import com.myfriendkennyagent.museportal.protocol.PairingRecord
import com.myfriendkennyagent.museportal.protocol.api.MuseApi
import com.myfriendkennyagent.museportal.protocol.chat.ChatEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuseConnectionTest {
  private class MemoryStore(var pairing: PairingRecord?) : DeviceStore {
    var identity: Identity? = Identity("02:00:00:12:34:56")

    override fun loadIdentity() = identity

    override fun saveIdentity(identity: Identity) {
      this.identity = identity
    }

    override fun loadPairing() = pairing

    override fun savePairing(record: PairingRecord) {
      pairing = record
    }

    override fun deletePairing() {
      pairing = null
    }

    override fun sdkToken(): String? = null
  }

  @Test
  fun `refreshes, connects, subscribes, and forgets an unpaired device`(): Unit = runBlocking {
    val server = MockWebServer()
    val requests = Channel<RecordedRequest>(Channel.UNLIMITED)
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          requests.trySend(request)
          return when (request.path) {
            "/device_token/refresh" -> MockResponse().setBody("""{"access_token":"acc2","refresh_token":"ref2"}""")
            "/fetch_vms" ->
              MockResponse()
                .setBody("""{"vm_list":[{"vm_ws_url":"wss://x","vm_auth_token":"vmtok","vm_name":"kenny","vm_id":"vm-1","default":true}]}""")
            else -> MockResponse().setResponseCode(404)
          }
        }
      }
    server.start()
    try {
      val apiRoot = server.url("").toString().trimEnd('/').replace("http://", "https://")
      val store =
        MemoryStore(
          PairingRecord(
            accessToken = "acc1",
            refreshToken = "ref1",
            apiUrlV2 = apiRoot,
            noiseHost = "noise.example",
            accessTokenSavedAt = 0, // long ago: refresh is due
          )
        )
      val vm = FakeVm(this)
      val states = Channel<ConnectionState>(Channel.UNLIMITED)
      val events = Channel<ChatEvent>(Channel.UNLIMITED)
      val connection =
        MuseConnection(
          store = store,
          api = HttpsToHttpApi(server),
          connector = vm.connector,
          device = { DeviceDescription(store.identity!!.nodeId, "Portal", "0.1.0", JSONObject()) },
          runner = { _, _, _ -> CommandResult.ok() },
          listener =
            object : ConnectionListener {
              override fun onState(state: ConnectionState) {
                states.trySend(state)
              }

              override fun onChatEvent(event: ChatEvent) {
                events.trySend(event)
              }
            },
          wallClock = { 1_000_000 },
        )
      withTimeout(15_000) {
        val job = launch { connection.run() }
        assertEquals("/device_token/refresh", requests.receive().path)
        val fetch = requests.receive()
        assertEquals("/fetch_vms", fetch.path)
        assertEquals("acc2", store.pairing!!.accessToken)
        assertEquals("Bearer acc2", fetch.getHeader("Authorization"))

        assertEquals(ConnectionState.Connecting("kenny"), states.receive())
        assertEquals(ConnectionState.Connected, states.receive())
        assertEquals("wss://noise.example/v1/noise?vm_id=vm-1", vm.connectedUrls.single())
        assertEquals("Bearer vmtok", vm.connectedHeaders.single()["Authorization"])

        vm.subscribed.await()
        assertEquals("subscribed", events.receive().type)
        vm.pushEvent("""{"type":"event","seq":1,"event":"agent.status","payload":{"activity_code":"online"}}""")
        assertEquals("agent.status", events.receive().event)

        vm.sendControl(JSONObject().put("event", "link.unpaired"))
        assertEquals(ConnectionState.Unpaired, states.receive())
        assertNull(store.pairing)
        assertEquals(ConnectionState.NotPaired, states.receive())
        job.cancel()
      }
    } finally {
      server.shutdown()
    }
  }

  /** MuseApi requires HTTPS roots; this one rewrites them to the plain-HTTP mock server. */
  private class HttpsToHttpApi(private val server: MockWebServer) : MuseApi(okhttp3.OkHttpClient(), "test") {
    private fun local(root: String) = server.url("").toString().trimEnd('/').also { check(root.startsWith("https://")) }

    override fun fetchVms(accessToken: String, root: String) = super.fetchVms(accessToken, local(root))

    override fun refreshDeviceToken(refreshToken: String, deviceId: String, root: String, sdkToken: String?) =
      super.refreshDeviceToken(refreshToken, deviceId, local(root), sdkToken)
  }

  @Test
  fun `a refused reply stream is reported, not silent`(): Unit = runBlocking {
    val server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse().setBody("""{"vm_list":[{"vm_ws_url":"wss://x","vm_auth_token":"t","vm_name":"k","vm_id":"v","default":true}]}""")
      }
    server.start()
    try {
      val store =
        MemoryStore(PairingRecord("a", "r", apiUrlV2 = "https://unused", noiseHost = "n", accessTokenSavedAt = 1_000_000))
      val vm = FakeVm(this).apply { subscribeStatus = 403 }
      val reports = Channel<String?>(Channel.UNLIMITED)
      val connection =
        MuseConnection(
          store = store,
          api = HttpsToHttpApi(server),
          connector = vm.connector,
          device = { DeviceDescription("homelink-123456", "Portal", "0.1.0", JSONObject()) },
          runner = { _, _, _ -> CommandResult.ok() },
          listener =
            object : ConnectionListener {
              override fun onSubscription(error: String?) {
                reports.trySend(error)
              }
            },
          wallClock = { 1_000_000 },
        )
      withTimeout(15_000) {
        val job = launch { connection.run() }
        val report = reports.receive()
        assertEquals(true, report?.contains("HTTP 403"))
        job.cancel()
      }
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `backoff doubles to a ceiling and honours its floor`() {
    val b = Backoff()
    assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), List(7) { b.nextDelayMs() })
    b.reset()
    b.floorMs = 15_000
    assertEquals(15_000L, b.nextDelayMs())
  }
}

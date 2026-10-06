package com.myfriendkennyagent.museportal.protocol.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseApiTest {
  private val server = MockWebServer().apply { start() }
  private val root = server.url("").toString().trimEnd('/')
  private val api = MuseApi(userAgent = "test")

  @After
  fun stop() = server.shutdown()

  @Test
  fun `fetch vms parses the list and sends the bearer`() {
    server.enqueue(
      MockResponse()
        .setBody(
          """{"vm_list":[{"vm_ws_url":"wss://a","vm_auth_token":"t1","vm_name":"one","vm_id":"id1"},
             {"vm_url":"wss://b","vm_auth_token":"t2","vm_name":"two","vm_id":"id2","default":true},
             {"vm_name":"broken"}]}"""
        )
    )
    val (vms, status) = api.fetchVms("acc", root)
    assertEquals(200, status)
    assertEquals(2, vms.size)
    assertTrue(vms[1].isDefault)
    assertEquals("wss://b", vms[1].vmUrl)
    val req = server.takeRequest()
    assertEquals("/fetch_vms", req.path)
    assertEquals("Bearer acc", req.getHeader("Authorization"))
    assertEquals("1.0.0", req.getHeader("X-API-Version"))
  }

  @Test
  fun `fetch vms reports 401`() {
    server.enqueue(MockResponse().setResponseCode(401))
    val (vms, status) = api.fetchVms("acc", root)
    assertEquals(401, status)
    assertTrue(vms.isEmpty())
  }

  @Test
  fun `refresh strips a doubled prefix and reports the sdk token`() {
    server.enqueue(MockResponse().setBody("""{"payload":{"access_token":"a2","refresh_token":"r2"}}"""))
    val (tokens, status) = api.refreshDeviceToken("hatch_refresh:raw", "homelink-abc123", root, "mgst_x")
    assertEquals(200, status)
    assertEquals(TokenPair("a2", "r2"), tokens)
    val req = server.takeRequest()
    assertEquals("/device_token/refresh", req.path)
    assertEquals("Bearer hatch_refresh:raw", req.getHeader("Authorization"))
    val body = JSONObject(req.body.readUtf8())
    assertEquals("homelink-abc123", body.getString("device_id"))
    assertEquals("mgst_x", body.getString("sdk_token"))
  }

  @Test
  fun `refresh 401 means paired again`() {
    server.enqueue(MockResponse().setResponseCode(401))
    val (tokens, status) = api.refreshDeviceToken("r", "d", root)
    assertNull(tokens)
    assertEquals(401, status)
  }

  @Test
  fun `api root prefers https v2 urls`() {
    assertEquals("https://x.example", MuseApi.apiRoot("https://x.example/"))
    assertEquals(MuseApi.API_BASE, MuseApi.apiRoot("http://insecure"))
    assertEquals(MuseApi.API_BASE, MuseApi.apiRoot(""))
  }
}

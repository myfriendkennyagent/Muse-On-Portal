package com.myfriendkennyagent.museportal.protocol.link

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** A binary message socket to the VM's Noise endpoint. */
interface FrameSocket {
  suspend fun send(data: ByteArray)

  /** The next binary message, or null once the socket is closed. */
  suspend fun receive(): ByteArray?

  fun close()
}

/** The VM refused the WebSocket upgrade with [status] (401/403: fetch fresh VM credentials). */
class UpgradeRejected(val status: Int) : IOException("upgrade rejected: HTTP $status")

fun interface SocketConnector {
  suspend fun connect(url: String, headers: Map<String, String>): FrameSocket
}

/** [SocketConnector] over OkHttp WebSockets, with 20 s pings as upstream. */
class OkHttpSocketConnector(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .pingInterval(20, TimeUnit.SECONDS)
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.SECONDS)
      .build(),
  private val userAgent: String = "museportal",
) : SocketConnector {
  override suspend fun connect(url: String, headers: Map<String, String>): FrameSocket {
    val builder = Request.Builder().url(url).header("User-Agent", userAgent)
    headers.forEach { (k, v) -> builder.header(k, v) }
    val socket = OkHttpFrameSocket()
    val ws = client.newWebSocket(builder.build(), socket.listener)
    socket.ws = ws
    try {
      socket.opened.await()
    } catch (e: Throwable) {
      ws.cancel()
      throw e
    }
    return socket
  }
}

private class OkHttpFrameSocket : FrameSocket {
  lateinit var ws: WebSocket
  val opened = CompletableDeferred<Unit>()
  private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

  val listener =
    object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.complete(Unit)
      }

      override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        inbox.trySend(bytes.toByteArray())
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        // Text frames are not part of the protocol; ignore them.
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        inbox.close()
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        inbox.close()
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val code = response?.code
        if (!opened.isCompleted) {
          opened.completeExceptionally(
            if (code == 401 || code == 403) UpgradeRejected(code)
            else if (code != null) IOException("upgrade failed: HTTP $code", t)
            else IOException(t.message ?: "connect failed", t)
          )
        }
        inbox.close()
      }
    }

  override suspend fun send(data: ByteArray) {
    if (!ws.send(data.toByteString())) throw IOException("socket closed")
  }

  override suspend fun receive(): ByteArray? =
    try {
      inbox.receive()
    } catch (e: ClosedReceiveChannelException) {
      null
    }

  override fun close() {
    ws.close(1000, null)
    inbox.close()
  }
}

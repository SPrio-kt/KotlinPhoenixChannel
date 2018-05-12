package org.phoenixframework.socket

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.phoenixframework.Message
import org.phoenixframework.PhoenixMessageSender
import org.phoenixframework.channel.Channel
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.concurrent.timerTask

class Socket @JvmOverloads constructor(
    private val endpointUri: String,
    private val heartbeatInterval: Long = DEFAULT_HEARTBEAT_INTERVAL)
  : PhoenixMessageSender {

  private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
      .apply { configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }
  private val httpClient: OkHttpClient = OkHttpClient()
  private var webSocket: WebSocket? = null
  private val channels: ConcurrentHashMap<String, Channel> = ConcurrentHashMap()
  private var refNumber = 1

  private var listeners = mutableSetOf<PhoenixSocketEventListener>()

  private var timer: Timer = Timer("org.phoenixframework.socket.Socket Timer For $endpointUri")
  private var timeoutTimer: Timer = Timer("Timeout Timer For $endpointUri")
  private var heartbeatTimerTask: TimerTask? = null
  var reconnectOnFailure: Boolean = false
  private var reconnectTimerTask: TimerTask? = null

  private val timeoutTimerTasks = ConcurrentHashMap<String, TimerTask>()

  // buffer가 비어있으면 작업을 중지하고 blocking 상태가 됨.
  private var messageBuffer: LinkedBlockingQueue<String> = LinkedBlockingQueue()

  companion object {
    private const val DEFAULT_HEARTBEAT_INTERVAL: Long = 7000
    private const val DEFAULT_RECONNECT_INTERVAL: Long = 5000
    private const val DEFAULT_TIMEOUT: Long = 5000
  }

  fun connect() {
    disconnect()
    val httpUrl = endpointUri.replaceFirst(Regex("^ws:"), "http:")
        .replaceFirst(Regex("^wss:"), "https:")
    val request = Request.Builder().url(httpUrl).build()
    webSocket = httpClient.newWebSocket(request, phoenixWebSocketListener)
  }

  fun disconnect() {
    webSocket?.close(1001, "Disconnect By Client")
    cancelReconnectTimer()
    cancelHeartbeatTimer()
  }

  fun registerEventListener(phoenixSocketEventListener: PhoenixSocketEventListener) {
    listeners.add(phoenixSocketEventListener)
  }

  fun unregisterEventListener(phoenixSocketEventListener: PhoenixSocketEventListener) {
    listeners.remove(phoenixSocketEventListener)
  }

  private fun push(request: Message): Socket {
    val node = objectMapper.createObjectNode()
    node.put("topic", request.topic)
    node.put("event", request.event)
    node.put("ref", request.ref)
    node.set("payload",
        request.payload?.let { objectMapper.readTree(it) } ?: objectMapper.createObjectNode())
    send(objectMapper.writeValueAsString(node))
    return this@Socket
  }

  fun channel(topic: String): Channel {
    var channel = channels[topic]
    if (channel == null) {
      channel = Channel(this, topic)
      channels[topic] = channel
    }
    return channel
  }

  fun removeChannel(topic: String) {
    channels.remove(topic)
  }

  // TODO : Implement another tasks to prevent memory leak if needed.
  private fun removeAllChannels() {
    channels.clear()
  }

  private fun send(json: String) {
    messageBuffer.put(json)
    while (isConnected() && messageBuffer.isNotEmpty()) {
      webSocket?.send(messageBuffer.take())
    }
  }

  private fun flushSendBuffer() {
    messageBuffer.clear()
  }

  private fun isConnected(): Boolean = webSocket != null

  private fun startHeartbeatTimer() {
    cancelHeartbeatTimer()
    heartbeatTimerTask = timerTask {
      if (isConnected()) {
        try {
          push(Message("phoenix", "heartbeat", null, makeRef()))
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
    timer.schedule(heartbeatTimerTask, heartbeatInterval, heartbeatInterval)
  }

  private fun cancelHeartbeatTimer() {
    heartbeatTimerTask?.cancel()
    heartbeatTimerTask = null
  }

  private fun startTimeoutTimer(channel: Channel, request: Message, timeout: Long) {
    val ref = request.ref!!
    val timeoutTimerTask = timerTask {
      channel.retrieveFailure(TimeoutException("Timeout from request " + request))
    }
    timeoutTimerTasks[ref] = timeoutTimerTask
    timeoutTimer.schedule(timeoutTimerTask, timeout)
  }

  private fun cancelTimeoutTimer(ref: String) {
    timeoutTimerTasks[ref]?.cancel()
    timeoutTimerTasks.remove(ref)
  }

  private fun startReconnectTimer() {
    cancelReconnectTimer()
    cancelHeartbeatTimer()
    reconnectTimerTask = timerTask {
      try {
        connect()
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
    timer.schedule(reconnectTimerTask, DEFAULT_RECONNECT_INTERVAL)
  }

  private fun cancelReconnectTimer() {
    reconnectTimerTask?.cancel()
    reconnectTimerTask = null
  }

  private fun triggerChannelError(throwable: Throwable?) {
    channels.values.forEach { it.retrieveFailure(throwable) }
  }

  /**
   * These methods are for socket listener.
  * */
  private fun onOpen(webSocket: WebSocket?) {
    this@Socket.webSocket = webSocket
    cancelReconnectTimer()
    startHeartbeatTimer()
    this@Socket.listeners.forEach { it.onOpen() }
    flushSendBuffer()
  }

  private fun onMessage(text: String?) {
    val messageJson = objectMapper.readTree(text)
    val message = Message(messageJson.get("topic").asText(),
        messageJson.get("event").asText(),
        messageJson.get("payload").toString(),
        messageJson.get("ref").asText()).apply {
      this.status = messageJson.get("payload")?.get("status")?.asText()
      this.reason = messageJson.get("payload")?.get("response")?.get("reason")?.asText()
    }
    listeners.forEach { it.onMessage(text) }
    message.ref?.let { cancelTimeoutTimer(it) }
    channels[message.topic]?.retrieveMessage(message)
  }

  private fun onClosing(code: Int, reason: String?) {
    listeners.forEach { it.onClosing(code, reason) }
  }

  private fun onClosed(code: Int, reason: String?) {
    this@Socket.apply {
      this@Socket.webSocket = null
      this@Socket.listeners.forEach { it.onClosed(code, reason) }
      triggerChannelError(SocketClosedException("Socket Closed"))
      removeAllChannels()
    }
  }

  private fun onFailure(t: Throwable?) {
    this@Socket.listeners.forEach { it.onFailure(t) }
    try {
      this@Socket.webSocket?.close(1001 /* GOING_AWAY */, "Error Occurred")
    } finally {
      this@Socket.webSocket = null
      triggerChannelError(t)
      if (this@Socket.reconnectOnFailure) {
        startReconnectTimer()
      }
    }
  }

  /**
   * Implements [PhoenixMessageSender].
   */
  override fun canSendMessage(): Boolean = isConnected()

  override fun sendMessage(message: Message, timeout: Long?) {
    startTimeoutTimer(channel(message.topic), message, timeout ?: DEFAULT_TIMEOUT)
    push(message)
  }

  override fun makeRef(): String {
    synchronized(refNumber) {
      val ref = refNumber++
      if (refNumber == Int.MAX_VALUE) {
        refNumber = 0
      }
      return ref.toString()
    }
  }

  private val phoenixWebSocketListener = object: WebSocketListener() {

    override fun onOpen(webSocket: WebSocket?, response: Response?) {
      this@Socket.onOpen(webSocket)
    }

    override fun onMessage(webSocket: WebSocket?, text: String?) {
      this@Socket.onMessage(text)
    }

    override fun onMessage(webSocket: WebSocket?, bytes: ByteString?) {
      onMessage(webSocket, bytes.toString())
    }

    override fun onClosing(webSocket: WebSocket?, code: Int, reason: String?) {
      this@Socket.onClosing(code, reason)
    }

    override fun onClosed(webSocket: WebSocket?, code: Int, reason: String?) {
      this@Socket.onClosed(code, reason)
    }

    override fun onFailure(webSocket: WebSocket?, t: Throwable?, response: Response?) {
      this@Socket.onFailure(t)
    }
  }

  /**
   * Implements test helper methods. Only tests can use below methods.
   */
  internal fun getChannels(): ConcurrentHashMap<String, Channel> = channels

  internal fun getWebSocket(): WebSocket? = webSocket

  internal fun getWebSocketListener(): WebSocketListener = phoenixWebSocketListener
}
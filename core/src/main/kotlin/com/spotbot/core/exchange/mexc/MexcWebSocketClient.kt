package com.spotbot.core.exchange.mexc

import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.random.Random

sealed interface StreamEvent {
    data class BookTickerUpdate(val ticker: BookTicker) : StreamEvent

    /** Henüz kapanmamış (canlı) mum güncellemesi; sinyal için kullanılmaz. */
    data class KlineUpdate(val symbol: String, val timeframe: Timeframe, val candle: Candle) : StreamEvent
}

enum class ConnectionStatus { STOPPED, CONNECTING, CONNECTED, RECONNECTING }

data class StreamConnectionState(
    val status: ConnectionStatus = ConnectionStatus.STOPPED,
    val connections: Int = 0,
    val connected: Int = 0,
    val subscriptions: Int = 0,
    val lastMessageAt: Long? = null,
    val reconnects: Int = 0,
    val lastError: String? = null,
)

/**
 * MEXC protobuf WebSocket istemcisi.
 *
 * - Bağlantı başına en fazla 30 kanal (MEXC sınırı); fazlası ek bağlantılara dağıtılır.
 * - 20 sn'de bir PING; 60 sn veri gelmezse bağlantı yenilenir; 23 saatte bir planlı yeniden bağlanma
 *   (MEXC bağlantıyı 24 saatte keser).
 * - Kopmada üstel geri çekilme (1 sn → 60 sn, rastgele sapmalı) ile yeniden bağlanır ve aboneleri yeniler.
 */
class MexcWebSocketClient(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = defaultClient(),
    private val url: String = DEFAULT_URL,
    private val timeSource: TimeSource = TimeSource.System,
) {
    private val _events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<StreamEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(StreamConnectionState())
    val state: StateFlow<StreamConnectionState> = _state.asStateFlow()

    private val shards = mutableListOf<Shard>()
    private var desired: List<String> = emptyList()
    private var running = false

    /** İstenen kanal kümesini ayarlar; değişiklikler açık bağlantılara anında uygulanır. */
    @Synchronized
    fun setSubscriptions(channels: Set<String>) {
        desired = channels.sorted()
        if (running) rebalance()
        _state.update { it.copy(subscriptions = desired.size) }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        _state.update { it.copy(status = ConnectionStatus.CONNECTING) }
        rebalance()
    }

    @Synchronized
    fun stop() {
        running = false
        shards.forEach { it.close() }
        shards.clear()
        _state.update { it.copy(status = ConnectionStatus.STOPPED, connections = 0, connected = 0) }
    }

    private fun rebalance() {
        val chunks = desired.chunked(MAX_SUBSCRIPTIONS_PER_CONNECTION).ifEmpty { emptyList() }
        while (shards.size > chunks.size) shards.removeAt(shards.lastIndex).close()
        while (shards.size < chunks.size) shards.add(Shard(shards.size).also { it.launch() })
        shards.forEachIndexed { index, shard -> shard.desired.value = chunks[index].toSet() }
        refreshState()
    }

    private fun refreshState() {
        val connected = shards.count { it.connected }
        _state.update {
            it.copy(
                connections = shards.size,
                connected = connected,
                status = when {
                    !running -> ConnectionStatus.STOPPED
                    shards.isEmpty() || connected == shards.size -> ConnectionStatus.CONNECTED
                    it.reconnects > 0 -> ConnectionStatus.RECONNECTING
                    else -> ConnectionStatus.CONNECTING
                },
            )
        }
    }

    private sealed interface Signal {
        data object Open : Signal
        data class Binary(val bytes: ByteArray) : Signal
        data class Text(val text: String) : Signal
        data class Closed(val reason: String) : Signal
    }

    private inner class Shard(val index: Int) {
        val desired = MutableStateFlow<Set<String>>(emptySet())
        @Volatile var connected = false
        private var job: Job? = null
        private var socket: WebSocket? = null

        fun launch() {
            job = scope.launch { runLoop() }
        }

        fun close() {
            job?.cancel()
            socket?.close(1000, "stop")
            connected = false
        }

        private suspend fun runLoop() {
            var attempt = 0
            while (scope.isActive && job?.isActive != false) {
                val reason = runCatching { connectOnce { attempt = 0 } }.exceptionOrNull()?.message ?: "kapandı"
                connected = false
                _state.update { it.copy(reconnects = it.reconnects + 1, lastError = "Bağlantı #$index: $reason") }
                refreshState()
                val backoff = minOf(MAX_BACKOFF_MILLIS, INITIAL_BACKOFF_MILLIS shl minOf(attempt, 6))
                delay(backoff + Random.nextLong(0, backoff / 4 + 1))
                attempt++
            }
        }

        /** Bir bağlantı ömrü. Bağlantı kapandığında döner (ya da istisna fırlatır). */
        private suspend fun connectOnce(onConnected: () -> Unit) {
            val signals = Channel<Signal>(Channel.UNLIMITED)
            val ws = httpClient.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        signals.trySend(Signal.Open)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        signals.trySend(Signal.Binary(bytes.toByteArray()))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        signals.trySend(Signal.Text(text))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        signals.trySend(Signal.Closed("sunucu kapattı ($code $reason)"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        signals.trySend(Signal.Closed(t.message ?: t.javaClass.simpleName))
                    }
                },
            )
            socket = ws
            try {
                val first = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { signals.receive() }
                if (first !is Signal.Open) error((first as? Signal.Closed)?.reason ?: "bağlantı zaman aşımı")

                val connectedAt = timeSource.now()
                var subscribed = emptySet<String>()
                var lastMessageAt = connectedAt
                var lastPingAt = connectedAt
                connected = true
                onConnected()
                refreshState()

                while (true) {
                    val target = desired.value
                    if (target != subscribed) {
                        (subscribed - target).takeIf { it.isNotEmpty() }?.let { ws.send(request("UNSUBSCRIPTION", it)) }
                        (target - subscribed).takeIf { it.isNotEmpty() }?.let { ws.send(request("SUBSCRIPTION", it)) }
                        subscribed = target
                    }
                    val now = timeSource.now()
                    if (now - lastPingAt >= PING_INTERVAL_MILLIS) {
                        ws.send("""{"method":"PING"}""")
                        lastPingAt = now
                    }
                    if (now - lastMessageAt > STALE_AFTER_MILLIS) error("veri akışı durdu")
                    if (now - connectedAt > MAX_CONNECTION_AGE_MILLIS) error("planlı yenileme (24 saat sınırı)")

                    when (val signal = withTimeoutOrNull(TICK_MILLIS) { signals.receive() }) {
                        null, Signal.Open -> Unit
                        is Signal.Closed -> error(signal.reason)
                        is Signal.Text -> {
                            lastMessageAt = timeSource.now()
                            handleText(signal.text)
                        }
                        is Signal.Binary -> {
                            lastMessageAt = timeSource.now()
                            handleBinary(signal.bytes, lastMessageAt)
                            _state.update { it.copy(lastMessageAt = lastMessageAt) }
                        }
                    }
                }
            } finally {
                ws.cancel()
                socket = null
            }
        }
    }

    private fun handleText(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val code = (obj["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (code != 0) _state.update { it.copy(lastError = "MEXC: ${obj["msg"]}") }
    }

    private fun handleBinary(bytes: ByteArray, receivedAt: Long) {
        val message = runCatching { MexcProto.decode(bytes) }.getOrElse {
            _state.update { s -> s.copy(lastError = "Çözülemeyen mesaj: ${it.message}") }
            return
        }
        when (val body = message.body) {
            is MexcProto.Body.BookTickerBody ->
                MexcChannels.toBookTicker(message, receivedAt)?.let { _events.tryEmit(StreamEvent.BookTickerUpdate(it)) }
            is MexcProto.Body.KlineBody -> {
                val timeframe = MexcProto.timeframeOf(body.interval) ?: return
                val symbol = message.symbol ?: return
                _events.tryEmit(StreamEvent.KlineUpdate(symbol, timeframe, body.candle))
            }
            else -> Unit
        }
    }

    private fun request(method: String, channels: Set<String>): String =
        JsonObject(mapOf("method" to JsonPrimitive(method), "params" to JsonArray(channels.map { JsonPrimitive(it) }))).toString()

    companion object {
        const val DEFAULT_URL = "wss://wbs-api.mexc.com/ws"
        const val MAX_SUBSCRIPTIONS_PER_CONNECTION = 30
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val PING_INTERVAL_MILLIS = 20_000L
        private const val STALE_AFTER_MILLIS = 60_000L
        private const val MAX_CONNECTION_AGE_MILLIS = 23 * 60 * 60_000L
        private const val TICK_MILLIS = 1_000L
        private const val INITIAL_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_MILLIS = 60_000L
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}

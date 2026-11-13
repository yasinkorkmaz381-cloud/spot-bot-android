package com.spotbot.android.bot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.spotbot.android.MainActivity
import com.spotbot.android.R
import com.spotbot.android.SpotBotApplication
import com.spotbot.core.exchange.mexc.MexcChannels
import com.spotbot.core.exchange.mexc.MexcWebSocketClient
import com.spotbot.core.exchange.mexc.StreamEvent
import com.spotbot.core.kr.BotMode
import com.spotbot.core.kr.KrPaper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatSignedMoney
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ön plan servisi: Kırılım–Retest döngüsünü ([com.spotbot.core.kr.KrPaper.cycle]) çalıştırır.
 * - FULL: tarama + yeni giriş + koruma; PROTECT_ONLY: yalnız açık pozisyonların stop/hedef takibi.
 * - OFF: servis durur. Açık pozisyon varken bu durum arayüzde kırmızı uyarıyla gösterilir.
 * Android pil kısıtları servisi geciktirebilir; kaçan aralıklar paper kaydında "izleme boşluğu" olarak tutulur.
 */
class BotService : Service() {

    private val container by lazy { (application as SpotBotApplication).container }
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notified = HashSet<String>()
    private var lastClockSync = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OFF -> container.settings.update { it.copy(botMode = BotMode.OFF) }
            ACTION_PROTECT -> container.settings.update { it.copy(botMode = BotMode.PROTECT_ONLY) }
            ACTION_FULL -> container.settings.update { it.copy(botMode = BotMode.FULL) }
        }
        if (container.settings.settings.value.botMode == BotMode.OFF) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, statusNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        acquireWakeLock()
        if (scope == null) loop()
        return START_STICKY
    }

    override fun onDestroy() {
        container.live.update { it.copy(serviceAliveAt = 0L, nextScanAt = 0L) }
        scope?.cancel()
        scope = null
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /**
     * Üç eşzamanlı iş:
     * 1. Tarama: UTC saatine hizalı 3 dakikalık aralıklarla ([KrPaper.SCAN_INTERVAL]); önceki tarama sürüyorsa yenisi
     *    başlamaz ([KrPaper.scan] sayar). Yalnız "Çalışıyor" modunda.
     * 2. Koruma: açık pozisyon varken 10 sn'de bir, fiyat akışında stop/hedef geçilince hemen.
     * 3. Fiyat akışı: açık pozisyonların MEXC WebSocket bookTicker kanalları (kopunca kendisi yeniden bağlanır).
     * Ön plan servisi kullanılır: WorkManager'ın en kısa periyodik aralığı 15 dakikadır, 3 dakikalık tarama ve sürekli
     * stop takibi için uygun değildir. Servis durursa ana ekran bunu "servis çalışmıyor" diye gösterir.
     */
    private fun loop() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        container.paper.state.value.book.let { b -> notified += b.trades.map { "T:" + it.id } + b.positions.map { "P:" + it.id } }
        val urgent = Channel<Unit>(Channel.CONFLATED)
        val stream = MexcWebSocketClient(s)
        s.launch { stream.state.collect { st -> container.live.update { it.copy(stream = st) } } }
        s.launch {
            container.paper.state.map { st -> st.book.positions.map { it.symbol }.toSet() }.distinctUntilChanged().collect { symbols ->
                stream.setSubscriptions(symbols.map { MexcChannels.bookTicker(it) }.toSet())
                if (symbols.isEmpty()) stream.stop() else stream.start()
            }
        }
        s.launch {
            stream.events.collect { e ->
                if (e is StreamEvent.BookTickerUpdate && container.paper.onTick(e.ticker)) urgent.trySend(Unit)
            }
        }
        s.launch {
            while (isActive) {
                if (container.settings.settings.value.botMode == BotMode.OFF) break
                container.live.update { it.copy(serviceAliveAt = System.currentTimeMillis()) }
                runCatching { container.paper.protect() }
                notifyChanges()
                notify(NOTIFICATION_ID, statusNotification())
                val open = container.paper.state.value.book.positions.isNotEmpty()
                withTimeoutOrNull(if (open) KrPaper.PROTECT_INTERVAL else 60_000L) { urgent.receive() }
            }
        }
        s.launch {
            while (isActive) {
                val mode = container.settings.settings.value.botMode
                if (mode == BotMode.OFF) break
                acquireWakeLock()
                if (System.currentTimeMillis() - lastClockSync > 10 * 60_000L) { container.syncClock(); lastClockSync = System.currentTimeMillis() }
                val now = container.clock.now()
                val slot = now - Math.floorMod(now, KrPaper.SCAN_INTERVAL) + KrPaper.SCAN_INTERVAL + KrPaper.SETTLE_MILLIS
                container.live.update { it.copy(nextScanAt = if (mode == BotMode.FULL) slot else 0L) }
                delay(slot - now)
                val m = container.settings.settings.value.botMode
                // Ayrı coroutine: uzun süren tarama zamanlamayı kaydırmaz; çakışmayı KrPaper.scan engeller ve sayar.
                if (m == BotMode.FULL) launch { runCatching { container.paper.scan(m, slot) }; notifyChanges() }
            }
            stream.stop()
            container.live.update { it.copy(serviceAliveAt = 0L, nextScanAt = 0L) }
            ServiceCompat.stopForeground(this@BotService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notifyChanges() {
        val book = container.paper.state.value.book
        for (p in book.positions) {
            if (notified.add("P:" + p.id)) {
                notify(p.id.hashCode(), simpleNotification(CHANNEL_TRADES, getString(R.string.notif_opened, p.symbol), getString(R.string.notif_opened_text, formatPrice(p.entryPrice), formatPrice(p.stop), formatPrice(p.target))))
            }
        }
        for (t in book.trades) {
            if (notified.add("T:" + t.id)) {
                notify(t.id.hashCode() + 1, simpleNotification(CHANNEL_TRADES, getString(R.string.notif_closed, t.symbol), getString(R.string.notif_closed_text, t.reason.title, formatSignedMoney(t.netPnl))))
            }
        }
        container.paper.state.value.lastError?.let { e ->
            if (notified.add("E:$e")) notify(ERROR_ID, simpleNotification(CHANNEL_ALERTS, getString(R.string.notif_problem), e))
        }
    }

    private fun statusNotification(): Notification {
        val st = container.paper.state.value
        val mode = container.settings.settings.value.botMode
        val text = getString(R.string.notif_status_text, mode.title, st.book.positions.size)
        val b = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name) + if (st.lastError != null) " · " + getString(R.string.status_error) else "")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        if (mode == BotMode.FULL) b.addAction(0, getString(R.string.action_protect_only), servicePending(ACTION_PROTECT, 2))
        else b.addAction(0, getString(R.string.action_full), servicePending(ACTION_FULL, 3))
        return b.build()
    }

    private fun simpleNotification(channel: String, title: String, text: String): Notification =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun notify(id: Int, notification: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun servicePending(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, BotService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Zaman aşımlı wake lock; servis çalıştıkça yenilenir, süreç takılırsa sistem kendiliğinden bırakır. */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CryptoScanPro:bot")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
    }

    companion object {
        const val ACTION_FULL = "com.spotbot.android.FULL"
        const val ACTION_PROTECT = "com.spotbot.android.PROTECT"
        const val ACTION_OFF = "com.spotbot.android.OFF"
        private const val NOTIFICATION_ID = 1
        private const val ERROR_ID = 2
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_TRADES = "trades"
        private const val CHANNEL_ALERTS = "alerts"

        fun setMode(context: Context, mode: BotMode) {
            val action = when (mode) {
                BotMode.FULL -> ACTION_FULL
                BotMode.PROTECT_ONLY -> ACTION_PROTECT
                BotMode.OFF -> ACTION_OFF
            }
            val intent = Intent(context, BotService::class.java).setAction(action)
            if (mode == BotMode.OFF) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
        }

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel(CHANNEL_TRADES, context.getString(R.string.channel_trades), NotificationManager.IMPORTANCE_DEFAULT),
                    NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
                ),
            )
        }
    }
}

/** Cihaz yeniden başladığında, kullanıcı botu açık bıraktıysa servisi yeniden başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as SpotBotApplication).container
        val mode = container.settings.settings.value.botMode
        if (mode != BotMode.OFF) BotService.setMode(context, mode)
    }
}

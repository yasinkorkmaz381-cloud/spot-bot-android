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
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.core.engine.EngineEvent
import com.spotbot.core.engine.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Botu 7/24 çalıştıran ön plan servisi. Sistem süreci sonlandırırsa START_STICKY ile yeniden başlatılır;
 * CPU'nun uykuya geçmemesi için kısmi wake lock tutar. Ayrıca pil optimizasyonu muafiyeti önerilir.
 */
class BotService : Service() {

    private val container by lazy { (application as SpotBotApplication).container }
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                container.settings.update { it.copy(botEnabled = false) }
                container.engine.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_ENTRIES -> {
                container.engine.setEntriesPaused(!container.engine.health.value.entriesPaused)
            }
        }
        // Başlatma (ya da sistemin servisi yeniden oluşturması: intent == null).
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, statusNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        acquireWakeLock()
        container.settings.update { it.copy(botEnabled = true) }
        container.engine.start()
        if (scope == null) observe()
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun observe() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            while (isActive) {
                notify(NOTIFICATION_ID, statusNotification())
                acquireWakeLock()
                delay(30_000)
            }
        }
        s.launch {
            container.engine.events.collect { event ->
                val (title, text) = when (event) {
                    is EngineEvent.Opened -> getString(R.string.notif_opened, event.position.symbol) to
                        getString(R.string.notif_opened_text, event.position.strategy.title, event.position.entryPrice.toString(), event.position.stopPrice.toString())
                    is EngineEvent.Closed -> getString(R.string.notif_closed, event.trade.position.symbol) to
                        getString(R.string.notif_closed_text, event.trade.reason.title, formatSignedMoney(event.trade.netPnl))
                    is EngineEvent.Problem -> getString(R.string.notif_problem) to event.message
                }
                val channel = if (event is EngineEvent.Problem) CHANNEL_ALERTS else CHANNEL_TRADES
                notify((System.currentTimeMillis() % 100_000).toInt() + 10, simpleNotification(channel, title, text))
            }
        }
    }

    private fun statusNotification(): Notification {
        val engine = container.engine
        val health = engine.health.value
        val portfolio = engine.portfolio.value
        val equity = portfolio.cash + portfolio.positions.sumOf { it.lastPrice * it.quantity }
        val status = when {
            health.status == EngineStatus.ERROR -> getString(R.string.status_error)
            health.entriesPaused -> getString(R.string.status_paused)
            health.status == EngineStatus.RUNNING -> getString(R.string.status_running)
            else -> getString(R.string.status_starting)
        }
        val text = getString(R.string.notif_status_text, formatMoney(equity), portfolio.positions.size)
        val toggleText = if (health.entriesPaused) R.string.action_resume_entries else R.string.action_pause_entries
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${getString(R.string.app_name)} · $status")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(toggleText), servicePending(ACTION_TOGGLE_ENTRIES, 1))
            .addAction(0, getString(R.string.action_stop_bot), servicePending(ACTION_STOP, 2))
            .build()
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
        const val ACTION_START = "com.spotbot.android.START"
        const val ACTION_STOP = "com.spotbot.android.STOP"
        const val ACTION_TOGGLE_ENTRIES = "com.spotbot.android.TOGGLE_ENTRIES"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 15 * 60_000L
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_TRADES = "trades"
        private const val CHANNEL_ALERTS = "alerts"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, BotService::class.java).setAction(ACTION_START))

        fun stop(context: Context) {
            context.startService(Intent(context, BotService::class.java).setAction(ACTION_STOP))
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
        if (container.settings.settings.value.botEnabled) BotService.start(context)
    }
}

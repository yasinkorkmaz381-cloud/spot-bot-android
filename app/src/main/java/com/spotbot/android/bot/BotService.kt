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
 * Ön plan servisi: Momentum RS-1 döngüsünü ([com.spotbot.core.rs1.Rs1Bot.cycle]) birkaç dakikada bir çalıştırır.
 * Android pil kısıtları servisi geciktirebilir ya da durdurabilir; bu durum Panel'de gösterilir.
 */
class BotService : Service() {

    private val container by lazy { (application as SpotBotApplication).container }
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notified = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.settings.update { it.copy(botEnabled = false) }
            container.bot.setRunning(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, statusNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        acquireWakeLock()
        container.settings.update { it.copy(botEnabled = true) }
        container.bot.setRunning(true)
        if (scope == null) loop()
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        container.bot.setRunning(false)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun loop() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        // Var olan kayıtlar için bildirim gönderme; yalnız bundan sonra oluşanlar.
        container.paper.state.value?.result?.let { r -> notified += r.trades.map { "T:" + it.key } + r.open.map { "P:" + it.key } }
        s.launch {
            while (isActive) {
                acquireWakeLock()
                runCatching { container.bot.cycle() }
                notifyChanges()
                notify(NOTIFICATION_ID, statusNotification())
                delay(CYCLE_MILLIS)
            }
        }
    }

    private fun notifyChanges() {
        val r = container.paper.state.value?.result ?: return
        for (p in r.open) {
            if (notified.add("P:" + p.key)) {
                notify(p.key.hashCode(), simpleNotification(CHANNEL_TRADES, getString(R.string.notif_opened, p.symbol), getString(R.string.notif_opened_text, formatPrice(p.entryPrice), formatPrice(p.stop))))
            }
        }
        for (t in r.trades) {
            if (notified.add("T:" + t.key)) {
                notify(t.key.hashCode() + 1, simpleNotification(CHANNEL_TRADES, getString(R.string.notif_closed, t.symbol), getString(R.string.notif_closed_text, t.reason.title, formatSignedMoney(t.netPnl))))
            }
        }
        container.bot.status.value.lastError?.let { e ->
            if (notified.add("E:$e")) notify(ERROR_ID, simpleNotification(CHANNEL_ALERTS, getString(R.string.notif_problem), e))
        }
    }

    private fun statusNotification(): Notification {
        val r = container.paper.state.value?.result
        val status = container.bot.status.value
        val text = getString(R.string.notif_status_text, formatMoney(r?.endEquity ?: container.config.capital), r?.open?.size ?: 0)
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${getString(R.string.app_name)} · ${getString(if (status.lastError != null) R.string.status_error else R.string.status_running)}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
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
        private const val NOTIFICATION_ID = 1
        private const val ERROR_ID = 2
        private const val CYCLE_MILLIS = 5 * 60_000L
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

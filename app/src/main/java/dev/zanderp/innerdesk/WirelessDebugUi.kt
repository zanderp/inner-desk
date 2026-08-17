package dev.zanderp.innerdesk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

object WirelessDebugUi {
    const val CHANNEL = "innerdesk_pair"
    const val NOTIF_ID = 52
    const val KEY_PIN = "innerdesk_pair_pin"
    const val ACTION_PIN = "dev.zanderp.innerdesk.PAIR_PIN"

    @Volatile var lastHost: String? = null
    @Volatile var lastPort: Int = -1

    private const val PREF_PAIRED = "wireless_paired"

    @Volatile var needsPairing: Boolean = false

    fun isPaired(context: Context): Boolean =
        AppSession.prefs(context).getBoolean(PREF_PAIRED, false)

    fun markPaired(context: Context) {
        needsPairing = false
        AppSession.prefs(context).edit().putBoolean(PREF_PAIRED, true).apply()
    }

    fun clearPaired(context: Context) {
        needsPairing = true
        AppSession.prefs(context).edit().putBoolean(PREF_PAIRED, false).apply()
    }

    fun onAuthorized(context: Context) {
        markPaired(context)
        clear(context)
    }

    fun onPaired(context: Context) {
        markPaired(context)
        clear(context)
    }

    fun onConnectFailed(context: Context) {
        if (WirelessAdb.live || WirelessAdb.paired || daemonReady()) {
            needsPairing = false
            return
        }
        clearPaired(context)
    }

    fun pairingNeeded(): Boolean =
        !WirelessAdb.live && !WirelessAdb.paired && !daemonReady()

    fun daemonReady(): Boolean {
        return try {
            AppSession.overlayMirror.pingAlive()
        } catch (_: Exception) {
            false
        }
    }

    fun openSettings(context: Context) {
        DexOverlayService.hide()
        val intents = listOf(
            Intent("com.android.settings.WIFI_DEBUGGING_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity"),
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            },
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    fun notifyPairing(context: Context, host: String?, port: Int?) {
        if (!host.isNullOrBlank()) lastHost = host
        if (port != null && port > 0) lastPort = port
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wireless debugging", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Type the pairing PIN here"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            52,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_PAIR)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = PendingIntent.getBroadcast(
            context,
            53,
            Intent(context, PairingReceiver::class.java).setAction(ACTION_PIN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remote = RemoteInput.Builder(KEY_PIN)
            .setLabel("6-digit PIN")
            .build()
        val pinAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            "Enter PIN",
            reply,
        )
            .addRemoteInput(remote)
            .setAllowGeneratedReplies(false)
            .build()
        val title = if (lastPort > 0) "Pairing port $lastPort — type PIN here" else "Type the pairing PIN here"
        val body = buildString {
            append("Expand this notification and tap Enter PIN.")
            if (lastPort > 0) append(" Port ").append(lastPort).append('.')
            if (!lastHost.isNullOrBlank()) append(" Host ").append(lastHost).append('.')
            append(" Wireless debugging → Pair with pairing code.")
        }
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText("Expand → Enter PIN")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .addAction(pinAction)
                .setAutoCancel(false)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build(),
        )
    }

    fun notifyStatus(context: Context, title: String, text: String, ongoing: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wireless debugging", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            52,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}

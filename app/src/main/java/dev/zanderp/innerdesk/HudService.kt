package dev.zanderp.innerdesk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class HudService : Service() {
    private lateinit var wm: WindowManager
    private var edgeView: View? = null
    private var bubbleView: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifText: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            val alive = ShizukuKeepAlive.beat(this@HudService)
            if (AppSession.dexRunning) {
                val text = if (alive) {
                    "Desktop on. Pull down this notification → Stop desktop"
                } else {
                    "Reconnecting privileged daemon…"
                }
                updateNotification(text)
                CrashGuard.persistSession(this@HudService)
            }
            handler.postDelayed(this, 3_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ensureChannel()
        startForeground(NOTIF_ID, baseNotification("InnerDesk is running").build())
        acquireWakeLock()
        handler.postDelayed(watchdog, 3_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SESSION -> {
                hideEdge()
                hideBubble()
                updateNotification("Desktop on. Pull down this notification → Stop desktop")
            }
            ACTION_RELOAD -> reloadDex()
            ACTION_SHARE_LOG -> LogShare.share(this)
            ACTION_CLOSE -> closeSession()
            ACTION_OPEN_SHIZUKU -> ShizukuKeepAlive.openShizuku(this)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!AppSession.dexRunning) {
            handler.removeCallbacks(watchdog)
            releaseWakeLock()
            hideEdge()
            hideBubble()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        releaseWakeLock()
        hideEdge()
        hideBubble()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "innerdesk:session")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }


    private fun showEdge() {
        if (edgeView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val edge = View(this).apply {
            setBackgroundColor(0x334EA1FF)
            var downX = 0f
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (downX - ev.rawX > 48) showBubble()
                        true
                    }
                    else -> false
                }
            }
        }
        val lp = overlayParams(dp(28), WindowManager.LayoutParams.MATCH_PARENT, Gravity.END)
        edgeView = edge
        wm.addView(edge, lp)
    }

    private fun hideEdge() {
        edgeView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        edgeView = null
    }

    private fun showBubble() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2171C24.toInt())
            setPadding(24, 20, 24, 20)
            addView(TextView(this@HudService).apply {
                text = "InnerDesk"
                setTextColor(0xFFF2F5F8.toInt())
                textSize = 16f
            })
            addView(Button(this@HudService).apply {
                text = "Reload desktop"
                setOnClickListener { reloadDex() }
            })
            addView(Button(this@HudService).apply {
                text = "Close and clear"
                setOnClickListener { closeSession() }
            })
            addView(Button(this@HudService).apply {
                text = "Hide"
                setOnClickListener { hideBubble() }
            })
        }
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL,
        )
        bubbleView = box
        wm.addView(box, lp)
    }

    private fun hideBubble() {
        bubbleView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
    }


    private fun reloadDex() {
        thread {
            try {
                val shell = AppSession.shell
                if (shell != null) {
                    DexStarter.clearSession(shell)
                    android.os.Handler(mainLooper).post {
                        Toast.makeText(this, "Session cleared. Restart from app.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.os.Handler(mainLooper).post {
                        Toast.makeText(this, "Privileged shell not available", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, e.message ?: "Reload failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun closeSession() {
        AppSession.setDexRunning(this, false)
        DexOverlayService.hide()
        AppSession.overlayMirror.stop()
        thread {
            try {
                AppSession.shell?.let { DexStarter.clearSession(it) }
            } catch (_: Exception) {}
            android.os.Handler(mainLooper).post {
                hideBubble()
                hideEdge()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun overlayParams(w: Int, h: Int, gravity: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { this.gravity = gravity }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("innerdesk_session")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "InnerDesk session", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText) return
        lastNotifText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, baseNotification(text).build())
    }

    private fun baseNotification(text: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            3,
            Intent(this, HudService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val share = PendingIntent.getService(
            this,
            4,
            Intent(this, HudService::class.java).setAction(ACTION_SHARE_LOG),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openShizuku = PendingIntent.getService(
            this,
            5,
            Intent(this, HudService::class.java).setAction(ACTION_OPEN_SHIZUKU),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("InnerDesk")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Share log", share)
            .addAction(0, "Open Shizuku", openShizuku)
            .addAction(0, "Stop desktop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    companion object {
        const val ACTION_SESSION = "dev.zanderp.innerdesk.SESSION"
        const val ACTION_RELOAD = "dev.zanderp.innerdesk.RELOAD"
        const val ACTION_CLOSE = "dev.zanderp.innerdesk.CLOSE"
        const val ACTION_SHARE_LOG = "dev.zanderp.innerdesk.SHARE_LOG"
        const val ACTION_OPEN_SHIZUKU = "dev.zanderp.innerdesk.OPEN_SHIZUKU"
        private const val CHANNEL = "innerdesk_session_quiet"
        private const val NOTIF_ID = 41

        fun showSession(context: Context) {
            context.startForegroundService(
                Intent(context, HudService::class.java).setAction(ACTION_SESSION),
            )
        }

        fun stopSession(context: Context) {
            stopQuiet(context)
        }

        fun stopQuiet(context: Context) {
            try {
                context.stopService(Intent(context, HudService::class.java))
            } catch (_: Exception) {}
        }
    }
}

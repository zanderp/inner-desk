package dev.zanderp.innerdesk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import rikka.shizuku.Shizuku

object ShizukuKeepAlive {

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val SELF_PKG = "dev.zanderp.innerdesk"
    private const val WATCH_PATH = "/data/local/tmp/idx-shizuku-watch.sh"

    @Volatile
    private var registered = false
    @Volatile
    private var dozeApplied = false
    private val main = Handler(Looper.getMainLooper())
    private val reconnect = object : Runnable {
        override fun run() {
            if (AppSession.overlayMirror.pingAlive()) {
                AppSession.overlayMirror.ensureBound()
                if (AppSession.dexRunning) DexOverlayService.rebindMirror()
                AppSession.onShizukuChanged?.invoke(true)
                return
            }
            if (DaemonShell.isAvailable()) {
                AppSession.overlayMirror.ensureBound()
                if (AppSession.dexRunning) DexOverlayService.rebindMirror()
                AppSession.onShizukuChanged?.invoke(true)
                main.postDelayed(this, 1000)
                return
            }
            if (isShizukuAlive()) {
                AppSession.shizuku?.reset()
                AppSession.overlayMirror.ensureBound()
                if (AppSession.dexRunning) DexOverlayService.rebindMirror()
                AppSession.onShizukuChanged?.invoke(true)
                return
            }
            main.postDelayed(this, 1000)
        }
    }

    fun register(app: Application) {
        if (registered) return
        registered = true
        try {
            Shizuku.addBinderReceivedListenerSticky {
                main.removeCallbacks(reconnect)
                main.post {
                    AppSession.shizuku?.reset()
                    if (ShizukuShell.hasPermission()) {
                        AppSession.overlayMirror.ensureBound()
                        Thread {
                            try {
                                AppSession.shell?.let { shell ->
                                    if (!dozeApplied) {
                                        applyDozeWhitelist(shell)
                                        dozeApplied = true
                                    }
                                    installWatchdog(app, shell)
                                }
                            } catch (_: Exception) {}
                        }.start()
                        if (AppSession.dexRunning) {
                            DexOverlayService.rebindMirror()
                        }
                    }
                    AppSession.onShizukuChanged?.invoke(true)
                }
            }
            Shizuku.addBinderDeadListener {
                AppSession.shizuku?.reset()
                main.post {
                    AppSession.onShizukuChanged?.invoke(false)
                    main.removeCallbacks(reconnect)
                    main.postDelayed(reconnect, 1000)
                }
            }
        } catch (_: Exception) {}
    }

    fun ensureDozeWhitelist() {
        refreshKeepAlive(null)
    }

    fun refreshKeepAlive(context: Context?) {
        val shell = AppSession.shell ?: return
        val app = context?.applicationContext
        Thread {
            try {
                if (!dozeApplied) {
                    applyDozeWhitelist(shell)
                    dozeApplied = true
                }
                if (app != null) installWatchdog(app, shell)
            } catch (_: Exception) {}
        }.start()
    }

    fun requestBatteryExemption(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun applyDozeWhitelist(shell: PrivilegedShell) {
        val pkgs = listOf(SELF_PKG, SHIZUKU_PKG)
        for (pkg in pkgs) {
            shell.runQuiet("dumpsys deviceidle whitelist +$pkg")
            shell.runQuiet("cmd deviceidle whitelist +$pkg")
            shell.runQuiet("am set-inactive $pkg false")
            shell.runQuiet("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            shell.runQuiet("cmd appops set $pkg RUN_IN_BACKGROUND allow")
        }
        shell.runQuiet("for p in \$(pidof shizuku_server); do echo -1000 > /proc/\$p/oom_score_adj; done")
        AppSession.appendLog("KeepAlive: doze whitelist applied")
    }

    fun installWatchdog(context: Context, shell: PrivilegedShell) {
        val script = try {
            context.assets.open("idx-shizuku-watch.sh").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n")
                .replace("\r", "\n")
        } catch (e: Exception) {
            AppSession.appendLog("KeepAlive: missing watchdog asset: ${e.message}")
            return
        }
        val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        shell.runQuiet("printf '%s' '$b64' | base64 -d > $WATCH_PATH")
        shell.runQuiet("chmod 700 $WATCH_PATH")
        shell.runQuiet(
            "sh -c 'setsid /system/bin/sh $WATCH_PATH </dev/null >>/data/local/tmp/idx-shizuku-watch.log 2>&1 &'",
        )
        AppSession.appendLog("KeepAlive: priv watchdog armed")
    }

    fun isShizukuAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun beat(context: Context): Boolean {
        if (AppSession.overlayMirror.pingAlive()) return true
        AppSession.overlayMirror.ensureBound()
        if (AppSession.overlayMirror.pingAlive()) return true
        if (DaemonShell.isAvailable()) return false
        if (!isShizukuAlive()) {
            AppSession.shizuku?.reset()
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, 1000)
        }
        return false
    }

    fun openShizuku(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(launch)
                AppSession.appendLog("KeepAlive: opened Shizuku")
                return
            } catch (_: Exception) {}
        }
        val names = listOf(
            "moe.shizuku.manager.MainActivity",
            "moe.shizuku.manager.StarterActivity",
        )
        for (cls in names) {
            try {
                context.startActivity(
                    Intent().setClassName(SHIZUKU_PKG, cls)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                AppSession.appendLog("KeepAlive: started $cls")
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(context, "Open Shizuku and tap Start", Toast.LENGTH_LONG).show()
        AppSession.appendLog("KeepAlive: could not open Shizuku")
    }
}

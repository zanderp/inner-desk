package dev.zanderp.innerdesk

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

object DesktopControls {
    fun refresh(context: Context) {
        val app = context.applicationContext
        try {
            TileService.requestListeningState(
                app,
                ComponentName(app, DesktopTileService::class.java),
            )
        } catch (_: Exception) {
        }
        DesktopWidgetProvider.refreshAll(app)
    }

    fun toggle(context: Context) {
        if (AppSession.dexRunning) stop(context) else start(context)
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (!DexStarter.detectSupport(app).supported) {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    ),
            )
            return
        }
        val intent = MainActivity.startDesktopIntent(app)
        if (context is TileService) {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    app,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                context.startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                context.startActivityAndCollapse(intent)
            }
        } else {
            app.startActivity(intent)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                AppSession.shell?.let { DexStarter.hideSamsungTouchpad(it) }
            } catch (_: Exception) {
            }
            try {
                AppSession.shutdownBlocking(app)
            } catch (_: Exception) {
            }
        }.start()
    }
}

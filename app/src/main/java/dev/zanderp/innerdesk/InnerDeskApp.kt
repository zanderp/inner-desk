package dev.zanderp.innerdesk

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import org.conscrypt.Conscrypt
import java.security.Security

class InnerDeskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (_: Exception) {}
        if (AppSession.shizuku == null) {
            AppSession.shizuku = ShizukuShell()
        }
        CrashGuard.install(this)
        CrashGuard.hydrate(this)
        try {
            AnonymousTelemetry.onAppStart(this)
        } catch (_: Exception) {}
        ShizukuKeepAlive.register(this)
        try {
            startService(android.content.Intent(this, PrivHookService::class.java))
        } catch (_: Exception) {}
        registerReceiver(
            PrivReceiver(),
            android.content.IntentFilter("dev.zanderp.innerdesk.PRIV_BINDER"),
            Context.RECEIVER_EXPORTED,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DexOverlayService.resizeForOrientation()
    }
}

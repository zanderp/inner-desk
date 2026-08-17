package dev.zanderp.innerdesk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class PrivReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val binder = intent.extras?.getBinder("b") ?: return
        try {
            val uid = IMirrorService.Stub.asInterface(binder).ping()
            if (uid != 2000 && uid != 0) return
        } catch (_: Exception) {
            return
        }
        PrivProvider.serviceBinder = binder
        Handler(Looper.getMainLooper()).post {
            AppSession.overlayMirror.attachExternal(binder)
            AppSession.onShizukuChanged?.invoke(true)
        }
        AppSession.appendLog("Priv: binder via broadcast")
    }
}

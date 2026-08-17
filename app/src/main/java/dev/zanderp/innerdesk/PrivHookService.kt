package dev.zanderp.innerdesk

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Parcel
import android.util.Log

class PrivHookService : Service() {
    private val hook = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                val uid = getCallingUid()
                if (uid != 2000 && uid != 0) return false
                accept(data.readStrongBinder(), "transact")
                reply?.writeNoException()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent?): IBinder = hook

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extras = intent?.extras
        val keys = extras?.keySet()?.joinToString()
        val fromMessenger = try {
            extras?.getParcelable("m", Messenger::class.java)?.binder
        } catch (_: Exception) {
            null
        }
        val fromBinder = extras?.getBinder("b")
        Log.i(TAG, "onStart keys=$keys messenger=$fromMessenger binder=$fromBinder")
        AppSession.appendLog("PrivHook: onStart keys=$keys m=${fromMessenger != null} b=${fromBinder != null}")
        Handler(Looper.getMainLooper()).post { accept(fromMessenger ?: fromBinder, "start") }
        return START_STICKY
    }

    private fun accept(b: IBinder?, via: String) {
        if (b == null) return
        AppSession.appendLog("Priv: binder via $via")
        Log.i(TAG, "accepted via $via")
        PrivProvider.serviceBinder = b
        AppSession.overlayMirror.attachExternal(b)
        AppSession.onShizukuChanged?.invoke(true)
    }

    companion object {
        private const val TAG = "PrivHook"
    }
}

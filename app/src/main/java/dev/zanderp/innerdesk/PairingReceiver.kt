package dev.zanderp.innerdesk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WirelessDebugUi.ACTION_PIN) return
        val pin = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(WirelessDebugUi.KEY_PIN)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (pin.length < 6) {
            WirelessDebugUi.notifyPairing(context, WirelessDebugUi.lastHost, WirelessDebugUi.lastPort)
            Toast.makeText(context, "Enter the 6-digit PIN", Toast.LENGTH_LONG).show()
            return
        }
        if (WirelessDebugUi.lastPort <= 0) {
            WirelessDebugUi.notifyPairing(context, WirelessDebugUi.lastHost, WirelessDebugUi.lastPort)
            Toast.makeText(context, "Waiting for pairing port. Keep Wireless debugging open.", Toast.LENGTH_LONG).show()
            return
        }
        WirelessDebugUi.notifyStatus(context, "Pairing…", "Starting privileged daemon", ongoing = true)
        val pending = goAsync()
        Thread {
            val result = try {
                val adb = WirelessAdb.get(context)
                adb.pairAt(WirelessDebugUi.lastHost ?: "127.0.0.1", WirelessDebugUi.lastPort, pin)
                WirelessDebugUi.onPaired(context)
                AppSession.onShizukuChanged?.invoke(true)
                adb.startDaemon()
            } catch (e: Exception) {
                e.message ?: "pair failed"
            }
            val ok = result.contains("daemon", true) || AppSession.overlayMirror.pingAlive()
            if (ok) {
                WirelessDebugUi.onAuthorized(context)
                AppSession.appendLog("Pair (notification): $result")
                AppSession.onShizukuChanged?.invoke(true)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.wireless_connected), Toast.LENGTH_SHORT).show()
                }
            } else if (WirelessAdb.paired) {
                WirelessDebugUi.onPaired(context)
                AppSession.appendLog("Paired. Connect: $result")
                AppSession.onShizukuChanged?.invoke(true)
            } else {
                WirelessDebugUi.onConnectFailed(context)
                WirelessDebugUi.notifyPairing(context, WirelessDebugUi.lastHost, WirelessDebugUi.lastPort)
                AppSession.appendLog("Pair failed: $result")
            }
            pending.finish()
        }.start()
    }
}

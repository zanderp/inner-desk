package dev.zanderp.innerdesk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper

class PrivProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "sendBinder") {
            extras?.classLoader = IMirrorService::class.java.classLoader
            val binder = extras?.getBinder("b")
            if (binder != null) {
                serviceBinder = binder
                Handler(Looper.getMainLooper()).post {
                    AppSession.overlayMirror.attachExternal(binder)
                    AppSession.onShizukuChanged?.invoke(true)
                }
                AppSession.appendLog("Priv: binder received")
            }
            return Bundle()
        }
        return null
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        @Volatile
        var serviceBinder: IBinder? = null

        fun service(): IMirrorService? {
            val b = serviceBinder ?: return null
            return try {
                IMirrorService.Stub.asInterface(b)
            } catch (_: Exception) {
                null
            }
        }
    }
}

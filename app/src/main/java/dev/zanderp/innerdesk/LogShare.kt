package dev.zanderp.innerdesk

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object LogShare {
    fun share(context: Context) {
        val text = AppSession.logText().ifBlank { "(empty log)" }
        try {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "innerdesk.log")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "innerdesk log")
                putExtra(Intent.EXTRA_TEXT, "innerdesk log")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("innerdesk log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "innerdesk log")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, e.message ?: "Share failed", Toast.LENGTH_LONG).show()
        }
    }
}

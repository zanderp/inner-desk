package dev.zanderp.innerdesk

import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UpdateUi {

    fun maybeCheck(activity: AppCompatActivity) {
        if (!BuildConfig.GITHUB_UPDATES) return
        activity.lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                try {
                    UpdateChecker.check(activity, manual = false)
                } catch (_: Exception) {
                    null
                }
            } ?: return@launch
            showDialog(activity, release)
        }
    }

    fun checkManual(activity: AppCompatActivity) {
        if (!BuildConfig.GITHUB_UPDATES) {
            Toast.makeText(activity, R.string.update_fdroid, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                UpdateChecker.check(activity, manual = true)
            }
            if (release == null) {
                Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
            } else {
                showDialog(activity, release)
            }
        }
    }

    fun applyVisibility(checkButton: View) {
        checkButton.visibility = if (BuildConfig.GITHUB_UPDATES) View.VISIBLE else View.GONE
    }

    private fun showDialog(activity: AppCompatActivity, release: UpdateChecker.Release) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setMessage(ReleaseNotes.toSpanned(release.notes))
            .setPositiveButton(R.string.update_download) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
                } catch (_: Exception) {
                    Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.update_skip) { _, _ ->
                UpdateChecker.skip(activity, release.version)
            }
            .setNeutralButton(R.string.update_later, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }
}

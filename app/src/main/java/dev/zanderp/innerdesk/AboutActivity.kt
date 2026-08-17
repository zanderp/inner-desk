package dev.zanderp.innerdesk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView

/** Copyright, credits, Discord, GitHub, and donate. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_about)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.about_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        findViewById<TextView>(R.id.about_version).text =
            "Version ${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}"

        findViewById<MaterialButton>(R.id.btn_about_website).setOnClickListener {
            openUrl(URL_WEBSITE)
        }
        findViewById<MaterialButton>(R.id.btn_about_github).setOnClickListener {
            openUrl(URL_GITHUB)
        }
        findViewById<MaterialButton>(R.id.btn_about_update).let { btn ->
            UpdateUi.applyVisibility(btn)
            btn.setOnClickListener { UpdateUi.checkManual(this) }
        }
        findViewById<MaterialButton>(R.id.btn_about_discord).setOnClickListener {
            openUrl(URL_DISCORD)
        }
        findViewById<MaterialButton>(R.id.btn_about_kofi).setOnClickListener {
            openUrl(URL_KOFI)
        }
        findViewById<MaterialSwitch>(R.id.switch_telemetry).apply {
            isChecked = PrivacyPrefs.telemetryEnabled(this@AboutActivity)
            setOnCheckedChangeListener { _, on ->
                PrivacyPrefs.setTelemetryEnabled(this@AboutActivity, on)
            }
        }
        findViewById<MaterialButton>(R.id.btn_about_done).setOnClickListener { finish() }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val URL_WEBSITE = "https://alexandru.rocks"
        const val URL_GITHUB = "https://github.com/zanderp/inner-desk"
        const val URL_DISCORD = "https://discord.gg/RJFeaetayh"
        const val URL_KOFI = "https://ko-fi.com/alexandrupopa"

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, AboutActivity::class.java))
        }

        fun openUrl(ctx: Context, url: String, failMessage: Int = R.string.about_open_failed) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(ctx, failMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

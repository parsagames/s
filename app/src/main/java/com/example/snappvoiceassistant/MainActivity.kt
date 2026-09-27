package com.example.snappvoiceassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    companion object {
        const val PREFS_NAME = "voice_assistant_prefs"
        const val KEY_ENABLED = "assistant_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)

        // درخواست مجوز میکروفون (برای شنیدن فرمان "قبول")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        val status: TextView = findViewById(R.id.statusText)
        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)

        if (!isAccessibilityServiceEnabled()) {
            status.text = getString(R.string.permission_hint)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener {
            setAssistantEnabled(true)
            speak("دستیار صوتی فعال شد")
            status.text = "دستیار صوتی فعال است"
        }

        btnStop.setOnClickListener {
            setAssistantEnabled(false)
            speak("دستیار صوتی غیر فعال شد")
            status.text = "دستیار صوتی غیر فعال است"
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val faLocale = Locale("fa", "IR")
            val result = tts.isLanguageAvailable(faLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // بستهٔ زبان فارسی روی موتور تبدیل‌متن‌به‌گفتار گوشی نصب نیست؛
                // صفحهٔ نصب بستهٔ زبان را باز می‌کنیم تا کاربر آن را دانلود کند.
                findViewById<TextView>(R.id.statusText).text =
                    "بستهٔ زبان فارسی برای صحبت‌کردن نصب نیست. صفحه‌ای برای نصب آن باز می‌شود؛ لطفاً «فارسی» را دانلود و نصب کنید و دوباره اپ را باز کنید."
                try {
                    startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                } catch (e: Exception) {
                    // اگر موتور TTS گوشی صفحهٔ نصب نداشت، کاربر را به تنظیمات راهنمایی می‌کنیم
                    findViewById<TextView>(R.id.statusText).text =
                        "برای نصب زبان فارسی به: تنظیمات > سیستم > زبان‌ها و ورودی > خروجی تبدیل متن به گفتار، بروید و بستهٔ فارسی را نصب کنید."
                }
            } else {
                tts.language = faLocale
                ttsReady = true
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "main_activity_utt")
        }
    }

    private fun setAssistantEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/.OrderAccessibilityService"
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServicesSetting.contains(expectedComponentName)
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}

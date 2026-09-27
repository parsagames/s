package com.example.snappvoiceassistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * موتور صوتی: هم متن را با صدای فارسی می‌خواند (TTS)
 * و هم بعد از خواندن سفارش، به دنبال فرمان صوتی "قبول" می‌گردد (STT).
 */
class VoiceEngine(
    private val context: Context,
    private val onAcceptCommandHeard: () -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listeningForAccept = false

    private val acceptWords = listOf("قبول", "تایید", "قبوله", "تأیید", "باشه قبوله")

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val faLocale = Locale("fa", "IR")
                val result = tts?.isLanguageAvailable(faLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // بستهٔ صوتی فارسی نصب نیست؛ صفحهٔ نصب زبان را باز می‌کنیم.
                    try {
                        val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(installIntent)
                    } catch (e: Exception) {
                        // اگر امکان باز کردن صفحهٔ نصب نبود، کاری از دست این سرویس ساخته نیست؛
                        // کاربر باید از طریق اپ اصلی (MainActivity) بستهٔ زبان را نصب کند.
                    }
                } else {
                    tts?.language = faLocale
                    ttsReady = true
                }
            }
        }
    }

    /** متن فارسی را می‌خواند و بعد از پایان خواندن، شنیدن فرمان "قبول" را شروع می‌کند. */
    fun speakThenListenForAccept(text: String) {
        if (!ttsReady || tts == null) return
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "order_utt") {
                    startListeningForAccept()
                }
            }
        })
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "order_utt")
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "misc_utt")
    }

    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // اگر بستهٔ آفلاین فارسی روی گوشی نصب باشد، ترجیح می‌دهیم بدون اینترنت کار کند؛
            // در غیر این صورت، خودِ گوشی به‌صورت خودکار از تشخیص آنلاین استفاده می‌کند.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private fun startListeningForAccept() {
        if (listeningForAccept) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // روی این گوشی، قابلیت تشخیص گفتار در دسترس نیست (یا زبان فارسی برایش نصب نشده).
            speak("قابلیت تشخیص گفتار در این گوشی در دسترس نیست. برای فعال‌سازی، در تنظیمات، تایپ صوتی گوگل را نصب و زبان فارسی را در آن اضافه کنید.")
            return
        }
        listeningForAccept = true

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = buildRecognizerIntent()

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                ) {
                    stopListening()
                    speak("زبان فارسی برای تشخیص گفتار روی این گوشی نصب نیست. لطفاً در تنظیمات، تایپ صوتی گوگل را باز کنید و زبان فارسی را (ترجیحاً به‌صورت آفلاین) نصب کنید.")
                    return
                }
                // در بقیهٔ خطاها (مثل سکوت طولانی)، دوباره شروع کن تا کاربر فرمان بدهد یا سرویس متوقف شود
                restartListening()
            }

            override fun onResults(results: Bundle?) {
                handleResult(results)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handleResult(partialResults)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)
    }

    private fun handleResult(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val heard = matches?.joinToString(" ") ?: return
        val said = heard.trim()
        if (acceptWords.any { said.contains(it) }) {
            stopListening()
            onAcceptCommandHeard()
        } else {
            restartListening()
        }
    }

    private fun restartListening() {
        if (!listeningForAccept) return
        recognizer?.cancel()
        recognizer?.startListening(buildRecognizerIntent())
    }

    fun stopListening() {
        listeningForAccept = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    fun shutdown() {
        stopListening()
        tts?.stop()
        tts?.shutdown()
    }
}

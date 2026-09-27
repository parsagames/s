package com.example.snappvoiceassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * این سرویس صفحهٔ سفارش اسنپ‌باکس را می‌خواند:
 * - مبلغ را با یک صفر کمتر و به‌جای «ریال»، «تومان» می‌گوید
 * - آدرس(ها) را می‌خواند
 * - رنگ دکمهٔ پایین صفحه را تشخیص می‌دهد و می‌گوید
 * سپس منتظر فرمان صوتی «قبول» می‌ماند و در صورت شنیدن آن، همان دکمه را کلیک می‌کند.
 *
 * توجه: قبل از استفاده، packageNames را در
 * res/xml/accessibility_service_config.xml با پکیج واقعی اپ اسنپ‌باکس جایگزین کنید.
 */
class OrderAccessibilityService : AccessibilityService() {

    private val TAG = "OrderVoiceAssistant"
    private lateinit var voiceEngine: VoiceEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    private var lastAnnouncedSignature: String? = null
    private var pendingAcceptNode: AccessibilityNodeInfo? = null

    private val priceRegex = Regex("([\\d,٬]{3,})\\s*ریال")

    override fun onServiceConnected() {
        super.onServiceConnected()
        voiceEngine = VoiceEngine(this) { onAcceptCommandHeard() }
        voiceEngine.init()
    }

    private fun isAssistantEnabled(): Boolean {
        return getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_ENABLED, false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isAssistantEnabled()) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // چون تغییرات محتوا ممکن است پشت‌سرهم زیاد بیاید، کمی صبر می‌کنیم (debounce)
        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { scanScreenForOrder() }
        debounceRunnable = runnable
        mainHandler.postDelayed(runnable, 400)
    }

    private fun scanScreenForOrder() {
        val root = rootInActiveWindow ?: return

        val texts = mutableListOf<String>()
        val clickableCandidates = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, texts, clickableCandidates)

        val fullText = texts.joinToString(" | ")
        val priceMatch = priceRegex.find(fullText) ?: return // این صفحه، صفحهٔ سفارش با مبلغ ریالی نیست

        val signature = fullText.hashCode().toString()
        if (signature == lastAnnouncedSignature) return // همین سفارش قبلاً خوانده شده
        lastAnnouncedSignature = signature

        // ۱) مبلغ: یک صفر کم کن و به‌جای ریال، تومان بگو
        val tomanText = rialToTomanSpeech(priceMatch.groupValues[1])

        // ۲) آدرس‌ها: خط‌هایی که طولانی‌تر از یک آستانه هستند و شامل کلماتی مثل خیابان/کوچه/محله/میدان اند،
        // یا هر متن بلندِ غیرِ مبلغ را به‌عنوان آدرس در نظر می‌گیریم.
        val addressLines = texts.filter { line ->
            line.length > 12 && !priceRegex.containsMatchIn(line)
        }
        val addressText = if (addressLines.isNotEmpty()) addressLines.joinToString("، ") else "آدرسی یافت نشد"

        // ۳) دکمهٔ پایین صفحه (بزرگ‌ترین دکمهٔ قابل‌کلیک نزدیک پایین صفحه)
        val screenHeight = resources.displayMetrics.heightPixels
        val bottomButton = clickableCandidates
            .filter { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.bottom > screenHeight * 0.75
            }
            .maxByOrNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.width().toLong() * bounds.height().toLong()
            }

        pendingAcceptNode = bottomButton

        if (bottomButton != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sampleButtonColorAndAnnounce(bottomButton, tomanText, addressText)
        } else {
            announceOrder(tomanText, addressText, "نامشخص")
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        texts: MutableList<String>,
        clickable: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) texts.add(t)
        if (node.isClickable) clickable.add(node)
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), texts, clickable)
        }
    }

    /** رشتهٔ مبلغ ریالی (مثلاً 770,000) را به «۷۷,۰۰۰ تومان»-گونه برای خواندن آماده می‌کند. */
    private fun rialToTomanSpeech(rawDigits: String): String {
        val digitsOnly = rawDigits.replace(",", "").replace("٬", "")
        val rialValue = digitsOnly.toLongOrNull() ?: return "$rawDigits تومان"
        val tomanValue = rialValue / 10 // حذف یک صفر: تبدیل ریال به تومان
        val formatted = "%,d".format(tomanValue).replace(",", "،")
        return "$formatted تومان"
    }

    private fun sampleButtonColorAndAnnounce(
        buttonNode: AccessibilityNodeInfo,
        tomanText: String,
        addressText: String
    ) {
        val bounds = Rect()
        buttonNode.getBoundsInScreen(bounds)

        takeScreenshot(
            0, // DEFAULT_DISPLAY
            Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()

                        val colorName = if (softwareBitmap != null) {
                            val cx = bounds.centerX().coerceIn(0, softwareBitmap.width - 1)
                            val cy = bounds.centerY().coerceIn(0, softwareBitmap.height - 1)
                            val pixel = softwareBitmap.getPixel(cx, cy)
                            ColorUtils.nearestPersianColorName(
                                (pixel shr 16) and 0xFF,
                                (pixel shr 8) and 0xFF,
                                pixel and 0xFF
                            )
                        } else "نامشخص"

                        mainHandler.post { announceOrder(tomanText, addressText, colorName) }
                    } catch (e: Exception) {
                        Log.e(TAG, "خطا در نمونه‌برداری رنگ دکمه", e)
                        mainHandler.post { announceOrder(tomanText, addressText, "نامشخص") }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    mainHandler.post { announceOrder(tomanText, addressText, "نامشخص") }
                }
            }
        )
    }

    private fun announceOrder(tomanText: String, addressText: String, colorName: String) {
        val sentence = "سفارش جدید. مبلغ: $tomanText. آدرس: $addressText. رنگ دکمهٔ پایین صفحه: $colorName است. " +
                "اگر قبول دارید بگویید قبول."
        voiceEngine.speakThenListenForAccept(sentence)
    }

    private fun onAcceptCommandHeard() {
        val node = pendingAcceptNode
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            voiceEngine.speak("قبول شد")
        } else {
            voiceEngine.speak("دکمه‌ای برای کلیک پیدا نشد")
        }
        pendingAcceptNode = null
        lastAnnouncedSignature = null // اجازه بده سفارش بعدی دوباره اعلام شود
    }

    override fun onInterrupt() {
        voiceEngine.stopListening()
    }

    override fun onDestroy() {
        voiceEngine.shutdown()
        super.onDestroy()
    }
}

package com.example.snappvoiceassistant

import kotlin.math.sqrt

object ColorUtils {

    private val namedColors = listOf(
        Triple(0, 128, 0) to "سبز",
        Triple(255, 0, 0) to "قرمز",
        Triple(0, 0, 255) to "آبی",
        Triple(255, 255, 0) to "زرد",
        Triple(255, 165, 0) to "نارنجی",
        Triple(128, 0, 128) to "بنفش",
        Triple(255, 255, 255) to "سفید",
        Triple(0, 0, 0) to "مشکی",
        Triple(128, 128, 128) to "خاکستری",
        Triple(165, 42, 42) to "قهوه‌ای"
    )

    /** نزدیک‌ترین نام رنگ فارسی را برای یک پیکسل RGB برمی‌گرداند. */
    fun nearestPersianColorName(r: Int, g: Int, b: Int): String {
        var best = "نامشخص"
        var bestDist = Double.MAX_VALUE
        for ((rgb, name) in namedColors) {
            val (nr, ng, nb) = rgb
            val d = sqrt(
                ((r - nr) * (r - nr) +
                        (g - ng) * (g - ng) +
                        (b - nb) * (b - nb)).toDouble()
            )
            if (d < bestDist) {
                bestDist = d
                best = name
            }
        }
        return best
    }
}

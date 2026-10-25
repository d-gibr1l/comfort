package com.comfort.app.util

import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.text.FieldPosition
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Sizes, speeds and dates written the way the app's language writes them: "1,5 MB" in German,
 * "1,5 МБ/с" in Russian, "1,5 Mo" in French, "22. Okt. 2026" rather than "Okt. 22, 2026".
 * Locale.getDefault() follows the app's own language (AppLanguage), so these follow it too. */
object LocalFormat {
    private val measureFormats = ConcurrentHashMap<Pair<Locale, Int>, MeasureFormat>()
    private val datePatterns = ConcurrentHashMap<Pair<Locale, String>, String>()

    /** [value] [unit] with exactly [decimals] decimals; per second when [perSecond]. */
    fun measure(value: Double, unit: MeasureUnit, decimals: Int, perSecond: Boolean = false): String {
        val locale = Locale.getDefault()
        val format = measureFormats.getOrPut(locale to decimals) {
            val number = NumberFormat.getInstance(locale).apply {
                minimumFractionDigits = decimals
                maximumFractionDigits = decimals
            }
            MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT, number)
        }
        val measure = Measure(value, unit)
        // Shared across threads (the download notification formats off the main thread), and
        // its number format isn't thread-safe.
        return synchronized(format) {
            if (perSecond) format.formatMeasurePerUnit(measure, MeasureUnit.SECOND, StringBuilder(), FieldPosition(0)).toString()
            else format.format(measure)
        }
    }

    /** A download speed, from MB/s: in KB/s under 1 MB/s. */
    fun speed(megabytesPerSecond: Float, decimals: Int): String =
        if (megabytesPerSecond < 1f) measure(megabytesPerSecond * 1024.0, MeasureUnit.KILOBYTE, decimals, perSecond = true)
        else measure(megabytesPerSecond.toDouble(), MeasureUnit.MEGABYTE, decimals, perSecond = true)

    /** [millis] with the fields in [skeleton] (ICU letters: "yMMMd", "MMMdjmm"…), in the order,
     * punctuation and month names the language uses. */
    fun date(millis: Long, skeleton: String): String {
        val locale = Locale.getDefault()
        val pattern = datePatterns.getOrPut(locale to skeleton) {
            android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        }
        return java.text.SimpleDateFormat(pattern, locale).format(Date(millis))
    }
}

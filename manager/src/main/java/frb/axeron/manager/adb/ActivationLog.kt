package frb.axeron.manager.adb

import android.util.Log
import frb.axeron.manager.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight activation logger. Retains [Level], [Line], and [asText] for OTG sheets while
 * avoiding unnecessary 500-line in-memory StateFlow allocations and Release logcat spam.
 */
object ActivationLog {

    private const val TAG = "ActivationLog"

    enum class Level { INFO, SUCCESS, WARN, ERROR, OUTPUT }

    data class Line(val time: Long, val level: Level, val text: String)

    fun begin(method: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "begin: $method")
    }

    fun info(text: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, text)
    }

    fun success(text: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, text)
    }

    fun warn(text: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, text)
    }

    fun error(text: String, cause: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(TAG, text, cause)
    }

    fun output(chunk: String) {
        if (BuildConfig.DEBUG && chunk.isNotBlank()) Log.v(TAG, chunk.trimEnd())
    }

    /** Plain-text copy for sharing in OTG sheets, one timestamped line per entry. */
    fun asText(lines: List<Line> = emptyList()): String {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return lines.joinToString("\n") { line ->
            "${format.format(Date(line.time))} ${line.level.name.padEnd(7)} ${line.text}"
        }
    }
}

package dev.blazelight.p4oc.core.notification

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded, in-process record of what the notification pipeline actually did. The app is often the
 * only place to observe a phone where logcat is unavailable, so Settings -> Notifications shows this
 * list: events seen, decisions taken, and notifications posted.
 */
object NotificationDiagnostics {
    private const val MAX_LINES = 40

    private const val FILE_NAME = "notification-diagnostics.log"

    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var file: File? = null

    /** Loads the trace persisted by the previous process, then appends to it from now on. */
    fun attach(context: Context) {
        synchronized(lines) {
            if (file != null) return
            val target = File(context.filesDir, FILE_NAME)
            file = target
            if (target.exists()) {
                runCatching {
                    target.readLines().takeLast(MAX_LINES).forEach(lines::addLast)
                }
            }
        }
    }

    fun record(line: String) {
        val stamped = "${formatter.format(Date())} $line"
        synchronized(lines) {
            lines.addLast(stamped)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
            file?.let { target ->
                runCatching { target.writeText(lines.joinToString("\n", postfix = "\n")) }
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            file?.let { target -> runCatching { target.writeText("") } }
        }
    }
}

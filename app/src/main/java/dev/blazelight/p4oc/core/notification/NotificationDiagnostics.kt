package dev.blazelight.p4oc.core.notification

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

    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun record(line: String) {
        val stamped = "${formatter.format(Date())} $line"
        synchronized(lines) {
            lines.addLast(stamped)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}

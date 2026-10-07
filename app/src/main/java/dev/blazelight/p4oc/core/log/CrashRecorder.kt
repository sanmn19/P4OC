package dev.blazelight.p4oc.core.log

import android.content.Context
import dev.blazelight.p4oc.BuildConfig
import java.io.File

/**
 * Debug-build-only uncaught-exception recorder: the full stack trace is written to app-private
 * storage on the phone so the crash can be inspected where logcat is unavailable (e.g. a remote
 * device testing a Taildrop APK). The next launch surfaces the stored trace once and clears it.
 */
object CrashRecorder {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        if (!BuildConfig.DEBUG) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { crashFile(context).writeText(format(thread, throwable)) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun pendingCrash(context: Context): String? =
        crashFile(context).takeIf(File::exists)?.let { file ->
            runCatching { file.readText() }.getOrNull()
        }

    fun consume(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    private fun crashFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun format(thread: Thread, throwable: Throwable): String =
        "${thread.name}:\n" + android.util.Log.getStackTraceString(throwable)
}

/*
 * Reads the most-recent persisted crash report (and, on devices that allow
 * it, a tail of logcat) and asks the AI agent to root-cause + propose a fix.
 *
 * The crash file is written by IDEApplication.persistLastCrash on the global
 * uncaught-exception handler, so even a crash that exits the process leaves
 * a forensic trail we can hand to the AI on next launch.
 */
package com.tom.rv2ide.artificial.debug

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object CrashDebugger {

  data class Report(
    val crashTrace: String,
    val logcatTail: String,
    val source: String,
  )

  private const val CRASH_DIR = "ai_crash"
  private const val CRASH_FILE = "last.txt"

  /** Path to the crash file (may not exist). */
  fun crashFile(context: Context): File =
    File(File(context.filesDir, CRASH_DIR), CRASH_FILE)

  fun hasRecentCrash(context: Context): Boolean = crashFile(context).exists()

  fun clearCrash(context: Context) {
    runCatching { crashFile(context).delete() }
  }

  /**
   * Read the most-recent crash dump and tail logcat. Both halves are
   * defensive — a missing crash file or a permission-denied logcat read
   * yields an empty string, never throws. The returned [source] string
   * tells the user (and the prompt) what data is available.
   */
  fun gather(context: Context, logcatLines: Int = 400): Report {
    val trace = runCatching {
      val f = crashFile(context)
      if (f.exists()) f.readText() else ""
    }.getOrDefault("")
    val tail = runCatching { readLogcatTail(logcatLines) }.getOrDefault("")
    val src = buildString {
      if (trace.isNotBlank()) append("crash dump")
      if (tail.isNotBlank()) {
        if (isNotEmpty()) append(" + ")
        append("logcat tail")
      }
      if (isEmpty()) append("(no crash data found)")
    }
    return Report(trace, tail, src)
  }

  /**
   * Read the last [maxLines] lines of logcat for the current process. On
   * recent Android versions third-party apps can only read their own pid's
   * log lines, but that's exactly what we want here. Returns "" if logcat
   * is unavailable.
   */
  private fun readLogcatTail(maxLines: Int): String {
    val pid = android.os.Process.myPid().toString()
    val cmd = arrayOf("logcat", "-d", "-t", maxLines.toString(), "--pid=$pid")
    val process = try { Runtime.getRuntime().exec(cmd) } catch (_: Throwable) { return "" }
    val sb = StringBuilder()
    try {
      BufferedReader(InputStreamReader(process.inputStream)).use { r ->
        var line = r.readLine()
        while (line != null) {
          sb.append(line).append('\n')
          if (sb.length > 64_000) break // safety
          line = r.readLine()
        }
      }
    } catch (_: Throwable) { return "" }
    return sb.toString()
  }

  /** Build the prompt to hand to the AI agent. */
  fun buildPrompt(report: Report): String {
    if (report.crashTrace.isBlank() && report.logcatTail.isBlank()) {
      return "I have an Android app issue but no crash dump was captured. " +
        "Please ask the user for more details to diagnose."
    }
    val sb = StringBuilder()
    sb.append("My Android app crashed. Diagnose the root cause and propose a ")
      .append("specific code fix. Focus on the *first* exception in the trace ")
      .append("(later ones may be cascading). If you need to modify files, ")
      .append("respond using the FILE_TO_MODIFY format so the agent can apply ")
      .append("the fix automatically.\n\n")
    if (report.crashTrace.isNotBlank()) {
      sb.append("=== CRASH STACK TRACE ===\n")
      sb.append(report.crashTrace.take(8_000)).append("\n\n")
    }
    if (report.logcatTail.isNotBlank()) {
      sb.append("=== LOGCAT TAIL (this process) ===\n")
      // Only the last ~6KB of logcat — most relevant context just before crash.
      val tail = report.logcatTail
      sb.append(if (tail.length > 6_000) tail.takeLast(6_000) else tail).append('\n')
    }
    return sb.toString()
  }
}

package com.example.gallerydl.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Chaquopy runs a single Python interpreter for the whole app, and gallery_dl_wrapper.py's
 * download() / list_items() configure themselves by mutating process-global state (sys.argv,
 * sys.stdout, sys.stderr) rather than anything thread-local. Two of these calls running on
 * different threads at once race on those globals — one download's output can end up routed into
 * another's callback (or dropped entirely), corrupting both downloads' progress tracking in ways
 * that don't show up as a crash. Every call into gallery_dl_wrapper must go through this lock so
 * only one is ever actually executing at a time, regardless of how many WorkManager workers are
 * running "concurrently" on the Kotlin side.
 *
 * yt_dlp_wrapper's YoutubeDL API doesn't touch that global state (options are a constructor dict,
 * not sys.argv), so it isn't vulnerable to the same bug — but it still shares this lock rather
 * than getting its own, purely as a conservative default: gallery-dl and yt-dlp both run in the
 * same interpreter, and there's no strong guarantee every third-party thing either library touches
 * internally (connection pools, etc.) is safe under genuine concurrent execution. Serializing is a
 * small cost against reintroducing the exact class of bug this lock exists to prevent. */
object PythonEngineLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

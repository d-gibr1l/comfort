package com.comfort.app.util

import android.content.Context
import java.io.File

/** Locates the qjs (QuickJS) CLI binary bundled in the APK so yt-dlp can shell out to it for
 * sites (YouTube chief among them) that now require solving a JavaScript challenge to extract
 * real download URLs — yt-dlp's own "pure-Python fallback" is deprecated for this and returns
 * no usable formats at all without a real JS runtime.
 *
 * The binary ships as jniLibs/<abi>/libqjs.so — a real, statically-linked ELF executable, just
 * given a library-shaped name so Android's own installer extracts it to nativeLibraryDir (a real,
 * exec-permitted path) at install time, the same trick used by YTDLnis (an open-source yt-dlp
 * Android app) for the same problem. It's executed directly from there rather than copied
 * anywhere else first: Android has mounted the rest of an app's private storage (files dir, cache
 * dir, etc.) noexec since Android 10 specifically to stop apps from running code fetched or
 * extracted at runtime, so nativeLibraryDir — the one location the installer is allowed to place
 * executable code in — is also the only place left that will actually let this run. */
object QuickJsRuntime {
    private const val BINARY_NAME = "libqjs.so"

    /** Path to the qjs executable, or null if it wasn't bundled for this device's ABI (shouldn't
     * happen for arm64-v8a/x86_64, the app's declared ABIs, but a missing runtime should degrade
     * to yt-dlp's own reduced-functionality fallback, not crash). */
    fun getExecutablePath(context: Context): String? {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        return binary.takeIf { it.exists() }?.absolutePath
    }
}

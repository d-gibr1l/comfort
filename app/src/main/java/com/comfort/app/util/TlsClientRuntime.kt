package com.comfort.app.util

import android.content.Context
import java.io.File

/** Locates the tls-client (github.com/bogdanfinn/tls-client) native library bundled in the APK —
 * used solely to resolve a Reddit share-link's redirect (reddit.com/r/<sub>/s/<code>) past
 * Reddit's WAF, which 403s a plain request for that same redirect (see yt_dlp_wrapper.py's own
 * _REDDIT_SHARE_LINK_RE comment for the full story). curl_cffi already does genuine TLS-fingerprint
 * impersonation for the broader "Impersonate a browser" toggle and TikTok's bot-detection bypass,
 * but it's only bundled for arm64-v8a (its cffi dependency needs a Python-version-locked native
 * `_cffi_backend` module nobody has built for armeabi-v7a/x86_64 — see PythonRuntime.kt's own
 * comments). tls-client sidesteps that entirely: it's a plain Go binary built with
 * `-buildmode=c-shared`, loaded via ctypes as a normal C-ABI shared library with zero Python
 * version coupling, so the exact same source cross-compiles cleanly for all three ABIs (built here
 * with `GOOS=android GOARCH=<abi> CGO_ENABLED=1 CC=<NDK clang for API 24>` — the same trick
 * bogdanfinn/tls-client's own build script already uses for plain Linux ARM targets, just pointed
 * at the NDK's clang instead).
 *
 * Deliberately not wired in as a general yt-dlp impersonate backend (that would need a real
 * yt_dlp.networking.common.RequestHandler implementing its full interface — streaming, cookies,
 * proxies, timeouts) — this is a narrow, self-contained fix for one specific failure, used via a
 * plain ctypes call in yt_dlp_wrapper.py's own _resolve_reddit_share_link.
 *
 * Unlike FfmpegRuntime/Aria2Runtime/PythonRuntime, this needs no `.zip.so` provisioning step at
 * all — a Go c-shared build statically links its own runtime and the whole tls-client dependency
 * tree (utls/fhttp forks, pure Go, no cgo-linked C libraries), so `libtlsclient.so`'s only NEEDED
 * entries are bionic's own liblog/libdl/libc (confirmed via `llvm-readobj -d` on each built ABI) —
 * safe to load and call directly from nativeLibraryDir the instant the APK is installed. */
object TlsClientRuntime {
    private const val LIBRARY_NAME = "libtlsclient.so"

    /** Path to the tls-client shared library, or null if it wasn't bundled for this device's ABI
     * (there is currently no such gap — arm64-v8a/armeabi-v7a/x86_64 are all covered — but callers
     * still treat a missing/failed load the same as any other yt-dlp error, never a hard crash). */
    fun getLibraryPath(context: Context): String? {
        val library = File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)
        return library.takeIf { it.exists() }?.absolutePath
    }
}

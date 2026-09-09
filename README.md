<p align="center">
  <img src="icon.svg" width="110" alt="Comfort icon">
</p>

<h1 align="center">Comfort</h1>

Comfort is a personal, free and open-source video/gallery downloader for Android 7.0 and above,
built on top of [gallery-dl](https://github.com/mikf/gallery-dl) and
[yt-dlp](https://github.com/yt-dlp/yt-dlp). Paste a link, or share one in from any app, and
download the video or gallery behind it with real quality, format, and trim control.

Package: `com.comfort.app` · minSdk 24 · targetSdk 36 · Jetpack Compose (Material 3 Expressive)

## Features

📥 **Downloading**
- Paste-a-link or Android share-sheet entry points, both landing on the same download preview
  sheet
- Automatic engine routing per URL — gallery-dl for image/gallery sites, yt-dlp for video-only
  hosts — with a same-item fallback/supplement pass between the two whenever one comes back empty
  (an Instagram carousel's embedded video that gallery-dl's own listing missed, for example)
- Share-sheet content detection: a shared link that resolves to a single video, from *any* site —
  not a fixed host list — opens the preview sheet directly; a real multi-item gallery still shows
  an item picker to choose which files to grab
- Queue with pause/resume/cancel, configurable concurrent downloads, "Start now" to jump the
  queue, and a scheduled download window (only download overnight, say)
- Resumable in-progress downloads survive process death — a killed app picks a download back up
  instead of restarting it

🎛️ **Per-download options** (the preview sheet)
- Video quality cap: Best / 1080p / 720p / 480p / Audio-only
- Clip/section trimming — multi-segment, Set Start/Set End against the real video position
- MP4 or MKV output
- Save thumbnail, embed metadata/subtitles, write description/info.json sidecar files
- Extra yt-dlp/gallery-dl CLI arguments and saved, reusable filename templates

🛡️ **Sites & reliability**
- Cookie import via an in-app login browser — logs in normally, then converts the session to a
  real Netscape `cookies.txt` automatically — with per-site management
- Bot-detection bypass (`curl_cffi` browser impersonation) for TikTok, Reddit share links, and
  others
- Proxy support, configurable network retries, and extractor arguments for site-specific
  throttling workarounds
- Sanitized error messages — a blocked request's raw HTML/CSS blob never reaches the UI as-is

🎨 **App**
- Library with search, filters, favorites, and a grid/list view toggle
- Light/dark/system theme, several named color themes, dynamic color, and a pure-black OLED option

## How it works

gallery-dl and yt-dlp run as a real standalone Python interpreter — a genuine OS subprocess per
download, not an embedded JNI interpreter — which is what makes `curl_cffi` browser impersonation
possible on Android at all. Kotlin talks to it over a small stdout line protocol
(`[title]`/`[thumbnail]`/`[progress]`/`[error]`/bare file paths), the same shape either tool prints
when run from a terminal. A bundled `ffmpeg` build handles muxing and does the post-download
stream-copy trim used by the Clip/section feature.

Room + WorkManager persist the download queue across process death; the UI is 100% Jetpack Compose
with Material 3 Expressive components.

## Building

```bash
git clone <this repo>
cd gallerydl
./gradlew :app:assembleDebug
```

Requires the Android SDK (targetSdk 36) and a JDK 17 toolchain. Debug builds install straight from
`app/build/outputs/apk/debug/`.

## Credits

- [gallery-dl](https://github.com/mikf/gallery-dl) and [yt-dlp](https://github.com/yt-dlp/yt-dlp) —
  the actual download engines this app wraps
- [YTDLnis](https://github.com/deniscerri/ytdlnis) — the subprocess-Python-on-Android architecture
  (and its published Python interpreter package) this app's own Python runtime is built on

## License

Personal project; no license has been set yet.

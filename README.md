<p align="center">
  <img src="icon.svg" width="120" alt="Comfort icon">
</p>

<h1 align="center">Comfort</h1>

Comfort is a personal, free and open-source video/gallery/music downloader for Android 7.0 and
above, built on top of [gallery-dl](https://github.com/mikf/gallery-dl) and
[yt-dlp](https://github.com/yt-dlp/yt-dlp), with added Spotify link support. Paste a link, or
share one in from any app, and download the video, gallery, or song behind it with real quality,
format, and trim control.

Package: `com.comfort.app` · minSdk 24 · targetSdk 36 · Jetpack Compose (Material 3 Expressive)

## Features

📥 **Downloading**
- Paste-a-link or Android share-sheet entry points, both landing on the same download preview
  sheet
- Automatic engine routing per URL — gallery-dl for image/gallery sites, yt-dlp for video-only
  hosts — with a same-item fallback/supplement pass between the two whenever one comes back empty
  (an Instagram carousel's embedded video that gallery-dl's own listing missed, for example). A
  live, no-network probe against the actually-bundled engines skips that fallback entirely when a
  link is exclusive to one engine, instead of always attempting both
- Share-sheet content detection: a shared link that resolves to a single video, from *any* site —
  not a fixed host list — opens the preview sheet directly; a real multi-item gallery still shows
  an item picker to choose which files to grab
- Queue with pause/resume/cancel, configurable concurrent downloads, "Start now" to jump the
  queue, and a scheduled download window (only download overnight, say)
- Resumable in-progress downloads survive process death — a killed app picks a download back up
  instead of restarting it
- gallery-dl and yt-dlp update independently of the app itself, on a Stable or Bleeding Edge
  channel per engine, from Settings — no need to wait for a new app release to pick up a
  site-extractor fix

🎵 **Music**
- Spotify track/album/playlist links — no API key or login needed. Scrapes Spotify's own public
  embed pages for real title/artist/album/cover art, matches the track on YouTube, and tags the
  downloaded audio with Spotify's metadata instead of whatever the matched video reports
- Song-aware preview sheet for Spotify/YouTube Music/SoundCloud links (and any link where you pick
  "Audio" quality): square cover art, and an editable title/artist pre-filled with upload clutter
  ("Official Video", "Lyrics", a channel's own auto-added "- Topic" suffix, ...) already stripped.
  An album/playlist link shows every track with its own checkbox instead of downloading blind
- Real artist/album metadata is captured into the app's own library (not just embedded in the
  file) — Library has a dedicated Audio filter, and a track's card shows "Artist — Album" instead
  of just the source domain
- A YouTube Music download's cover art is looked up against iTunes' public catalog and swapped in
  for YouTube's own 16:9 video-frame thumbnail, since embedding a real square image locally isn't
  possible on this stripped-down Android build (no image codec)

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

gallery-dl and yt-dlp (plus a small Spotify wrapper that scrapes Spotify's public embed pages, then
delegates the actual audio download to yt-dlp) run as a real standalone Python interpreter — a
genuine OS subprocess per download, not an embedded JNI interpreter — which is what makes
`curl_cffi` browser impersonation possible on Android at all. Kotlin talks to it over a small
stdout line protocol (`[title]`/`[thumbnail]`/`[progress]`/`[error]`/bare file paths), the same
shape either tool prints when run from a terminal. A bundled `ffmpeg` build handles muxing and
does the post-download stream-copy trim used by the Clip/section feature.

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

Ships as per-ABI APKs for `arm64-v8a` and `armeabi-v7a` — the latter kept specifically for real
32-bit-only budget devices, not just emulators. `x86_64` isn't built; it's essentially never seen
outside an emulator and would otherwise triple the release-matrix size for no real-device benefit.

## Credits

- [gallery-dl](https://github.com/mikf/gallery-dl) and [yt-dlp](https://github.com/yt-dlp/yt-dlp) —
  the actual download engines this app wraps
- [YTDLnis](https://github.com/deniscerri/ytdlnis) — the subprocess-Python-on-Android architecture
  (and its published Python interpreter package) this app's own Python runtime is built on

## License

Personal project; no license has been set yet.

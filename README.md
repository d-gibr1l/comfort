<p align="center">
  <img src="icon.svg" width="120" alt="Comfort icon">
</p>

<h1 align="center">Comfort</h1>

Comfort is a personal, free and open-source video/gallery/music downloader for Android 7.0 and
above, built on top of [gallery-dl](https://github.com/mikf/gallery-dl),
[yt-dlp](https://github.com/yt-dlp/yt-dlp) and [Instaloader](https://github.com/instaloader/instaloader),
with added Spotify link support. Paste a link, or share one in from any app, and download the
video, gallery, or song behind it with real quality, format, and trim control.

Package: `com.comfort.app` · minSdk 24 · targetSdk 36 · Jetpack Compose (Material 3 Expressive)

Download the APK from [Releases](https://github.com/d-gibr1l/comfort/releases/latest), or get updates
straight from them with Obtainium:

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/d-gibr1l/comfort"><img src="docs/badge_obtainium.png" alt="Get it on Obtainium" width="161"></a>

<p align="center">
  <img src="docs/screenshots/home.png" width="200" alt="Home">&nbsp;
  <img src="docs/screenshots/preview_video.png" width="200" alt="Video preview sheet">&nbsp;
  <img src="docs/screenshots/preview_song.png" width="200" alt="Song preview sheet">&nbsp;
  <img src="docs/screenshots/queue.png" width="200" alt="Download queue">
</p>

## See it work

<table>
  <tr>
    <td align="center"><img src="docs/media/instagram_carousel.gif" width="260" alt="Sharing an Instagram carousel into Comfort"></td>
    <td align="center"><img src="docs/media/spotify_song.gif" width="260" alt="Sharing a Spotify song into Comfort"></td>
  </tr>
  <tr>
    <td align="center">Share an Instagram carousel, pick the items, download</td>
    <td align="center">Share a Spotify song, check the details, download</td>
  </tr>
</table>

## Features

📥 **Downloading**
- Paste a link on Home, or share one in from any app. **Configure** opens the preview sheet,
  **Instant** starts downloading right away, and the Sharing mode setting picks one for you or
  asks each time
- Search YouTube right from Home's link field, and download from the results
- Automatic engine routing per URL — gallery-dl for image/gallery sites, yt-dlp for video hosts,
  Instaloader for Instagram — with a same-item fallback/supplement pass between them whenever one
  comes back empty (an Instagram carousel's embedded video that gallery-dl's own listing missed,
  for example). A live, no-network probe against the bundled engines skips that fallback when a
  link is exclusive to one engine, instead of always attempting both
- Content detection: a link that resolves to video (from *any* site, not a fixed host list) or a
  song opens the preview sheet; a real multi-item gallery shows an item picker to choose which
  files to grab
- Queue with pause/resume/cancel, configurable concurrent downloads, **Start now** to start a
  download at once (past the window and the line), and a scheduled download window (only download
  overnight, say) that starts everything waiting the moment it opens
- **Start at…**: give any download its own start time — from the preview sheet, a queued or paused
  card, or a running one (which pauses until then)
- Select many downloads at once to retry, resume, pause, cancel or delete them together, or Retry
  All on the Errored tab — a hundred cards move in one go
- A download that saved some of its items and not others says so, and can fetch just the rest
- Live speed and progress, a stall watchdog, and automatic retries on flaky connections; a dropped
  connection is ridden out for a few minutes, then the download waits for the network and carries
  on where it stopped
- Resumable in-progress downloads survive process death — a killed app picks a download back up
  instead of restarting it
- **Subscriptions**: subscribe to a channel or playlist from the download sheet, and its new uploads
  download on their own (Settings › Subscriptions)
- yt-dlp, gallery-dl and Instaloader update independently of the app itself — automatically by
  default, on a Stable or Bleeding Edge channel per engine (Settings › Updates and engines) — no need
  to wait for a new app release to pick up a site-extractor fix

🎵 **Music**
- Spotify track/album/playlist links — no API key or login needed. Scrapes Spotify's own public
  embed pages for real title/artist/album/cover art, matches the track on YouTube, and tags the
  downloaded audio with Spotify's metadata instead of whatever the matched video reports
- A song preview for Spotify/YouTube Music/SoundCloud links (and any link where you pick "Audio"
  quality): cover art, duration, and an editable title/artist pre-filled with upload clutter
  ("Official Video", "Lyrics", a channel's own auto-added "- Topic" suffix, ...) already stripped.
  An album/playlist link shows the album and every track with its own checkbox instead of
  downloading blind
- Real artist/album metadata is captured into the app's own library (not just embedded in the
  file) — Library has a dedicated Audio filter, and a track's card shows "Artist — Album" instead
  of just the source domain
- A YouTube Music download's cover art is cropped square from its video thumbnail, not left 16:9
- Synced lyrics from [LRCLIB](https://lrclib.net) saved inside each song
- Save songs as MP3, M4A, Opus or FLAC (or the site's original audio), with an option to level
  every song to the same loudness

▶️ **Viewing and playing**
- A built-in viewer for pictures and videos: swipe through the Library, see a photo's details,
  share, or open in another app
- A full music player, with Auxio's playback engine and look: a queue, shuffle, Now Playing with
  synced lyrics, and a video with sound pauses the music, which plays on after. Controls on the lock
  screen, from headphones and Bluetooth, and a home-screen widget

🎛️ **Per-download options** (the preview sheet)
- Video quality: Best / 4K / 1440p / 1080p / 720p / 480p / Audio-only, showing the size of the
  quality you pick before you download
- Clip/section trimming — multi-segment, Set Start/Set End against the real video position
- MP4 or MKV output; MP4 converts VP9/AV1-only videos to H.264 on the phone's hardware encoder
- Audio language for videos with dubs (the original marked), and subtitles in your language and the
  original by default — per download, or as a default in Settings › Processing
- SponsorBlock (YouTube): mark sponsored segments as chapters, or cut them out
- Split a video with chapters into one file per chapter
- Save thumbnail, embed metadata/subtitles, write description/info.json sidecar files
- Extra command-line arguments — one set for both engines plus separate yt-dlp and gallery-dl sets,
  with a switch to turn them all off — saved command templates, and one filename format for every
  engine

🛡️ **Sites & reliability**
- Cookie import via an in-app login browser — logs in normally, then converts the session to a
  real Netscape `cookies.txt` automatically — with per-site management
- Bot-detection bypass (`curl_cffi` browser impersonation) for TikTok, Reddit share links, and
  others
- Proxy support (HTTP or SOCKS) that every engine's requests go through, configurable network
  retries, and extractor arguments for site-specific throttling workarounds
- File names that fit: a long caption in Japanese, Arabic or emoji is shortened to what Android
  allows instead of failing the download
- Readable errors — each engine's own error for a failed download, and a blocked request's raw
  HTML/CSS blob never reaches the UI as-is

🎨 **App**
- Library with search, filters, favorites, duplicates, and a grid/list view toggle; swipe a card
  to remove it
- Swipe sideways between Home, Library and Settings, and between the Queue's tabs
- Backup & restore of your settings, command templates, Library history and logins
- Light/dark/system theme, several named color themes, dynamic color, and a pure-black OLED option
- 16 languages — English, Spanish, Portuguese (Brazil), German, French, Italian, Russian, Turkish,
  Indonesian, Vietnamese, Hindi, Japanese, Chinese (Simplified), and right-to-left Arabic, Hebrew and
  Persian — picked in Settings › Appearance › Language

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/share_sheet.png" width="200" alt="Share sheet"><br>Configure or Instant</td>
    <td align="center"><img src="docs/screenshots/picker.png" width="200" alt="Gallery picker"><br>Gallery picker</td>
    <td align="center"><img src="docs/screenshots/preview_playlist.png" width="200" alt="Playlist preview"><br>Playlist</td>
    <td align="center"><img src="docs/screenshots/library.png" width="200" alt="Library"><br>Library</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/appearance_light.png" width="200" alt="Appearance, light"><br>Appearance</td>
    <td align="center"><img src="docs/screenshots/appearance_dark.png" width="200" alt="Appearance, dark"><br>Pure black</td>
    <td align="center"><img src="docs/screenshots/settings.png" width="200" alt="Settings"><br>Settings</td>
    <td align="center"><img src="docs/screenshots/settings_downloads.png" width="200" alt="Download settings"><br>Downloads</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/settings_processing.png" width="200" alt="Processing settings"><br>Processing</td>
    <td align="center"><img src="docs/screenshots/settings_folders.png" width="200" alt="Folder settings"><br>Folders</td>
    <td align="center"><img src="docs/screenshots/settings_advanced.png" width="200" alt="Advanced settings"><br>Advanced</td>
    <td align="center"><img src="docs/screenshots/settings_about.png" width="200" alt="About"><br>About</td>
  </tr>
</table>

## How it works

gallery-dl, yt-dlp and Instaloader (plus a small Spotify wrapper that scrapes Spotify's public
embed pages, then delegates the actual audio download to yt-dlp) run in a real standalone Python
interpreter — a genuine OS process per download, not an embedded JNI interpreter — which is what
makes `curl_cffi` browser impersonation possible on Android at all. A small fork server keeps the
engines pre-loaded so each download starts without paying Python's import cost again. The wrappers
report back to Kotlin as structured JSON lines on stdout (title, size, progress, each saved file,
errors), next to the engines' own log output. A bundled `ffmpeg` build handles muxing,
exact trims, audio conversion (MP3/M4A/Opus/FLAC), volume levelling and MP4 conversion.

Room + WorkManager persist the download queue across process death; the UI is 100% Jetpack Compose
with Material 3 Expressive components.

## Building

```bash
git clone https://github.com/d-gibr1l/comfort.git
cd comfort
./gradlew :app:assembleDebug
```

Requires the Android SDK (targetSdk 36) and a JDK 17 toolchain. Debug builds install straight from
`app/build/outputs/apk/debug/`.

Checks — the same ones CI runs on every push (`.github/workflows/build.yml`):

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug           # Android Lint; any error fails (settings in app/lint.xml)
python tools/test_wrapper_resume.py   # resuming and conversion through yt_dlp_wrapper.py (needs yt-dlp and ffmpeg)
```

The bundled Python runtime (`app/src/main/jniLibs/*/libpython.zip.so`, from ytdlnis-packages) is
trimmed by `tools/trim_runtime_zips.py`; run it again after replacing those zips.

Ships as per-ABI APKs for `arm64-v8a`, `armeabi-v7a` (kept for real 32-bit-only budget devices,
not just emulators) and `x86_64`, plus a universal APK with all three.

## Credits

- [gallery-dl](https://github.com/mikf/gallery-dl) (GPLv2), [yt-dlp](https://github.com/yt-dlp/yt-dlp)
  (Unlicense) and [Instaloader](https://github.com/instaloader/instaloader) (MIT) — the actual
  download engines this app wraps
- [YTDLnis](https://github.com/deniscerri/ytdlnis) / its
  [ytdlnis-packages](https://github.com/deniscerri/ytdlnis-packages) repo (GPLv3) — the
  subprocess-Python-on-Android architecture this app's own Python runtime is built on, and the
  actual prebuilt Python/QuickJS binaries bundled directly into this app
- [FFmpeg](https://ffmpeg.org) (LGPLv2.1+) 7.1, built for this app with
  [LAME](https://lame.sourceforge.io) (LGPLv2) for MP3 and [Opus](https://opus-codec.org) (BSD) —
  merging, converting, trimming and re-encoding (H.264 on the phone's own hardware encoder)
- [aria2](https://github.com/aria2/aria2) (GPLv2+) — multi-connection downloads
- [QuickJS](https://github.com/bellard/quickjs) (MIT) — JS-challenge solving for sites that require it
- [yt-dlp-ejs](https://github.com/yt-dlp/ejs) (Unlicense, with MIT/ISC parts) — the scripts yt-dlp
  runs in QuickJS to solve YouTube's challenges
- [curl_cffi](https://github.com/lexiforest/curl_cffi) (MIT) — browser impersonation for sites that
  block scripted requests
- [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K) (Apache-2.0) — the Appearance
  screen's theme-picker layout (light/dark preview card rows) is ported from its own Settings UI
- [Auxio](https://github.com/OxygenCobalt/Auxio) (GPLv3) — the music player's playback engine
  and look
- [Photon Gallery](https://github.com/Bn5prS/Photon_Gallery) (GPLv3) — the built-in picture and
  video viewer
- [LRCLIB](https://lrclib.net) — synced lyrics

## License

GPLv3 — see [`LICENSE`](LICENSE). Required by the GPL-licensed components this app bundles
directly (gallery-dl, aria2, the ytdlnis-packages Python/QuickJS binaries, and the code ported
from Auxio and Photon Gallery) — bundling
GPL code into a distributed app means the combined work has to be GPL-compatible too, with source
available to anyone who gets the APK (this repo). The permissively-licensed pieces (yt-dlp,
Instaloader, QuickJS, yt-dlp-ejs, curl_cffi, the ported TachiyomiJ2K UI) are all GPL-compatible on their own terms; see
Credits above for each one's actual license.

# Comfort - To-Do List

## Features & Enhancements
- [x] **Instagram File Sizes:** *(done in a914351 — sizes come from the media servers in parallel; shown on the picker/preview Download buttons, track rows and the single-item card, and Instaloader downloads report their total up front)* Add HTTP HEAD requests to instaloader_wrapper.py and gallery_dl_wrapper.py to fetch Content-Length of media files prior to downloading. This will allow the app to display file sizes for Instagram/Reddit links on the preview card and during the active download progress bar.

## Technical Debt & Refactoring
- [x] **Componentize Monoliths:** *(done in e5a97ec — MoreScreen.kt split into SettingsScreen, SettingsComponents and one file per Settings page)* Break up massive files, specifically MoreScreen.kt (4,350+ lines) into separate feature screens (e.g., SettingsScreen.kt, AboutScreen.kt, EnginesScreen.kt) and move reusable UI components into Components.kt.
- [x] **Adopt MVVM Architecture:** *(done in c9e4bf6 and the commit after it — UpdatesViewModel (all update state, replaces the global update flags), PreviewSheetViewModel and SharePickerViewModel (each sheet's listing/selection/duplicate check, scoped per opening), LinkRouterViewModel (Home's paste flow and the share sheet: which sheet a link gets, and every enqueue), CookiesViewModel over a testable CookieStore (the cookies.txt format rules). Queue/Library already went through DownloadsViewModel; their copy-pasted undo-delete is one helper now. Left as-is on purpose: Settings pages whose state is one-to-one preference toggles)* Migrate business logic and complex state management out of Compose `remember`/`LaunchedEffect` blocks and into properly scoped ViewModels using StateFlow.
- [x] **Structured Inter-process Communication:** *(done in 96fe584 — JSON report lines with a 0x1E marker on the existing pipe (comfort_events.py), read by EngineEventParser into typed EngineEvents; a socket wasn't needed)* Replace the brittle Regex-based stdout string parsing in DownloadWorker.kt and PythonRuntime.kt with a structured data format (e.g., JSON over a local socket) for reliable communication with Python engines.
- [x] **Abstract Engine Fallbacks:** *(done in 96fe584 — EnginePlan strategies (Single / FallbackIfNothingSaved / GalleryDlFirst) + EngineCommands per engine, unit-tested)* Refactor DownloadWorker.kt to use the Strategy Pattern for download engines, replacing the deeply nested if/else fallback chains with a unified, clean interface for yt-dlp, gallery-dl, and Instaloader.

## Languages (after the app's text is in strings.xml)
- [ ] **Language picker:** declare the app's languages (`locales_config.xml`, so Android 13+ lists them under the app's system settings) and add a picker in Settings > Appearance for older versions (AppCompatDelegate.setApplicationLocales).
- [ ] **Translations:** first pass for Spanish, Portuguese (Brazil), German, French, Russian, Indonesian and Arabic, checked on the phone; later maybe Weblate/Crowdin for volunteers once the public repo has users.
- [ ] **Right-to-left (Arabic, Hebrew, Persian):** check the swipe directions (song skip, page swipe, swipe to delete), the queue's drag handle, and any left/right that should be start/end.
- [ ] **Fonts:** the bundled Google Sans is a Latin-only subset; titles and lyrics in Cyrillic, Greek, Arabic, Hindi or CJK fall back to the system font. Bundle the full font (~5 MB) or a subset per script if that looks wrong.
- [ ] **Longer text:** German and Russian run 30-40% longer; check the fixed-size chips and buttons (Configure/Download, format and quality chips, the nav pill, the share sheet) for cut-off text.
- [ ] **Dates and numbers:** go through the ~55 date/number formatting calls (Library dates, file sizes, speeds) so they follow the user's language, not English formats.
- [ ] **Engine messages:** warnings and errors the Python wrappers write themselves (not yt-dlp's or gallery-dl's own) are English; give them codes the app turns into translated text.
- Stays English on purpose: yt-dlp's and gallery-dl's own error text, command-line flags, site names, the COMFORT wordmark.

## Content languages (audio tracks, subtitles, lyrics)
- [ ] **Audio language picker:** the preview sheet lists a video's audio languages (Original + each dub, from the extraction it already does) and picks one; Settings gets "Preferred audio language" (Original by default, or the phone's language, or a set one). Songs and Spotify stay on the original. Today yt-dlp's ranking just takes the original.
- [ ] **Subtitles:** default to the phone's language plus the video's original instead of always `en`; the preview sheet shows which languages exist (uploaded vs auto-generated) to pick from; an option for auto-generated captions and YouTube's auto-translation when nothing was uploaded (`writeautomaticsub` is never set today).
- [ ] **Track switching in the viewer:** for files with several audio or subtitle tracks, the video viewer's ⋮ menu picks the audio track and shows subtitles (Media3 supports it; not wired up).
- [ ] **Lyrics language tag:** `lyrics.py` writes every MP3's USLT frame as `lang="eng"`; use `und` (or a detected language). Translated/romanised lyrics would need another source than LRCLIB (left out for now).

## Other open items
- [x] **Bundle yt-dlp-ejs 0.8.0** *(done in be583b3)* (works with QuickJS): without it YouTube's JS challenge is skipped ("[jsc] ... was skipped"), giving fewer formats or slow extraction.
- [ ] **Channel/playlist subscriptions:** watch a source and download new uploads (YTDLnis's "Observe sources").
- [x] **Test SponsorBlock and split-by-chapters on the phone** *(done in 5eb704c — both failed there without ffprobe, and Opus splits on the cover picture; fixed with an ffmpeg-based probe and an audio-only split that copies the cover onto each part)* (only tested on the PC with the same wrapper), including how a split download shows in the Library.
- [ ] **Unverified:** the Library Undo red-flash fix, the home-screen music widget, resuming from headphones/Bluetooth.
- [ ] **Lyrics from `.lrc` files:** songs whose lyrics only exist as a sidecar `.lrc` (the `.webm` Spotify downloads that can't hold tags) don't show lyrics in Now Playing.
- [ ] **Pinch-to-zoom lyrics:** couldn't be tested over adb (no two-finger input); check by hand.

# Comfort - To-Do List

## Features & Enhancements
- [x] **Instagram File Sizes:** *(done in a914351 — sizes come from the media servers in parallel; shown on the picker/preview Download buttons, track rows and the single-item card, and Instaloader downloads report their total up front)* Add HTTP HEAD requests to instaloader_wrapper.py and gallery_dl_wrapper.py to fetch Content-Length of media files prior to downloading. This will allow the app to display file sizes for Instagram/Reddit links on the preview card and during the active download progress bar.

## Technical Debt & Refactoring
- [ ] **Componentize Monoliths:** Break up massive files, specifically MoreScreen.kt (4,350+ lines) into separate feature screens (e.g., SettingsScreen.kt, AboutScreen.kt, EnginesScreen.kt) and move reusable UI components into Components.kt.
- [ ] **Adopt MVVM Architecture:** Migrate business logic and complex state management out of Compose `remember`/`LaunchedEffect` blocks and into properly scoped ViewModels using StateFlow.
- [ ] **Structured Inter-process Communication:** Replace the brittle Regex-based stdout string parsing in DownloadWorker.kt and PythonRuntime.kt with a structured data format (e.g., JSON over a local socket) for reliable communication with Python engines.
- [ ] **Abstract Engine Fallbacks:** Refactor DownloadWorker.kt to use the Strategy Pattern for download engines, replacing the deeply nested if/else fallback chains with a unified, clean interface for yt-dlp, gallery-dl, and Instaloader.

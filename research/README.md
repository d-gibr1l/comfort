# Research & Code Reviews Directory

This directory contains in-depth architectural audits, vulnerability investigations, and production code reviews for the Comfort (`gallerydl`) Android application.

---

## Index of Reviews

### 1. [Download Queue Lifecycle & State-Machine Audit](queue-lifecycle-code-review.md)
* **File:** [`queue-lifecycle-code-review.md`](queue-lifecycle-code-review.md) *(Version 2.0.0-PROD, 1,272 lines, 80 KB)*
* **Scope:** Deep-dive audit across `DownloadDispatcher.kt`, `DownloadWorker.kt`, `DownloadDao.kt`, `QueueViewModel.kt`, and `QueueScreen.kt`.
* **Key Topics:**
  - **Root Cause Analysis:** Diagnoses why downloads unexpectedly linger in the "In Queue" tab with 0 active workers (System-Stop `Result.success()` terminal trap, uncaught cancellations at `DownloadConcurrencyGate.acquire()`, and cold-start orphan detection failures).
  - **State Machines:** Audits `RUNNING`, `QUEUED`, `PAUSED`, `SCHEDULED`, `ERRORED`, and `CANCELLED` transitions.
  - **Boundary Conditions:** Schedule window closures, night-time restriction leaks, and staging deletion races during cancellation.
  - **Production Patches:** Concrete, drop-in Kotlin patches for `DownloadWorker.kt`, `DownloadDispatcher.kt`, `DownloadsViewModel.kt`, and `QueueScreen.kt`.

---

### 2. [Instaloader Pipeline & Subsystem Integration](instaloader-pipeline-code-review.md)
* **File:** [`instaloader-pipeline-code-review.md`](instaloader-pipeline-code-review.md) *(16 KB)*
* **Scope:** Audit of Instagram download engine, metadata extraction, and session management in `instaloader_wrapper.py`, `net_resilience.py`, and `DownloadWorker.kt`.
* **Key Topics:**
  - **Metadata Retrieval vs Immediate Download:** Explains why Instaloader must execute GraphQL queries before downloading.
  - **Proxy Bypass:** Resolves real IP leakage across GraphQL calls (`copy_session()`), media fetching (`get_anonymous_session()`), and file-size probes (`net_resilience.content_lengths()`).
  - **Partial Carousel Completion:** Fixes the bug where saving 1 out of 10 items returns `"ok"` and marks incomplete downloads as `FINISHED`.
  - **Latency & Cookie Fixes:** Disables artificial 15s `sleep=True` delay, and fixes case-sensitive `#HttpOnly_` parsing that stripped valid session cookies.

---

### 4. [Library Page Scrolling Stutter Investigation](library-scrolling-stutter-review.md)
* **File:** [`library-scrolling-stutter-review.md`](library-scrolling-stutter-review.md)
* **Scope:** Performance audit of `DownloadsHistoryScreen.kt`, `LibraryItems.kt`, `LibraryHeader.kt`, `SwipeToDelete.kt`, and `DownloadsViewModel.kt` targeting reported animation stuttering during scrolling on the Library page.
* **Key Findings:**
  - **`mediaShapes` unthrottled:** `combine(rawHistoryFlow, rawDeletedFlow)` without `.throttleLatest()` causes excess emissions → triggers both main-thread filter+sort and O(N) `justifiedCells` recomputation on every shape resolution.
  - **O(N) `justifiedCells` on composition thread:** Grid layout recomputed synchronously for every `shapes` Map emission, even when only one shape changed.
  - **`visibleItems` filter+sort on main thread:** `.filter().sortedByDescending()` runs during composition on each `historyFlow` emission.
  - **`MarkShownAfterFirstFrame` restarts on emissions:** Resets the 2-frame window that guards entrance animations, risking blank frames during active-download scrolling.
  - **`selectionMode` swap causes full item subtree recompose:** `if (selectionMode) row() else SwipeToDeleteCard { row() }` can be replaced with `enabled = !selectionMode`.
  - **`SwipeToDeleteCard` overdraw:** Confirmed fixed (gated on `dismissDirection != Settled`).

---

### 3. [Swipe-to-Delete Cards Review](swipe-to-delete-cards-review.md)
* **File:** [`swipe-to-delete-cards-review.md`](swipe-to-delete-cards-review.md) *(8.3 KB)*
* **Scope:** Audit of `SwipeToDeleteCard` in `DownloadsHistoryScreen.kt`, `QueueScreen.kt`, and gesture interaction with `PageSwipe.kt`.
* **Key Topics:**
  - **Permanent Overdraw:** Eliminates red delete box rendering continuously behind resting cards.
  - **Touch Zone Conflict:** Calibrates `PageSwipe` edge exclusion from 80dp down to 48dp so cards can be swiped without accidentally changing tabs.
  - **Tree Churn:** Replaces conditional `if (selectionMode) row() else SwipeToDeleteCard { row() }` branching with `gesturesEnabled = !selectionMode`.
  - **Accessibility:** Adds `CustomAccessibilityAction("Remove")` for TalkBack users.

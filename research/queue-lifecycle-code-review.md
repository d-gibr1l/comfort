# Comprehensive Code Review & State-Machine Audit: Download Queue Lifecycle

**Document Version:** 2.0.0-PROD (Remediated & Hardened)  
**Date:** 2026-10-21  
**Author:** Download Queue Lifecycle Audit & Hardening Team  
**Scope:** Architecture, State Machine, Concurrency Gate, Subprocess Execution, and Presentation Layer Audit of the Comfort (`gallerydl`) Android Application  
**Target Files Audited:**
- `app/src/main/java/com/comfort/app/data/DownloadDispatcher.kt`
- `app/src/main/java/com/comfort/app/worker/DownloadWorker.kt`
- `app/src/main/java/com/comfort/app/data/DownloadDao.kt`
- `app/src/main/java/com/comfort/app/data/DownloadEntity.kt`
- `app/src/main/java/com/comfort/app/viewmodel/DownloadsViewModel.kt`
- `app/src/main/java/com/comfort/app/ui/main/QueueScreen.kt`
- `app/src/main/java/com/comfort/app/worker/ScheduleAlarmReceiver.kt`
- `app/src/main/java/com/comfort/app/util/PythonRuntime.kt`
- `app/src/main/java/com/comfort/app/viewmodel/DownloadsSettingsViewModel.kt`
- `app/src/main/java/com/comfort/app/ui/main/DownloadsSettings.kt`
- `app/src/main/assets/python_packages/py_server.py`

---

## Executive Summary

An exhaustive lifecycle audit of the download subsystem was conducted to diagnose why items unexpectedly linger indefinitely in the `DownloadStatus.QUEUED` state (displaying in the "In Queue" tab as *"Waiting to start…"*) even when zero downloads are actively running, and to identify edge cases, race conditions, and synchronization failures across **Room DB**, **Android WorkManager**, the **in-memory Concurrency Gate**, the **Python/native subprocess execution layer**, and the **Jetpack Compose UI**.

The audit uncovered **nine interrelated critical architectural vulnerabilities**:

1. **The System-Stop / `Result.success()` Terminal Trap (`DownloadWorker.kt:429, 1027, 1094`):** When the Android OS terminates an active worker (via network constraint loss, Doze, battery optimization, or execution time limits), `requeueIfStillRunning()` correctly restores the Room DB row to `QUEUED`. However, `doWork()` then returns `Result.success()`. In WorkManager, `Result.success()` permanently marks the `OneTimeWorkRequest` as `SUCCEEDED` (`isFinished = true`) and destroys it. Because WorkManager considers the job completed, no replacement work request is ever enqueued. The download remains stranded in `QUEUED` in Room DB with 0 active workers and no trigger to resume.
2. **Uncaught Cancellation of Waiting Workers at `DownloadConcurrencyGate` (`DownloadWorker.kt:289-294`):** Under the one-job-per-download architecture, every queued download is immediately submitted to WorkManager as an independent `OneTimeWorkRequest`. Workers exceeding the concurrency limit block inside `DownloadConcurrencyGate.acquire()`. If Android cancels a sleeping worker due to background execution constraints, `CancellationException` is thrown *outside* the inner IO `try-catch` block. The exception escapes `doWork()`, causing WorkManager to mark the job `CANCELLED`, while the Room DB row remains permanently in `QUEUED` with no error, no notification, and no re-enqueue trigger. Furthermore, if a worker wakes up after window closure, an early return without releasing the concurrency gate permanently deadlocks all subsequent queue workers.
3. **Inversion of Cancellation and Status Updates (`DownloadDispatcher.kt:396, 423`):** In both `pauseDownload()` and `cancelDownload()`, `cancelWorkManagerJob()` was invoked *before* updating the database status via `updateStatusIfIn()`. `cancelWorkManagerJob()` blocks synchronously on `operation.result.get(5, TimeUnit.SECONDS)`. During this 5-second window, the worker coroutine receives `CancellationException` and executes `requeueIfStillRunning()`. Because the database row was still in `RUNNING`, `requeueIfStillRunning()` overwrote the row to `QUEUED`. The subsequent `updateStatusIfIn()` could fail or cause transient UI status flicker, and subsequent queue repair resurrected paused/cancelled downloads.
4. **Cold-Start Orphan Detection Flaw (`DownloadDispatcher.kt:503`):** On application cold start, `repairIfJobDead()` inspects WorkManager using `getWorkInfoById()`. Because WorkManager's persisted SQLite state for interrupted jobs often remains `State.ENQUEUED` or `State.RUNNING` (not yet transitioned to a terminal `isFinished` state), `repairIfJobDead()` assumes the job is alive and skips it, stranding orphaned rows forever. Any row in `RUNNING` at cold start is guaranteed to be an orphan from a terminated OS process and must be unconditionally re-enqueued.
5. **Absence of Reactive Queue Coordination on Completion:** When a download finishes or errors out, `DownloadWorker` releases its slot via `DownloadConcurrencyGate.release()`. This only signals an in-memory coroutine flow (`slotFreed`), waking workers already suspended in memory. If waiting workers died, were evicted, or were never started by WorkManager, zero proactive dispatch occurred. Proactively invoking `DownloadDispatcher.requestQueueRepair(applicationContext)` on exit resolves this stall.
6. **Time-of-Check to Time-of-Use (TOCTOU) Worker Startup & Teardown Races (`DownloadWorker.kt:317-322, 1041, 1073, 1097`):** `DownloadWorker` inspected the DB status at line 317, but at line 322 executed an unconditional `dao.updateStatus(downloadId, RUNNING)`. If a user paused or cancelled while the worker was entering execution, line 322 overwrote `PAUSED` or `CANCELLED` back to `RUNNING`. Similarly, terminal writes at completion or error used unconditional `dao.updateError()`, overwriting concurrent user pauses or cancellations.
7. **Dual Unique Work Naming Collision on Fast-Track Start (`DownloadDispatcher.kt:352`):** `startNow()` enqueued jobs under the unique name `"gallery_dl_now_$id"`, whereas normal jobs used `"gallery_dl_download_$id"`. Because `ExistingWorkPolicy.REPLACE` only replaces jobs sharing the identical unique name, two workers ran concurrently for the exact same download ID, racing on disk writes and MediaStore insertions.
8. **Schedule Window Boundary Closure Leak & Second Truncation (`DownloadDispatcher.kt:660, 703`):** `scheduleWindowAlarm` armed an alarm only when waiting for the window to open, never for window closure. When scheduling was unrestricted (`startMin == endMin`), it armed a phantom 24-hour RTC_WAKEUP alarm. Active running downloads were ignored when the window closed. Furthermore, workers waking after window closure had no pre-execution schedule check upon acquiring the concurrency gate, causing queued items to churn through `RUNNING` outside permitted hours.
9. **Subprocess Process Group Leaks & Staging Deletion Races (`PythonRuntime.kt:376, 569`, `DownloadDispatcher.kt:436`):** In fork server mode (`py_server.py`), `os.setpgid(0, 0)` allows atomic process-group killing (`Os.kill(-pid, SIGKILL)`). In direct execution mode (`runDirect`), Java's `ProcessBuilder` does not create a process group; child processes (`ffmpeg`, `aria2c`) become orphaned if killed directly. Concurrently, `cancelDownload()` deleted the staging directory immediately on the dispatcher thread while `DownloadWorker`'s `NonCancellable` `saveHeldFiles()` block was actively reading and flushing files to MediaStore, producing `FileNotFoundException` crashes and corrupted 0-byte media.

---

## Architectural & Queue Model Overview

### System Architecture Layers

The download subsystem is structured across five decoupled layers:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      1. UI & Presentation Layer                         │
│   QueueScreen.kt  ───  DownloadsViewModel.kt  ───  QueueItemCard.kt    │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ Flow / StateFlow
┌────────────────────────────────────▼────────────────────────────────────┐
│                    2. Persistent State Store (Room DB)                  │
│       AppDatabase.kt  ───  DownloadDao.kt  ───  DownloadEntity.kt       │
│        (Single Source of Truth: RUNNING, QUEUED, PAUSED, etc.)          │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ Observers / Atomic CAS
┌────────────────────────────────────▼────────────────────────────────────┐
│                  3. Work Dispatcher & Concurrency Layer                 │
│ DownloadDispatcher.kt  ───  DownloadWorker.kt  ─── ConcurrencyGate     │
│   (WorkManager OneTimeWorkRequest, In-memory Slot Management)           │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ IPC / Subprocess Control
┌────────────────────────────────────▼────────────────────────────────────┐
│                 4. Native Execution & Subprocess Engine                 │
│ PythonRuntime.kt ─── py_server.py (Unix Domain Socket / Process Groups) │
│       Engines: gallery-dl, yt-dlp, instaloader, aria2c, ffmpeg          │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ MediaStore Scans / Staging
┌────────────────────────────────────▼────────────────────────────────────┐
│                      5. Storage & File System                           │
│  cacheDir/gallery-dl-staging/<id>/  ─── MediaStoreHelper.saveToGallery  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Evolution of the Queue Model

1. **Phase 1: Round-Robin Unique Work Chains (`ExistingWorkPolicy.APPEND_OR_REPLACE`):**
   - Originally, downloads were enqueued round-robin into $N$ fixed WorkManager unique work chains named `gallery_dl_queue_0`, `gallery_dl_queue_1`, etc., where $N$ represented the concurrency limit.
   - **Fatal Flaw:** WorkManager chains enforce strict failure/cancellation cascades. If a user cancelled or deleted a single waiting item in lane 0, WorkManager automatically marked every subsequent item in that chain as `CANCELLED`. When the next download was appended via `APPEND_OR_REPLACE`, WorkManager purged the entire cancelled prefix, stranding dozens of database rows in `QUEUED` pointing at non-existent WorkRequests.
2. **Phase 2: One WorkManager Job Per Download (`ExistingWorkPolicy.REPLACE`):**
   - Implemented in commit `4b2a800` to eliminate cascading cancellations: every download receives its own unique WorkRequest named `gallery_dl_download_$id`.
   - **Architectural Tradeoff:** Shifting to one job per download meant WorkManager was flooded with dozens or hundreds of concurrent work requests simultaneously. To enforce concurrency limits, `DownloadWorker` introduced `DownloadConcurrencyGate`, an in-memory lock-free gate where workers block until an active slot becomes free.
   - **The New Failure Mode:** By shifting scheduling responsibility from WorkManager's queue engine into coroutine suspensions inside `DownloadWorker`, any worker crash, OS background kill, or uncaught exception inside the concurrency gate permanently severed the link between Room DB and WorkManager.

---

## Section 1 (R1): Root Cause Analysis of "Stuck in QUEUED with 0 Active Downloads"

### 1.1 The System-Stop / `Result.success()` Trap

The primary mechanism stranding downloads in `QUEUED` occurs when an active download is interrupted by system-level constraints.

#### Code Analysis
In `DownloadWorker.kt`:
```kotlin
// Lines 213-221
private suspend fun requeueIfStillRunning(dao: com.comfort.app.data.DownloadDao, downloadId: String) {
    withContext(NonCancellable) {
        runCatching {
            if (dao.getById(downloadId)?.workRequestId == id.toString()) {
                dao.updateStatusIfIn(downloadId, DownloadStatus.QUEUED, listOf(DownloadStatus.RUNNING))
            }
        }
    }
}
```
And throughout `doWork()`:
```kotlin
// Lines 417-430 (Pre-execution stop check)
if (isStopped) {
    requeueIfStillRunning(dao, downloadId)
    DownloadNotifications.cancel(applicationContext, downloadId)
    return@withContext Result.success()
}

// Lines 1016-1028 (Post-execution stop check)
if (isStopped || dao.getById(downloadId)?.workRequestId != id.toString()) {
    requeueIfStillRunning(dao, downloadId)
    DownloadNotifications.cancel(applicationContext, downloadId)
    return@withContext Result.success()
}

// Lines 1091-1095 (Generic exception stop check)
if (isStopped) {
    requeueIfStillRunning(dao, downloadId)
    DownloadNotifications.cancel(applicationContext, downloadId)
    Result.success()
}
```

#### Detailed Execution Trace & Failure Sequence
1. Item `X` is actively downloading (`status = RUNNING`, `active = 1`).
2. The user walks out of Wi-Fi range (triggering a network constraint violation), the device enters Android Doze mode, or the worker exceeds Android 14's Foreground Service execution limits.
3. WorkManager signals cancellation to `DownloadWorker`. Under the hood, `CoroutineWorker` sets `isStopped = true` and cancels the coroutine job.
4. At the next suspension point or exception handler, `isStopped` is detected as `true`.
5. The worker executes `requeueIfStillRunning()`. Because `status == RUNNING` and `workRequestId == id.toString()`, the Room DB row is updated to `DownloadStatus.QUEUED`.
6. `DownloadWorker` returns `Result.success()`.
7. **The Terminal Trap:** WorkManager inspects the returned `Result.success()`. WorkManager assumes the task completed its entire lifecycle without error. It updates its internal `WorkSpec` table to `State.SUCCEEDED` (`isFinished = true`) and removes the job from active scheduling.
8. **The Deadlock:**
   - Room DB records `DownloadStatus.QUEUED`.
   - WorkManager has zero active or pending jobs for this download.
   - The UI displays the item on the "In Queue" tab with "Waiting to start…".
   - Concurrency count drops to 0.
   - No event or timer exists to re-enqueue the job. The download is frozen indefinitely.

**Remediation:** On system stops (`isStopped == true`), `DownloadWorker` must return `Result.retry()`. This instructs WorkManager to reschedule the WorkRequest according to its backoff policy, keeping the job alive in WorkManager's scheduler.

---

### 1.2 Uncaught Cancellation of Waiting Workers at `DownloadConcurrencyGate.acquire()`

Under the one-job-per-download model, if a user enqueues 20 downloads and the concurrency limit is 2, WorkManager immediately invokes `doWork()` for all 20 downloads. Two workers proceed to download; the remaining 18 workers suspend in memory inside `DownloadConcurrencyGate.acquire()`.

#### Code Analysis
In `DownloadWorker.kt`:
```kotlin
// Lines 281-306
val gateAcquired = AtomicBoolean(false)
try {
    // BLOCKS HERE:
    DownloadConcurrencyGate.acquire(
        applicationContext, downloadId,
        queueOrder = entity?.queueOrder ?: 0,
        dateAdded = entity?.dateAdded ?: System.currentTimeMillis(),
    )
    gateAcquired.set(true)
    try {
        DownloadNotifications.updateProgress(...)
        return withContext(Dispatchers.IO) {
            try {
                // Inner IO execution
            } catch (e: CancellationException) {
                requeueIfStillRunning(dao, downloadId)
                throw e
            }
        }
    } finally {
        if (gateAcquired.compareAndSet(true, false)) {
            DownloadConcurrencyGate.release()
        }
    }
}
```

#### Detailed Execution Trace & Failure Sequence
1. Worker #3 enters `doWork()` and calls `DownloadConcurrencyGate.acquire()`. It suspends waiting on `slotFreed.first()`.
2. The Android OS reclaims background worker threads or cancels the job due to constraint shifts.
3. Coroutine cancellation throws `CancellationException` inside `DownloadConcurrencyGate.acquire()`.
4. `gateAcquired.set(true)` is **never reached**.
5. The coroutine unwinds through the outer `try` block. The inner `withContext(Dispatchers.IO)` block—which contains the `catch (e: CancellationException)` and `requeueIfStillRunning()` logic—is **never entered**.
6. The `CancellationException` escapes `doWork()` unhandled. WorkManager marks Worker #3 as `State.CANCELLED`.
7. The Room DB row remains `QUEUED` in Room DB with no WorkRequest bound to it.
8. When Workers #1 and #2 complete and release their slots, Worker #3 is already dead and cannot claim the newly freed slot. No replacement worker is scheduled.
9. **Concurrency Gate Slot Deadlock on Window Suspension:** If a worker wakes up after acquiring the gate, verifies that the schedule window has closed, and executes `DownloadDispatcher.suspendForSchedule(applicationContext, downloadId)` followed by `return Result.success()`, it must explicitly release the gate (`if (gateAcquired.compareAndSet(true, false)) DownloadConcurrencyGate.release()`). Otherwise, the slot is permanently leaked, deadlocking all subsequent queued items.

**Remediation:** Enclose `DownloadConcurrencyGate.acquire()` in its own `try/catch (e: CancellationException)` block that calls `requeueIfStillRunning(dao, downloadId)` and returns `Result.retry()` when `isStopped` is true. Ensure that any pre-execution window suspension releases the gate before returning.

---

### 1.3 Flawed Cold-Start Orphan Detection (`repairIfJobDead`)

When the application process is killed by the operating system (e.g. low-memory killer, system crash, or user swipe from Recents), all in-flight coroutines and native Python subprocesses are terminated abruptly.

#### Code Analysis
In `DownloadDispatcher.kt`:
```kotlin
// Lines 486-489
suspend fun repairOrphanedRunning(context: Context) {
    if (GalleryDlPreferences.isGloballyPaused(context)) return
    repairIfJobDead(context, AppDatabase.getDatabase(context).downloadDao().getRunningOnce())
}

// Lines 494-517
private suspend fun repairIfJobDead(context: Context, entities: List<DownloadEntity>) {
    val workManager = WorkManager.getInstance(context)
    entities.forEach { entity ->
        val uuid = entity.workRequestId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val info = uuid?.let { runCatching { workManager.getWorkInfoById(it).get() }.getOrNull() }
        if (info == null || info.state.isFinished) {
            enqueueWork(
                context, entity.id, entity.url,
                forceImmediate = entity.forceStart,
                expectedStatus = setOf(entity.status),
            )
        }
    }
}
```

#### Detailed Failure Mechanism
- WorkManager stores `WorkSpec` entries in an internal SQLite database (`androidx.work.workdatabase`).
- When an Android app process is abruptly terminated, WorkManager's persisted `WorkSpec` state for in-flight jobs often remains recorded as `State.RUNNING` or `State.ENQUEUED`.
- Upon app restart, `MainActivity` calls `DownloadDispatcher.repairOrphanedRunning()`.
- `repairIfJobDead()` executes `workManager.getWorkInfoById(uuid).get()`.
- Because WorkManager has not yet re-evaluated or re-scheduled the job, `info.state` returns `State.RUNNING` or `State.ENQUEUED`.
- Therefore, `info.state.isFinished` evaluates to `false`.
- `repairIfJobDead()` assumes the job is alive and takes **no action**.
- In reality, the OS process that was executing the job is dead, the Python server is dead, and the socket is closed.
- *(Note on redundancy: In `androidx.work.WorkInfo.State`, `isFinished` is defined as `this == SUCCEEDED || this == FAILED || this == CANCELLED`. Checking `|| info.state == WorkInfo.State.CANCELLED || info.state == WorkInfo.State.FAILED` is completely redundant and fails to address the dead-process issue.)*

**Remediation:** In `repairOrphanedRunning()`, recognize that on application cold start, **any row still marked `RUNNING` is an orphan from a terminated OS process**. Re-enqueue all `RUNNING` rows immediately. In `repairIfJobDead()`, re-enqueue whenever `uuid == null || info == null || info.state.isFinished`.

---

### 1.4 Absence of a Reactive Queue Coordinator on Worker Completion

In `DownloadWorker.kt`:
- Lines 1073–1077 (successful completion) and 1085–1121 (error completion) invoke `DownloadConcurrencyGate.release()`.
- `release()` only updates an in-memory counter and emits a unit to in-memory waiting coroutines.
- If the app experienced an orphan event, if waiting workers were evicted by the OS, or if a job completed when no other worker was actively suspended inside `acquire()`, **no database query or dispatch occurs**.
- In `DownloadDispatcher.kt:102`, `requestQueueRepair(context)` was declared `private`.

**Remediation:** Change `requestQueueRepair` visibility from `private` to `internal` in `DownloadDispatcher.kt`. In `DownloadWorker.kt`'s outer `finally` block, invoke `DownloadDispatcher.requestQueueRepair(applicationContext)` to proactively inspect and drain the queue whenever a worker exits.

---

### 1.5 Stale Constraints on Setting Changes

In `DownloadsSettings.kt` (lines 601 and 613), the download delay preference was modified directly via `GalleryDlPreferences` without ViewModel involvement:
```kotlin
GalleryDlPreferences.setDownloadDelayEnabled(context, it)
GalleryDlPreferences.setDownloadDelaySeconds(context, seconds)
```
When delay settings change, `DownloadDispatcher.rescheduleQueuedDownloads()` was never invoked. Existing `OneTimeWorkRequest` instances in WorkManager maintained their immutable initial delays and constraints.

**Remediation:** Route delay setting changes through `DownloadsSettingsViewModel`, triggering `rescheduleQueue(delayMs = 250L)` to cancel and re-enqueue queued WorkRequests with fresh parameters.

---

## Section 2 (R2): Pause & Schedule State Machine Audit

### 2.1 Inversion of Cancellation & Status Update Order

The most pernicious race condition in `DownloadDispatcher` was the inversion of `cancelWorkManagerJob()` and `dao.updateStatusIfIn()`.

#### Code Analysis
In `DownloadDispatcher.kt`:
```kotlin
// Lines 392-410
suspend fun pauseDownload(context: Context, id: String, notify: Boolean = true) {
    withDownloadLock(id) {
        val dao = AppDatabase.getDatabase(context).downloadDao()
        val entity = dao.getById(id) ?: return@withDownloadLock
        
        // BUG: Step 1 cancels the WorkManager job FIRST
        cancelWorkManagerJob(context, entity.workRequestId)
        
        // BUG: Step 2 updates DB status AFTER cancellation has completed
        if (dao.updateStatusIfIn(id, DownloadStatus.PAUSED, PAUSABLE_STATUSES) == 0) return@withDownloadLock
        dao.resetSpeed(id)
        ...
    }
}
```
And inside `cancelWorkManagerJob()`:
```kotlin
// Lines 88-95
private suspend fun cancelWorkManagerJob(context: Context, workRequestId: String?) {
    val uuid = workRequestId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
    val operation = WorkManager.getInstance(context).cancelWorkById(uuid)
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { operation.result.get(5, TimeUnit.SECONDS) } // BLOCKS FOR UP TO 5 SECONDS!
    }
    requestQueueRepair(context)
}
```

#### Detailed Execution Race Trace
```
Thread A (Dispatcher: pauseDownload)          Thread B (Worker: doWork)
─────────────────────────────────────          ─────────────────────────
1. Acquires lock(id)
2. Calls cancelWorkManagerJob()
3. Invokes WorkManager.cancelWorkById()
4. Blocks on operation.result.get(5s) ──►  5. Worker coroutine receives CancellationException
                                           6. Enters catch (e: CancellationException)
                                           7. Calls requeueIfStillRunning(dao, downloadId)
                                           8. Reads DB: status is STILL RUNNING!
                                           9. Executes updateStatusIfIn(QUEUED, [RUNNING])
                                              ==> DB STATUS OVERWRITTEN TO QUEUED!
                                           10. Worker rethrows CancellationException & dies.
11. Unblocks from 5s wait                      WorkManager marks job CANCELLED.
12. Executes updateStatusIfIn(PAUSED, ...)
    - Status is QUEUED (in PAUSABLE_STATUSES),
      so row transitions to PAUSED.
    - BUT: cancelWorkManagerJob already
      scheduled requestQueueRepair()!
13. If requestQueueRepair() runs before
    step 12 or if process dies:
    ==> Row remains QUEUED, Job is CANCELLED!
```
The exact same defect was present in `cancelDownload()` (`DownloadDispatcher.kt:423-426`), causing cancelled downloads to flash as `QUEUED` in the UI and risk resurrection by `repairOrphanedQueue()`.

**Remediation:** Update Room DB status via `dao.updateStatusIfIn(...)` *before* invoking `cancelWorkManagerJob(...)`.

---

### 2.2 Time-of-Check to Time-of-Use (TOCTOU) Worker Startup & Teardown Races

In `DownloadWorker.kt`:
```kotlin
// Lines 317-324
val freshStatus = dao.getById(downloadId)?.status
if (freshStatus == null || freshStatus == DownloadStatus.PAUSED || freshStatus == DownloadStatus.CANCELLED) {
    DownloadNotifications.cancel(applicationContext, downloadId)
    return@withContext Result.success()
}
// TOCTOU RACE:
dao.updateStatus(downloadId, DownloadStatus.RUNNING)
val startTime = System.currentTimeMillis()
dao.setStartTime(downloadId, startTime)
```

#### Race Analysis
1. Worker enters execution after clearing `DownloadConcurrencyGate.acquire()`.
2. At line 317, it checks `freshStatus` and finds `DownloadStatus.QUEUED`.
3. Concurrently, the user taps "Pause" or "Cancel" on the card.
4. `pauseDownload()` acquires the lock and updates Room DB status to `PAUSED`.
5. Worker resumes execution at line 322 and executes `dao.updateStatus(downloadId, DownloadStatus.RUNNING)`.
6. **The Startup Bug:** Line 322 used unconditional `updateStatus`, not `updateStatusIfIn`. It blindly overwrote `PAUSED` back to `RUNNING`.
7. **The Teardown Bug:** At lines 1041, 1073, and 1097, terminal updates used unconditional `dao.updateError()`. If a user paused or cancelled while the worker was completing its final write, the terminal write clobbered `PAUSED`/`CANCELLED` with `FINISHED` or `ERRORED`.

**Remediation:** Replace line 322 with `val updated = dao.updateStatusIfIn(downloadId, DownloadStatus.RUNNING, listOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED)); if (updated == 0) return@withContext Result.success()`. At lines 1041, 1073, and 1097, use conditional CAS requiring `status == DownloadStatus.RUNNING` before applying terminal state changes.

---

### 2.3 Dual Work Naming Collision on Fast-Track Start (`startNow`)

In `DownloadDispatcher.kt`:
```kotlin
// Lines 352-357
val uniqueName = if (forceImmediate) "gallery_dl_now_$id" else "gallery_dl_download_$id"
WorkManager.getInstance(context).enqueueUniqueWork(
    uniqueName,
    ExistingWorkPolicy.REPLACE,
    workRequest,
)
```
- Normal jobs used `"gallery_dl_download_$id"`, while `startNow()` used `"gallery_dl_now_$id"`.
- `ExistingWorkPolicy.REPLACE` only replaces jobs sharing the identical unique name.
- Both workers ran concurrently, racing on disk writes and MediaStore insertions.
- Additionally, calling `startNow()` on an already `RUNNING` item killed and restarted the download from scratch.

**Remediation:** Unify the unique work name to `"gallery_dl_download_$id"`. In `startNow()`, add a guard checking `if (entity.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED)) return` and pass `expectedStatus = setOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED)`.

---

### 2.4 Schedule Window Calculation: Second Truncation & Boundary Leaks

In `DownloadDispatcher.kt`:
```kotlin
// Lines 649-672
fun scheduleDelayMillis(context: Context): Long {
    if (!GalleryDlPreferences.isScheduleEnabled(context)) return 0L
    val startMin = GalleryDlPreferences.getScheduleStartMinutes(context).coerceIn(0, 1439)
    val endMin = GalleryDlPreferences.getScheduleEndMinutes(context).coerceIn(0, 1439)
    if (startMin == endMin) return 0L
    val now = Calendar.getInstance()
    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    val withinWindow = if (startMin <= endMin) {
        nowMin in startMin..endMin
    } else {
        nowMin >= startMin || nowMin <= endMin
    }
    if (withinWindow) return 0L

    val minutesUntilStart = if (nowMin < startMin) startMin - nowMin else (24 * 60 - nowMin) + startMin
    return TimeUnit.MINUTES.toMillis(minutesUntilStart.toLong())
}
```

#### Deficiencies Identified
1. **Truncation of Seconds and Milliseconds:** `scheduleDelayMillis` calculated delay purely in minutes (`TimeUnit.MINUTES.toMillis(...)`), delaying executions by up to 59 seconds and drifting against system alarms.
2. **Closed-Interval Boundary Leak:** `nowMin in startMin..endMin` allowed downloads to start at `endMin:00` (one minute after scheduled window closing).
3. **No Window-Open Check at Worker Startup:** A worker waking after delay elapsed had no pre-execution check to verify if the window was still open upon acquiring the concurrency gate.

**Remediation:** Subtract elapsed seconds/milliseconds in the current minute (`elapsedMsInCurrentMin`). Use half-open intervals `startMin until endMin` and `nowMin >= startMin || nowMin < endMin`. Add pre-execution window check in `DownloadWorker`.

---

### 2.5 Schedule Boundary Closure Leak, Phantom 24h Alarm Guard & Active Worker Suspension

In `DownloadDispatcher.kt`:
- `scheduleWindowAlarm` armed an alarm *only* when waiting for the window to open. Once inside the window (`delayMillis <= 0L`), it cancelled the alarm.
- If `startMin == endMin`, scheduling is unrestricted. Testing `if (delayMillis <= 0L)` without checking `startMin != endMin` resulted in computing an alarm against `endMin` and arming a phantom 24-hour `RTC_WAKEUP` alarm that drained battery indefinitely.
- When the window closed, `rescheduleQueuedDownloads()` only queried `getQueuedOnce()`. Currently `RUNNING` downloads were completely ignored and continued downloading indefinitely.

**Remediation:**
1. In `scheduleWindowAlarm()`, check `if (startMin == endMin) { runCatching { alarmManager.cancel(pendingIntent) }; return }`.
2. When `delayMillis <= 0L`, compute the remaining milliseconds until `endMin` and arm an `RTC_WAKEUP` alarm for window closure.
3. In `ScheduleAlarmReceiver.kt`, check `isWithinScheduleWindow()`. If the window closed, iterate `dao.getRunningOnce()` and invoke `DownloadDispatcher.suspendForSchedule(appContext, entity.id)` on all non-forced downloads.

---

## Section 3 (R3): Cancel, Error & Retry Lifecycle Review

### 3.1 Subprocess Management & Zombie Process Prevention: Process Group Contract

The app executes download engines (`gallery-dl`, `yt-dlp`, `instaloader`) through `PythonRuntime.kt`, which operates in two execution modes:

#### 1. Fork Server Mode (`py_server.py`)
- `py_server.py` acts as a Unix domain socket daemon.
- In `_run_job()` (line 60), before executing the entrypoint, the child process explicitly creates a new process group:
  ```python
  os.setpgid(0, 0)
  signal.signal(signal.SIGCHLD, signal.SIG_DFL)
  ```
- Because `os.setpgid(0, 0)` sets the PGID equal to the child PID, the process becomes a process group leader.
- In `PythonRuntime.kt` (`runViaServer`), cancellation executes:
  ```kotlin
  runCatching { Os.kill(-pid, android.system.OsConstants.SIGKILL) }
  runCatching { Os.kill(pid, android.system.OsConstants.SIGKILL) }
  ```
- **Contract Verification:** Delivering `SIGKILL` to `-pid` targets the entire process group. Any child processes spawned by Python (such as `ffmpeg` audio/video multiplexers or `aria2c` multi-part downloaders) receive `SIGKILL` atomically. This prevents zombie processes.

#### 2. Direct Process Fallback Mode (`runDirect`)
- When `USE_FORK_SERVER = false` or the socket server crashes, `PythonRuntime.runDirect()` is used.
- `runDirect` uses Java's `pythonProcess(...).start()`, which delegates to `java.lang.ProcessBuilder`.
- In Android/Linux, `ProcessBuilder.start()` **does not invoke `setpgid(0, 0)`**. The child process belongs to the parent application process group.
- Calling `Os.kill(-pid, SIGKILL)` in `runDirect` targets a non-existent process group, failing with `ErrnoException(ESRCH)`.
- `process.destroyForcibly()` only delivers `SIGKILL` to the primary Python process PID. Any child processes (`ffmpeg`, `aria2c`) become orphaned, are reparented to Android's `init` (PID 1), and continue running in the background.

**Architectural Documentation:** The fork server mode is the only mode providing POSIX process group isolation. Direct execution mode is strictly a best-effort emergency fallback and should log a warning regarding subprocess isolation limitations.

---

### 3.2 Staging Directory Lifecycle & Concurrency Race Condition

Each download writes temporary files into `cacheDir/gallery-dl-staging/<downloadId>/`.

#### The Cancellation Race Condition
In `DownloadDispatcher.cancelDownload()`:
```kotlin
// Line 436
deleteStagingDir(context, id)
```
Simultaneously in `DownloadWorker.kt`:
```kotlin
// Lines 964-966
finally {
    withContext(NonCancellable) {
        saveHeldFiles()
    }
}
```
- `cancelWorkManagerJob()` cancels the worker coroutine.
- While the coroutine is unwinding, `saveHeldFiles()` runs in `NonCancellable` context to flush completed files from staging to `MediaStoreHelper.saveMediaToGallery()`.
- Simultaneously, `cancelDownload()` on the dispatcher thread executed `deleteStagingDir(context, id)`.
- `deleteStagingDir()` deleted files out from underneath `saveHeldFiles()`.
- **Consequence:** `saveHeldFiles()` crashed with `FileNotFoundException`, or inserted truncated 0-byte entries into Android's MediaStore gallery.

**Remediation:** Remove immediate `deleteStagingDir(context, id)` from `DownloadDispatcher.cancelDownload()`. Staging directory cleanup is deferred to `DownloadWorker`'s exit path (after `saveHeldFiles()` has completed) or swept periodically by `StagingCleanupWorker`.

---

### 3.3 Download Archive Preservation

- Download archives (`archives/$id.sqlite3`, `archives/$id.ytdlp.txt`, `archives/$id.instaloader.txt`) record IDs of completed files to avoid re-downloading media from multi-item galleries or channels.
- `cancelDownload()` and `pauseDownload()` intentionally preserve these archive files.
- Only `deleteDownload()` (`DownloadDispatcher.kt:527-530`) purges the archives.
- **Evaluation:** This design is sound and verified correct.

---

### 3.4 Incomplete State Resets on Retry

In `DownloadsViewModel.kt`:
```kotlin
// Lines 226-236
fun retryDownload(id: String) {
    viewModelScope.launch {
        val context = getApplication<Application>()
        val entity = dao.getById(id) ?: return@launch
        if (entity.status == DownloadStatus.ERRORED) dao.moveToQueueEnd(entity.id, System.currentTimeMillis())
        DownloadDispatcher.enqueueWork(context, entity.id, entity.url, userInitiated = true)
    }
}
```
In `DownloadDao.kt`:
```kotlin
// Line 62
@Query("UPDATE downloads SET queueOrder = 0, forceStart = 0, dateAdded = :now WHERE id = :id")
suspend fun moveToQueueEnd(id: String, now: Long)
```

#### Deficiencies Identified
1. **Stale Error Details & Timestamps:** `moveToQueueEnd()` left `errorMessage`, `errorDetails`, `erroredAt`, `speedMbs`, `liveBytes`, and `etaSeconds` untouched.
2. **UI Artifacts:** Retried items displayed stale error strings alongside "Waiting to start…".
3. **Cancelled Retries Bypassed Queue Ordering:** If the item was `CANCELLED`, `moveToQueueEnd()` was not called.

**Remediation:** Introduce `dao.resetForRetry(id, newStatus, order, now)` which explicitly sets `errorMessage = null`, `errorDetails = null`, and `erroredAt = null`, along with resetting live metrics and queue order.

---

## Section 4 (R4): UI & ViewModel Presentation Layer Audit

### 4.1 Status Tab Filtering in `QueueScreen.kt`

The UI organizes queue items across six distinct tabs:
```kotlin
// Line 142
val filters = listOf("Running", "In Queue", "Paused", "Scheduled", "Errored", "Cancelled")
```
Items are filtered strictly by `DownloadStatus`:
```kotlin
fun itemsFor(filter: String) =
    queueItems.filter { item ->
        when (filter) {
            "Running" -> item.status == DownloadStatus.RUNNING
            "In Queue" -> item.status == DownloadStatus.QUEUED
            "Scheduled" -> item.status == DownloadStatus.SCHEDULED
            "Paused" -> item.status == DownloadStatus.PAUSED
            "Errored" -> item.status == DownloadStatus.ERRORED
            "Cancelled" -> item.status == DownloadStatus.CANCELLED
            else -> false
        }
    }
```
Stopped states are cleanly partitioned into "Paused", "Errored", and "Cancelled".

---

### 4.2 Badge Count Discrepancy & Omission of `SCHEDULED`

In `DownloadsViewModel.kt`:
```kotlin
// Lines 109-115
val hasActiveDownloads: StateFlow<Boolean> = queueFlow
    .map { list -> list.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

val activeDownloadsCount: StateFlow<Int> = queueFlow
    .map { list -> list.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
```
- When a user enqueues downloads with a nighttime schedule configured, all items enter `SCHEDULED`.
- The Queue tab badge and navigation icons displayed **0** active downloads, while `QueueScreen` showed active scheduled items.

**Remediation:** Include `DownloadStatus.SCHEDULED` in `hasActiveDownloads` and `activeDownloadsCount`.

---

### 4.3 Action Wiring in `StoppedRow` & Intentional Exclusion of Swipe-to-Delete

#### 1. Action Wiring in `StoppedRow` (`QueueScreen.kt:594`)
```kotlin
StoppedRow(
    item = item,
    onResume = { viewModel.retryDownload(item.id) }, // DEFECT: Calls retryDownload for PAUSED items!
    onDelete = { requestDelete(setOf(item.id)) },
    selectionMode = selectionMode,
    selected = isSelected,
    onToggleSelect = { toggleSelected(item.id) },
)
```
- In `QueueScreen.kt:594`, `onResume` was bound to `viewModel.retryDownload(item.id)` for both `PAUSED` and `CANCELLED` items.
- For a `PAUSED` download, `retryDownload()` sends the item to the back of the queue, resets progress, and bypasses clean resume dispatcher logic.
- Furthermore, `DownloadsViewModel` lacked a `resumeDownload(id: String)` method.

**Remediation:** Add `fun resumeDownload(id: String)` to `DownloadsViewModel.kt` calling `DownloadDispatcher.resumeDownload(context, id)`. In `QueueScreen.kt:594`, wire `onResume` to:
```kotlin
onResume = {
    if (item.status == DownloadStatus.PAUSED) {
        viewModel.resumeDownload(item.id)
    } else {
        viewModel.retryDownload(item.id)
    }
}
```

#### 2. Architectural Analysis: Intentional Exclusion of Swipe-to-Delete
`QueueScreen.kt` lines 631–636 contain the following explicit architectural documentation:
```kotlin
// No swipe-to-remove on Queue cards: a sideways swipe anywhere here moves
// between the filters (PageSwipe.kt). On a card it went to the card's own
// swipe, and the card's page-turning edge strips sit in Android's
// back-gesture zone, so fast swipes almost never turned the page. Every
// card has its own delete/Cancel button.
row()
```
Swipe-to-delete is **intentionally omitted** from `QueueScreen.kt` to prevent gesture conflicts with the horizontal pager navigation (`PageSwipe.kt`) between filter tabs. Every card on `QueueScreen` provides dedicated, accessible action buttons (Delete/Cancel/Pause/Resume) within the card layout. No `SwipeToDeleteCard` exists or should be added to `QueueScreen.kt`.

---

### 4.4 Resolution of `retryAll` State Transition Regression

In prior proposals, `DownloadsViewModel.retryAll(status)` invoked `dao.resetForRetry(...)` before calling `DownloadDispatcher.enqueueWork(...)`:
```kotlin
// DEFECTIVE IMPLEMENTATION:
queueFlow.value
    .filter { it.status == status }
    .forEachIndexed { index, entity ->
        dao.resetForRetry(entity.id, DownloadStatus.QUEUED, order = 0, now = now + index)
        DownloadDispatcher.enqueueWork(context, entity.id, entity.url, expectedStatus = setOf(status))
    }
```
In `DownloadDispatcher.enqueueWork()` (lines 268–272):
```kotlin
val current = dao.getById(id)
if (expectedStatus != null && (current == null || current.status !in expectedStatus)) {
    return@withDownloadLock
}
```
Because `dao.resetForRetry` updated `current.status` to `QUEUED`, `current.status !in expectedStatus` evaluated to `true`. `enqueueWork` aborted immediately without submitting work to WorkManager. All retried downloads were permanently stranded in `QUEUED`.

**Remediation:** In `retryAll()`, pass `expectedStatus = setOf(status, DownloadStatus.QUEUED)` (or defer `resetForRetry` to `enqueueWork`).

---

## Section 5: State Machine Diagrams

### 5.1 Current (Defective) State Machine

```
                      ┌────────────────────────────────────────┐
                      │                 ENQUEUE                │
                      └───────────────────┬────────────────────┘
                                          │
                                          ▼
                      ┌────────────────────────────────────────┐
                      │                 QUEUED                 │
                      └───────┬────────────────────────▲───────┘
                              │                        │
               WorkManager    │                        │ requeueIfStillRunning()
               starts worker  │                        │ [BUG 1: System Stop Trap]
                              ▼                        │ [BUG 2: Inverted Pause/Cancel]
                      ┌────────────────────────┐       │
                      │ ConcurrencyGate.acquire│       │
                      └───┬────────────────────┘       │
                          │                            │
             acquire()    │ Concurrency slot           │
             throws       │ granted                    │
             Cancellation │                            │
             [BUG 3]      ▼                            │
        ┌───────────► ┌────────────────────────┐       │
        │             │  dao.updateStatus()    │       │
        │             │  RUNNING (Unconditional├───────┤
        │             └───┬────────────────────┘       │
        │                 │                            │
        │                 │ (Subprocess executes)      │
        │                 │                            │
        │                 ▼                            │
┌───────┴──────┐      ┌────────────────────────┐       │
│ WorkManager  │      │   Cancellation or      │       │
│ CANCELLED    │      │   System Stop Occurs   ├───────┘
│ (DB still in │      └───────────┬────────────┘
│  QUEUED)     │                  │
└──────────────┘                  ▼
                      ┌────────────────────────┐
                      │ DownloadWorker.doWork  │
                      │ returns Result.success ├──────────────┐
                      └───────────┬────────────┘              │
                                  │                           ▼
                                  ▼               ┌────────────────────────┐
                      ┌────────────────────────┐  │ deleteStagingDir()     │
                      │ WorkManager Marks Job  │  │ races saveHeldFiles()  │
                      │ SUCCEEDED & Destroys It│  │ [Corrupt 0-byte media] │
                      │ [Permanent Deadlock]   │  └────────────────────────┘
                      └────────────────────────┘
```

---

### 5.2 Remediated State Machine

```
                      ┌────────────────────────────────────────┐
                      │                 ENQUEUE                │
                      │     (Compute Schedule Window Delay)    │
                      └───────┬────────────────────────┬───────┘
                              │ Delay > 0              │ Delay == 0
                              ▼                        ▼
                      ┌────────────────┐       ┌────────────────┐
                      │   SCHEDULED    │       │     QUEUED     │
                      └───────┬────────┘       └───────┬────────┘
                              │ Window                 │ WorkManager
                              │ Opens (Alarm)          │ Launches Worker
                              ▼                        ▼
                      ┌────────────────────────────────────────┐
                      │        DownloadConcurrencyGate         │
                      │ (Catches Cancellation -> Result.retry) │
                      └───────────────────┬────────────────────┘
                                          │ Slot Acquired
                                          ▼
                      ┌────────────────────────────────────────┐
                      │ Pre-Execution Schedule Check:          │
                      │ If window closed -> Release Gate &     │
                      │ suspendForSchedule() -> Result.success │
                      └───────────────────┬────────────────────┘
                                          │ Window Open
                                          ▼
                      ┌────────────────────────────────────────┐
                      │ Atomic CAS: updateStatusIfIn           │
                      │ [QUEUED, SCHEDULED] -> RUNNING         │
                      └───────┬────────────────────────┬───────┘
                              │ CAS == 0               │ CAS == 1
                              ▼ (Already Stopped)      ▼
                      ┌────────────────┐       ┌────────────────┐
                      │ Return Success │       │    RUNNING     │
                      │ (No-op Clean)  │       │ (Subprocess)   │
                      └────────────────┘       └───────┬────────┘
                                                       │
                 ┌───────────────────┬─────────────────┼───────────────────┐
                 │ User Pauses       │ User Cancels    │ System Stop       │ Completion
                 ▼                   ▼                 ▼ (Doze/Network)    ▼
         ┌───────────────┐   ┌───────────────┐ ┌───────────────┐   ┌───────────────┐
         │ 1. CAS PAUSED │   │ 1. CAS CANCEL │ │ 1. CAS QUEUED │   │ 1. MediaStore │
         │ 2. Cancel Job │   │ 2. Cancel Job │ │ 2. request    │   │    Flush      │
         │ 3. Reset Spd  │   │ 3. Reset Spd  │ │    QueueRepair│   │ 2. CAS FINISH │
         │               │   │ 4. Defer Stage│ │ 3. Return     │   │ 3. Gate       │
         │               │   │    Deletion   │ │    RETRY()    │   │    Release    │
         └───────┬───────┘   └───────┬───────┘ └───────┬───────┘   │ 4. Proactive  │
                 │                   │                 │           │    Dispatch   │
                 ▼                   ▼                 ▼           └───────┬───────┘
         ┌───────────────┐   ┌───────────────┐ ┌───────────────┐           │
         │    PAUSED     │   │   CANCELLED   │ │ Rescheduled   │           ▼
         │               │   │               │ │ via Retry     │   ┌───────────────┐
         └───────────────┘   └───────────────┘ └───────────────┘   │   FINISHED    │
                                                                   └───────────────┘
```

---

## Section 6: Production-Ready Code Patches

### 6.1 Patch 1: `DownloadDispatcher.kt`
- Makes `requestQueueRepair` `internal` so `DownloadWorker` can trigger queue drains.
- Standardizes unique work name to `"gallery_dl_download_$id"` (eliminating the `"gallery_dl_now_$id"` collision).
- Guards `startNow()` against active downloads.
- Reorders status updates before `cancelWorkManagerJob()` in `pauseDownload()` and `cancelDownload()`.
- Synchronizes staging cleanup by removing immediate synchronous `deleteStagingDir` on cancel.
- Unconditionally repairs dead-process `RUNNING` rows on cold start.
- Adds exact millisecond calculation to `scheduleDelayMillis` (`startMin until endMin`).
- Adds `startMin == endMin` guard in `scheduleWindowAlarm` and arms alarm for window closure.

```diff
--- a/app/src/main/java/com/comfort/app/data/DownloadDispatcher.kt
+++ b/app/src/main/java/com/comfort/app/data/DownloadDispatcher.kt
@@ -101,3 +101,3 @@ object DownloadDispatcher {
     @Synchronized
-    private fun requestQueueRepair(context: Context) {
+    internal fun requestQueueRepair(context: Context) {
         val appContext = context.applicationContext
@@ -351,7 +351,7 @@ object DownloadDispatcher {
         // queue order (including "Up next") for every worker that reaches it, so every queued
         // download now simply waits there for the next free slot. REPLACE only ever replaces this
         // same download's own previous job, which is cancelled above anyway.
-        val uniqueName = if (forceImmediate) "gallery_dl_now_$id" else "gallery_dl_download_$id"
+        val uniqueName = "gallery_dl_download_$id"
         WorkManager.getInstance(context).enqueueUniqueWork(
             uniqueName,
             ExistingWorkPolicy.REPLACE,
@@ -395,9 +395,9 @@ object DownloadDispatcher {
         withDownloadLock(id) {
             val dao = AppDatabase.getDatabase(context).downloadDao()
             val entity = dao.getById(id) ?: return@withDownloadLock
-            cancelWorkManagerJob(context, entity.workRequestId)
             // Conditional: Pause All works from a snapshot, and a download that finished or failed
             // before its turn must stay FINISHED/ERRORED rather than being flipped to PAUSED.
             if (dao.updateStatusIfIn(id, DownloadStatus.PAUSED, PAUSABLE_STATUSES) == 0) return@withDownloadLock
+            cancelWorkManagerJob(context, entity.workRequestId)
             dao.resetSpeed(id)
             if (notify) {
@@ -422,17 +422,16 @@ object DownloadDispatcher {
         withDownloadLock(id) {
             val dao = AppDatabase.getDatabase(context).downloadDao()
             val entity = dao.getById(id) ?: return@withDownloadLock
-            cancelWorkManagerJob(context, entity.workRequestId)
             // Same reasoning as pauseDownload: don't overwrite a download that already finished or
             // failed (and don't wipe its staging dir either).
             if (dao.updateStatusIfIn(id, DownloadStatus.CANCELLED, CANCELLABLE_STATUSES) == 0) return@withDownloadLock
+            cancelWorkManagerJob(context, entity.workRequestId)
             dao.resetSpeed(id)
             DownloadNotifications.cancel(context, id)
-            // cancelWorkManagerJob() just requests the stop — it doesn't wait for the worker's own
-            // subprocess to actually die, so this can briefly race an item still writing into its
-            // staging dir. deleteRecursively() is best-effort (doesn't throw, just skips whatever's
-            // locked at that instant) rather than a hard delete, and the subprocess dies moments
-            // later anyway once PythonRuntime's own cancellation-triggered kill lands — so a rare
-            // near-miss here just leaves the same harmless private-cache leftover this was already
-            // living with, never a real failure.
-            deleteStagingDir(context, id)
+            // Staging directory deletion is intentionally not performed synchronously here:
+            // the worker coroutine may still be executing saveHeldFiles() in NonCancellable context
+            // to flush files to the gallery. Immediate deletion causes FileNotFoundException and
+            // corrupted 0-byte media. Staging directory cleanup is handled upon worker termination
+            // or by StagingCleanupWorker.
         }
@@ -487,7 +487,14 @@ object DownloadDispatcher {
         if (GalleryDlPreferences.isGloballyPaused(context)) return
-        repairIfJobDead(context, AppDatabase.getDatabase(context).downloadDao().getRunningOnce())
+        val dao = AppDatabase.getDatabase(context).downloadDao()
+        // In a fresh process after cold start, any RUNNING row is an orphan from the killed process.
+        dao.getRunningOnce().forEach { entity ->
+            enqueueWork(
+                context, entity.id, entity.url,
+                forceImmediate = entity.forceStart,
+                expectedStatus = setOf(DownloadStatus.RUNNING),
+            )
+        }
     }
@@ -502,3 +509,3 @@ object DownloadDispatcher {
             val info = uuid?.let { runCatching { workManager.getWorkInfoById(it).get() }.getOrNull() }
-            if (info == null || info.state.isFinished) {
+            if (uuid == null || info == null || info.state.isFinished) {
                 enqueueWork(
@@ -636,4 +643,5 @@ object DownloadDispatcher {
         val entity = dao.getById(id) ?: return
+        if (entity.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED)) return
         dao.setForceStart(id, -(System.currentTimeMillis() / 1000L).toInt())
-        enqueueWork(context, id, entity.url, forceImmediate = true, userInitiated = true)
+        enqueueWork(context, id, entity.url, forceImmediate = true, userInitiated = true, expectedStatus = setOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED))
         repairOrphanedQueue(context)
@@ -659,15 +667,19 @@ object DownloadDispatcher {
         val now = Calendar.getInstance()
         val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
+        val nowSec = now.get(Calendar.SECOND)
+        val nowMs = now.get(Calendar.MILLISECOND)
+        val elapsedMsInCurrentMin = (nowSec * 1000L) + nowMs
 
         val withinWindow = if (startMin <= endMin) {
-            nowMin in startMin..endMin
+            nowMin in startMin until endMin
         } else {
-            nowMin >= startMin || nowMin <= endMin
+            nowMin >= startMin || nowMin < endMin
         }
         if (withinWindow) return 0L
 
         val minutesUntilStart = if (nowMin < startMin) startMin - nowMin else (24 * 60 - nowMin) + startMin
-        return TimeUnit.MINUTES.toMillis(minutesUntilStart.toLong())
+        return (TimeUnit.MINUTES.toMillis(minutesUntilStart.toLong()) - elapsedMsInCurrentMin).coerceAtLeast(0L)
     }
@@ -701,13 +713,24 @@ object DownloadDispatcher {
+        val startMin = GalleryDlPreferences.getScheduleStartMinutes(context).coerceIn(0, 1439)
+        val endMin = GalleryDlPreferences.getScheduleEndMinutes(context).coerceIn(0, 1439)
+        if (startMin == endMin) {
+            runCatching { alarmManager.cancel(pendingIntent) }
+            return
+        }
         val delayMillis = scheduleDelayMillis(context)
         if (delayMillis <= 0L) {
-            // Already inside the window (or nothing configured) — nothing to wake up for.
-            runCatching { alarmManager.cancel(pendingIntent) }
-            return
+            // Inside the window: arm an alarm for when the window CLOSES so queued/running downloads suspend properly.
+            val now = Calendar.getInstance()
+            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
+            val nowSec = now.get(Calendar.SECOND)
+            val nowMs = now.get(Calendar.MILLISECOND)
+            val elapsedMsInCurrentMin = (nowSec * 1000L) + nowMs
+            val minutesUntilEnd = if (nowMin < endMin) endMin - nowMin else (24 * 60 - nowMin) + endMin
+            val closeDelayMillis = (TimeUnit.MINUTES.toMillis(minutesUntilEnd.toLong()) - elapsedMsInCurrentMin).coerceAtLeast(0L)
+            runCatching {
+                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + closeDelayMillis, pendingIntent)
+            }
+        } else {
+            runCatching {
+                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMillis, pendingIntent)
+            }
         }
```

---

### 6.2 Patch 2: `DownloadWorker.kt`
- Wraps `DownloadConcurrencyGate.acquire()` in `try/catch (e: CancellationException)`.
- Enforces pre-execution schedule window check upon acquiring gate, releasing the gate before returning `Result.success()`.
- Replaces raw `updateStatus` with atomic CAS `updateStatusIfIn(RUNNING, [QUEUED, SCHEDULED])`.
- Returns `Result.retry()` on system stops instead of `Result.success()`.
- Uses atomic CAS on completion and error exits so a late finish does not overwrite `PAUSED` or `CANCELLED`.
- Removes out-of-scope `saveHeldFiles()` from outer catch (handled in `finally` at line 966).
- Triggers `DownloadDispatcher.requestQueueRepair(applicationContext)` in `finally`.

```diff
--- a/app/src/main/java/com/comfort/app/worker/DownloadWorker.kt
+++ b/app/src/main/java/com/comfort/app/worker/DownloadWorker.kt
@@ -282,12 +282,34 @@ class DownloadWorker(
         try {
+            try {
+                DownloadConcurrencyGate.acquire(
+                    applicationContext, downloadId,
+                    queueOrder = entity?.queueOrder ?: 0,
+                    dateAdded = entity?.dateAdded ?: System.currentTimeMillis(),
+                )
+                gateAcquired.set(true)
+            } catch (e: CancellationException) {
+                if (isStopped) {
+                    requeueIfStillRunning(dao, downloadId)
+                    DownloadNotifications.cancel(applicationContext, downloadId)
+                    return Result.retry()
+                }
+                throw e
+            }
+
+            // Verify schedule window upon waking: if the window closed while waiting in line, suspend immediately.
+            if (GalleryDlPreferences.isScheduleEnabled(applicationContext) &&
+                !DownloadDispatcher.isWithinScheduleWindow(applicationContext) &&
+                entity?.forceStart != true
+            ) {
+                if (gateAcquired.compareAndSet(true, false)) {
+                    DownloadConcurrencyGate.release()
+                }
+                DownloadDispatcher.suspendForSchedule(applicationContext, downloadId)
+                return Result.success()
+            }
+
             try {
                 DownloadNotifications.updateProgress(applicationContext, downloadId, displayTitle, entity?.downloadedItems ?: 0, initialPercent)
                 return withContext(Dispatchers.IO) {
                     try {
-                        val freshStatus = dao.getById(downloadId)?.status
-                        if (freshStatus == null || freshStatus == DownloadStatus.PAUSED || freshStatus == DownloadStatus.CANCELLED) {
+                        if (isStopped) {
+                            requeueIfStillRunning(dao, downloadId)
+                            DownloadNotifications.cancel(applicationContext, downloadId)
+                            return@withContext Result.retry()
+                        }
+                        val updated = dao.updateStatusIfIn(downloadId, DownloadStatus.RUNNING, listOf(DownloadStatus.QUEUED, DownloadStatus.SCHEDULED))
+                        if (updated == 0) {
                             DownloadNotifications.cancel(applicationContext, downloadId)
                             return@withContext Result.success()
                         }
-                        dao.updateStatus(downloadId, DownloadStatus.RUNNING)
                         val startTime = System.currentTimeMillis()
                         dao.setStartTime(downloadId, startTime)
@@ -426,5 +448,5 @@ class DownloadWorker(
                     requeueIfStillRunning(dao, downloadId)
                     DownloadNotifications.cancel(applicationContext, downloadId)
-                    return@withContext Result.success()
+                    return@withContext Result.retry()
                 }
@@ -1024,5 +1046,5 @@ class DownloadWorker(
                     requeueIfStillRunning(dao, downloadId)
                     DownloadNotifications.cancel(applicationContext, downloadId)
-                    return@withContext Result.success()
+                    return@withContext if (isStopped) Result.retry() else Result.success()
                 }
@@ -1041,3 +1063,7 @@ class DownloadWorker(
-                    dao.updateError(downloadId, DownloadStatus.ERRORED, errorMsg)
-                    dao.setErrorDetails(downloadId, errorDetailsJson())
-                    DownloadNotifications.notifyFailed(applicationContext, downloadId, displayTitle, errorMsg)
+                    val updated = dao.updateStatusIfIn(downloadId, DownloadStatus.ERRORED, listOf(DownloadStatus.RUNNING))
+                    if (updated > 0) {
+                        dao.updateError(downloadId, DownloadStatus.ERRORED, errorMsg)
+                        dao.setErrorDetails(downloadId, errorDetailsJson())
+                        DownloadNotifications.notifyFailed(applicationContext, downloadId, displayTitle, errorMsg)
+                    }
                     DownloadDispatcher.forgetLock(downloadId)
@@ -1073,2 +1099,6 @@ class DownloadWorker(
-                    dao.updateError(downloadId, DownloadStatus.FINISHED, incompleteMessage)
-                    dao.setErrorDetails(downloadId, if (incompleteMessage != null) errorDetailsJson() else null)
+                    val updated = dao.updateStatusIfIn(downloadId, DownloadStatus.FINISHED, listOf(DownloadStatus.RUNNING))
+                    if (updated > 0) {
+                        if (incompleteMessage != null) dao.updateError(downloadId, DownloadStatus.FINISHED, incompleteMessage)
+                        dao.setErrorDetails(downloadId, if (incompleteMessage != null) errorDetailsJson() else null)
+                    }
                 }
@@ -1091,5 +1121,9 @@ class DownloadWorker(
                 if (isStopped) {
                     requeueIfStillRunning(dao, downloadId)
                     DownloadNotifications.cancel(applicationContext, downloadId)
-                    Result.success()
+                    Result.retry()
                 } else {
                     val sanitized = e.localizedMessage?.let { GalleryDlListing.sanitizeErrorMessage(it, applicationContext.resources) }
-                    dao.updateError(downloadId, DownloadStatus.ERRORED, sanitized)
+                    val updated = dao.updateStatusIfIn(downloadId, DownloadStatus.ERRORED, listOf(DownloadStatus.RUNNING))
+                    if (updated > 0) {
+                        dao.updateError(downloadId, DownloadStatus.ERRORED, sanitized)
+                    }
@@ -1124,4 +1158,6 @@ class DownloadWorker(
             if (gateAcquired.compareAndSet(true, false)) {
                 DownloadConcurrencyGate.release()
             }
+            // Proactively drain/repair queue in case waiting workers were evicted
+            DownloadDispatcher.requestQueueRepair(applicationContext)
         }
```

---

### 6.3 Patch 3: `DownloadDao.kt`
- Adds atomic `resetForRetry` query to wipe error messages, details, `erroredAt`, and live metrics upon retrying.

```diff
--- a/app/src/main/java/com/comfort/app/data/DownloadDao.kt
+++ b/app/src/main/java/com/comfort/app/data/DownloadDao.kt
@@ -63,4 +63,21 @@ interface DownloadDao {
     suspend fun moveToQueueEnd(id: String, now: Long)
 
+    /** Resets an errored or cancelled download cleanly for re-enqueuing: clears stale error strings,
+     * error details, timestamps, metrics, and places it at the tail of the queue. */
+    @Query("""
+        UPDATE downloads 
+        SET status = :newStatus,
+            errorMessage = null,
+            errorDetails = null,
+            erroredAt = null,
+            speedMbs = 0,
+            etaSeconds = 0,
+            liveBytes = 0,
+            downloadStartTime = 0,
+            queueOrder = :order,
+            forceStart = 0,
+            dateAdded = :now
+        WHERE id = :id
+    """)
+    suspend fun resetForRetry(id: String, newStatus: DownloadStatus, order: Int = 0, now: Long = System.currentTimeMillis())
+
     @Query("UPDATE downloads SET queueOrder = :order WHERE id = :id")
```

---

### 6.4 Patch 4: `DownloadsViewModel.kt`
- Includes `DownloadStatus.SCHEDULED` in active download counts and state flows.
- Adds missing `resumeDownload(id: String)` method.
- Fixes `retryDownload` and `retryAll` regression by passing `expectedStatus = setOf(status, DownloadStatus.QUEUED)` to `enqueueWork`.

```diff
--- a/app/src/main/java/com/comfort/app/viewmodel/DownloadsViewModel.kt
+++ b/app/src/main/java/com/comfort/app/viewmodel/DownloadsViewModel.kt
@@ -109,7 +109,11 @@ class DownloadsViewModel(application: Application) : AndroidViewModel(applicat
     val hasActiveDownloads: StateFlow<Boolean> = queueFlow
-        .map { list -> list.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED } }
+        .map { list -> list.any { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED } }
         .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
 
     val activeDownloadsCount: StateFlow<Int> = queueFlow
-        .map { list -> list.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED } }
+        .map { list -> list.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.SCHEDULED } }
         .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
@@ -223,7 +227,15 @@ class DownloadsViewModel(application: Application) : AndroidViewModel(applicat
     }
 
+    fun resumeDownload(id: String) {
+        viewModelScope.launch {
+            val context = getApplication<Application>()
+            DownloadDispatcher.resumeDownload(context, id)
+        }
+    }
+
     fun retryDownload(id: String) {
         viewModelScope.launch {
             val context = getApplication<Application>()
             val entity = dao.getById(id) ?: return@launch
-            if (entity.status == DownloadStatus.ERRORED) dao.moveToQueueEnd(entity.id, System.currentTimeMillis())
-            DownloadDispatcher.enqueueWork(context, entity.id, entity.url, userInitiated = true)
+            if (entity.status == DownloadStatus.ERRORED) {
+                dao.resetForRetry(entity.id, DownloadStatus.QUEUED, order = 0, now = System.currentTimeMillis())
+            }
+            DownloadDispatcher.enqueueWork(context, entity.id, entity.url, userInitiated = true, expectedStatus = setOf(entity.status, DownloadStatus.QUEUED))
         }
     }
@@ -253,5 +265,7 @@ class DownloadsViewModel(application: Application) : AndroidViewModel(applicat
                 .forEachIndexed { index, entity ->
-                    if (status == DownloadStatus.ERRORED) dao.moveToQueueEnd(entity.id, now + index)
-                    DownloadDispatcher.enqueueWork(context, entity.id, entity.url, expectedStatus = setOf(status))
+                    if (status == DownloadStatus.ERRORED) {
+                        dao.resetForRetry(entity.id, DownloadStatus.QUEUED, order = 0, now = now + index)
+                    }
+                    DownloadDispatcher.enqueueWork(context, entity.id, entity.url, expectedStatus = setOf(status, DownloadStatus.QUEUED))
                 }
         }
```

---

### 6.5 Patch 5: `QueueScreen.kt`
- Fixes `StoppedRow` resume callback to call `resumeDownload()` for `PAUSED` items and `retryDownload()` for `CANCELLED` items.
- Retains intentional exclusion of `SwipeToDeleteCard` per lines 631–636.

```diff
--- a/app/src/main/java/com/comfort/app/ui/main/QueueScreen.kt
+++ b/app/src/main/java/com/comfort/app/ui/main/QueueScreen.kt
@@ -593,3 +593,3 @@ fun QueueScreen(...) {
                                     StoppedRow(
                                         item = item,
-                                        onResume = { viewModel.retryDownload(item.id) },
+                                        onResume = { if (item.status == DownloadStatus.PAUSED) viewModel.resumeDownload(item.id) else viewModel.retryDownload(item.id) },
                                         onDelete = { requestDelete(setOf(item.id)) },
```

---

### 6.6 Patch 6: `ScheduleAlarmReceiver.kt`
- Handles schedule window closure: suspends active `RUNNING` downloads and reschedules queued downloads.
- Re-arms the exact alarm for the next window boundary.

```diff
--- a/app/src/main/java/com/comfort/app/worker/ScheduleAlarmReceiver.kt
+++ b/app/src/main/java/com/comfort/app/worker/ScheduleAlarmReceiver.kt
@@ -6,2 +6,4 @@ import android.content.Intent
 import com.comfort.app.data.DownloadDispatcher
+import com.comfort.app.data.AppDatabase
 import kotlinx.coroutines.CoroutineScope
@@ -23,3 +25,17 @@ class ScheduleAlarmReceiver : BroadcastReceiver() {
             try {
-                DownloadDispatcher.rescheduleQueuedDownloads(appContext)
+                if (DownloadDispatcher.isWithinScheduleWindow(appContext)) {
+                    DownloadDispatcher.rescheduleQueuedDownloads(appContext)
+                } else {
+                    // Window just closed: suspend active running downloads (unless forceStart)
+                    val dao = AppDatabase.getDatabase(appContext).downloadDao()
+                    dao.getRunningOnce().forEach { entity ->
+                        if (!entity.forceStart) {
+                            DownloadDispatcher.suspendForSchedule(appContext, entity.id)
+                        }
+                    }
+                    DownloadDispatcher.rescheduleQueuedDownloads(appContext)
+                }
+                DownloadDispatcher.scheduleWindowAlarm(appContext)
             } finally {
```

---

### 6.7 Patch 7: `PythonRuntime.kt`
- Documents process group isolation contract between `runViaServer` and direct execution fallback.

```diff
--- a/app/src/main/java/com/comfort/app/util/PythonRuntime.kt
+++ b/app/src/main/java/com/comfort/app/util/PythonRuntime.kt
@@ -569,2 +569,5 @@ object PythonRuntime {
                         if (process.isAlive) {
+                            // ProcessBuilder does not configure a process group (unlike py_server.py's
+                            // os.setpgid(0, 0)). destroyForcibly() terminates the primary Python PID.
+                            // Subprocesses (ffmpeg/aria2c) may be reparented to init if not reaped.
                             process.destroyForcibly()
```

---

### 6.8 Patch 8: `DownloadsSettingsViewModel.kt`
- Adds reactive queue rescheduling when download delays are enabled or configured.

```diff
--- a/app/src/main/java/com/comfort/app/viewmodel/DownloadsSettingsViewModel.kt
+++ b/app/src/main/java/com/comfort/app/viewmodel/DownloadsSettingsViewModel.kt
@@ -40,4 +40,14 @@ class DownloadsSettingsViewModel(application: Application) : AndroidViewModel(a
         rescheduleQueue(delayMs = 250L)
     }
 
+    fun setDownloadDelayEnabled(enabled: Boolean) {
+        GalleryDlPreferences.setDownloadDelayEnabled(context, enabled)
+        rescheduleQueue(delayMs = 250L)
+    }
+
+    fun setDownloadDelaySeconds(seconds: Int) {
+        GalleryDlPreferences.setDownloadDelaySeconds(context, seconds)
+        rescheduleQueue(delayMs = 250L)
+    }
+
     fun setConcurrentDownloads(count: Int) {
```

---

## Section 7: Verification & Testing Methodology

### 7.1 Unit & Integration Test Strategy

Automated test scenarios covering the remediated behaviors:

#### 1. System Stop & Re-enqueue Test (`DownloadWorkerTest`)
- **Objective:** Verify that `DownloadWorker` returns `Result.retry()` and restores Room DB status to `QUEUED` when `isStopped` is signaled.
- **Setup:** Use `androidx.work.testing.TestWorkerBuilder` with an in-memory Room DB.
- **Execution:**
  1. Initialize worker with test URL.
  2. Start execution and trigger cancellation via `TestDriver.setStopReason()`.
  3. Assert worker returns `ListenableWorker.Result.retry()`.
  4. Assert Room DB status is `DownloadStatus.QUEUED`.
  5. Assert `DownloadNotifications` cancelled ongoing progress notification.

#### 2. Atomic Pause/Cancel Ordering & CAS Terminal Update Test
- **Objective:** Verify that calling `pauseDownload()` or `cancelDownload()` sets Room DB status before calling WorkManager cancellation, and verify terminal updates do not overwrite `PAUSED` or `CANCELLED`.
- **Execution:**
  1. Seed DB row with `status = RUNNING`.
  2. Mock `WorkManager.cancelWorkById()` with a 500ms delay simulating IPC wait.
  3. Invoke `DownloadDispatcher.pauseDownload()`.
  4. In a concurrent coroutine, query Room DB during the 500ms window: verify status is immediately `PAUSED`.
  5. In worker completion block, execute terminal CAS: verify CAS returns 0 and does not overwrite `PAUSED` with `FINISHED` or `ERRORED`.

#### 3. Concurrency Gate Acquisition & Release Verification
- **Objective:** Verify that cancellation during `DownloadConcurrencyGate.acquire()` returns `Result.retry()` and does not leak slots.
- **Execution:**
  1. Fill concurrency slots to capacity.
  2. Launch worker #3; verify it suspends at `acquire()`.
  3. Cancel worker #3 coroutine: verify `CancellationException` is caught and returns `Result.retry()`.
  4. Verify concurrency gate count reflects only active slots.
  5. Launch worker #4 with closed schedule window: verify gate is released before returning `Result.success()`.

#### 4. Schedule Window Millisecond Precision & Wrap Test
- **Objective:** Verify exact millisecond calculation and midnight-crossing logic.
- **Test Matrix:**
  * `start = 22:00, end = 06:00, now = 21:59:30` -> Delay equals `30,000 ms`.
  * `start = 22:00, end = 06:00, now = 23:30:00` -> Delay equals `0 ms` (inside window).
  * `start = 22:00, end = 06:00, now = 06:00:00` -> Delay is `> 0 ms` (window closed at 06:00:00 via half-open interval).
  * `start = 08:00, end = 08:00` -> `startMin == endMin`: delay is `0 ms` and no alarm is armed.

#### 5. Staging Directory Cleanup Synchronization Test
- **Objective:** Verify `saveHeldFiles()` completes MediaStore insertion before staging directory is purged.
- **Execution:**
  1. Create dummy media files in `cacheDir/gallery-dl-staging/test-id/`.
  2. Launch worker into synthetic `saveHeldFiles()` coroutine.
  3. Invoke `DownloadDispatcher.cancelDownload(context, "test-id")`.
  4. Assert no `FileNotFoundException` occurs and staging files remain intact until worker teardown exits.

---

### 7.2 Manual & Live Device Verification Protocol

1. **Network Constraint Interruption:**
   - Enqueue 5 downloads with "Wi-Fi Only" enabled.
   - Once Item 1 is `RUNNING` and Item 2 is `QUEUED`, disable Wi-Fi.
   - Observe WorkManager and Room DB. Item 1 transitions to `QUEUED` ("Waiting for network").
   - Re-enable Wi-Fi.
   - **Verification:** Download resumes automatically without user interaction or app restart. Concurrency counter does not drop to 0 permanently.
2. **Rapid Pause-All / Resume-All Stress Test:**
   - Enqueue 50 items.
   - Tap "Pause All" and immediately tap "Resume All" within 300ms.
   - **Verification:** No downloads are stranded in `QUEUED` without WorkRequests; no cards flicker between tabs.
3. **Overnight Schedule Simulation:**
   - Configure schedule window: Start `02:00`, End `04:00`.
   - Set device clock to `01:59:50`.
   - Observe alarm trigger at `02:00:00`. Downloads transition from `SCHEDULED` to `RUNNING`.
   - Fast-forward device clock to `04:00:01`.
   - **Verification:** Active download suspends gracefully back to `SCHEDULED`; queued items remain in `SCHEDULED` without starting.

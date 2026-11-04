# Comprehensive Code Review: Swipe-to-Delete Cards

**Date:** 2026-10-20  
**Scope:** Review and UI/gesture audit of the `SwipeToDeleteCard` implementation, gesture arbitration with `PageSwipe`, accessibility semantics, and call-site usages in the Comfort (`gallerydl`) Android application.

**Target Files Audited:**
- `app/src/main/java/com/comfort/app/ui/main/DownloadsHistoryScreen.kt` (definition at lines 1261–1320; History card usage at line 658; Duplicates usage at line 1365)
- `app/src/main/java/com/comfort/app/ui/main/QueueScreen.kt` (stopped Queue card usage at lines 620–632)
- `app/src/main/java/com/comfort/app/ui/main/PageSwipe.kt` (`pageSwipeEdges` interaction at lines 218–224)
- `app/src/main/java/com/comfort/app/ui/main/Components.kt` (`rememberUndoableDelete` interaction)

---

## 1. Executive Summary

`SwipeToDeleteCard` provides smooth haptic ticks and manages card reveal shape styling. However, the audit revealed **6 core architectural, visual, and gesture issues**:
1. **Permanent Background Overdraw & Subpixel Bleed:** `errorContainer` red box with `Icons.Outlined.Delete` renders continuously behind every resting card.
2. **Gesture Contention with Page Navigation:** `Modifier.pageSwipeEdges(zone = 80.dp)` steals touches across 40–45% of the total card width.
3. **Selection Mode Tree Churn:** Conditional `if (selectionMode) row() else SwipeToDeleteCard { row() }` tears down and reconstructs composable subtrees for every visible item.
4. **1-Frame Red Flash on Undo:** Restoring deleted cards via Snackbar Undo shows a 1-frame swiped-away red flash.
5. **Bi-directional Destruction:** Both swipe-left and swipe-right trigger deletion, conflicting with the Android system back gesture.
6. **Missing Accessibility Semantics:** TalkBack / switch access users cannot trigger deletion without drag gestures.

---

## 2. Key Findings

### Finding 1: Permanent Background Overdraw & Subpixel Bleed at Rest
- **Severity:** Medium-High (Visual & GPU Performance)
- **File:** `DownloadsHistoryScreen.kt:1305-1316`
- **Problem:**
  `SwipeToDismissBox` continuously renders `backgroundContent` (the red `errorContainer` Box and `Delete` icon) behind every single card even when completely idle (`currentValue == Settled`).
- **Impact:**
  - Extra GPU overdraw layer drawn behind every visible card in History, Duplicates, and Queue.
  - Sub-pixel anti-aliasing around curved corners (`RoundedCornerShape(20.dp)` and `LIBRARY_CARD_SHAPE`) can produce a faint reddish fringe or halo at rest.
  - At rest (`dismissDirection == Settled`), `contentAlignment` evaluates to `Alignment.CenterStart` (left), placing the trash can on the left even if the user peeks from the right.
- **Fix:** Only render/draw the red background and icon when `dismissState.targetValue != Settled || dismissState.currentValue != Settled`.

---

### Finding 2: Touch Competition with Horizontal Tab Navigation (`PageSwipe`)
- **Severity:** High (UX / Frustration)
- **Files:** `PageSwipe.kt:218-224`, `DownloadsHistoryScreen.kt:1304`
- **Problem:**
  `Modifier.pageSwipeEdges(pageSwipe, zone = 80.dp)` reserves the outer 80dp on *both* sides of the card for switching main tabs (Home ↔ Library ↔ Settings).
- **Impact:**
  On a standard 360–400dp mobile screen, reserving 80dp on the left and 80dp on the right consumes 40–45% of the total card width. When users attempt to swipe a card away with their right thumb, their touch lands in the right 80dp strip, accidentally navigating tabs instead of swiping the card.
- **Fix:** Narrow the `zone` width from 80dp to 48dp (matching standard touch target widths).

---

### Finding 3: Selection Mode Destroys & Reconstructs Composable Trees
- **Severity:** Medium (Compose Performance & Jitter)
- **Files:** `DownloadsHistoryScreen.kt:655-661`, `QueueScreen.kt:620-631`
- **Problem:**
  Both screens conditionally branch the composable tree:
  ```kotlin
  if (selectionMode) {
      row()
  } else {
      SwipeToDeleteCard(...) { row() }
  }
  ```
- **Impact:**
  Entering or exiting multi-select mode tears down and rebuilds the entire composable subtree for every visible item in the list, causing GC pressure, dropped frames, and resetting local UI states.
- **Fix:** Keep `SwipeToDeleteCard` in the hierarchy and pass `enabled: Boolean = !selectionMode` directly to `SwipeToDismissBox(gesturesEnabled = enabled)`.

---

### Finding 4: One-Frame Red Flash upon Undo
- **Severity:** Medium (Visual Polish)
- **File:** `DownloadsHistoryScreen.kt:1268-1282`
- **Problem:**
  `rememberSwipeToDismissBoxState` saves state via `rememberSaveable`. When an item is deleted, its state is saved as `EndToStart`. If the user taps "Undo" in the Snackbar, the item is restored with the same key. Because `LaunchedEffect(Unit) { dismissState.snapTo(Settled) }` executes after the initial draw frame, the restored item renders for 1 frame fully swiped away (showing the red background) before snapping back.
- **Fix:** Prevent saving the swiped-away state across disposals, or reset the state immediately upon re-entry.

---

### Finding 5: Bi-directional Destruction (`StartToEnd` vs `EndToStart`)
- **Severity:** Medium (User Error / Accidental Deletion)
- **File:** `DownloadsHistoryScreen.kt:1268-1276`
- **Problem:**
  Currently, both swiping right (`StartToEnd`) and swiping left (`EndToStart`) delete the item.
- **Impact:**
  Swiping right collides with Android's system predictive back gesture. Standard Material 3 UX reserves left-swipe (`EndToStart`) for destructive delete actions.
- **Fix:** Set `enableDismissFromStartToEnd = false` and `enableDismissFromEndToStart = true`.

---

### Finding 6: Missing Accessibility Action for TalkBack Users
- **Severity:** Low-Medium (A11y Compliance)
- **File:** `DownloadsHistoryScreen.kt:1302-1319`
- **Problem:**
  Users relying on TalkBack or switch access cannot perform horizontal drag gestures. The delete action is hidden behind the physical swipe gesture without an explicit semantic action.
- **Fix:** Add a `CustomAccessibilityAction("Remove")` to the card modifier so TalkBack users can trigger removal from the TalkBack local context menu.

---

## 3. Recommended Implementation

```kotlin
@Composable
internal fun SwipeToDeleteCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onDelete()
                true
            } else false
        }
    )

    // TalkBack Custom Action
    val accessibilityModifier = Modifier.semantics {
        if (enabled) {
            customActions = listOf(
                CustomAccessibilityAction("Remove") {
                    onDelete()
                    true
                }
            )
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.then(accessibilityModifier),
        gesturesEnabled = enabled,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            // Only draw background when actively moving/dismissing
            val isSwiping = dismissState.targetValue != SwipeToDismissBoxValue.Settled ||
                            dismissState.currentValue != SwipeToDismissBoxValue.Settled
            if (isSwiping) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
    ) {
        content()
    }
}
```

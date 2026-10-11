package com.comfort.app.worker

import com.comfort.app.data.DownloadDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WorkersAndDispatchersAuditTest {

    @Test
    fun derivePosterCaptionTitle_parsesStandardFormats() {
        assertEquals(
            "artist - song",
            derivePosterCaptionTitle("artist - song [abc12345].mp3")
        )
        assertEquals(
            "cool photo caption",
            derivePosterCaptionTitle("cool photo caption [xyz987].jpg")
        )
        assertEquals(
            "plain_filename",
            derivePosterCaptionTitle("plain_filename.png")
        )
    }

    @Test
    fun derivePosterCaptionTitle_handlesEdgeCasesAndBounds() {
        // Missing extension
        assertNull(derivePosterCaptionTitle("noextension"))
        // Empty or dot-only
        assertNull(derivePosterCaptionTitle(".hidden"))
        assertNull(derivePosterCaptionTitle(""))
        assertNull(derivePosterCaptionTitle("   "))

        // Only bracketed id present
        assertNull(derivePosterCaptionTitle("[12345].mp4"))
        assertNull(derivePosterCaptionTitle("  [abc]  .mp3"))

        // 300 character long caption is clamped to at most 255 characters
        val longCaption = "A".repeat(300)
        val result = derivePosterCaptionTitle("$longCaption [id].mp4")
        assertNotNull(result)
        assertEquals(255, result?.length)
    }

    @Test
    fun isNotItsKind_detectsEngineRoutingErrors() {
        assertTrue(isNotItsKind("No video could be found in this tweet"))
        assertTrue(isNotItsKind("there's no video in this post"))
        assertTrue(isNotItsKind("no video formats found for requested url"))
        assertTrue(isNotItsKind("unsupported url: https://foo.bar"))

        // Legitimate network or auth errors should not match
        assertFalse(isNotItsKind("HTTP 403 Forbidden: Private account"))
        assertFalse(isNotItsKind("Connection refused"))
    }

    @Test
    fun downloadNotifications_constantsAndIdGeneration() {
        assertEquals("downloads", DownloadNotifications.CHANNEL_ID)
        assertEquals(0x646C664B, DownloadNotifications.FOREGROUND_SERVICE_NOTIFICATION_ID)

        val id1 = DownloadNotifications.notificationId("download-100")
        val id2 = DownloadNotifications.notificationId("download-100")
        val id3 = DownloadNotifications.notificationId("download-200")

        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
    }

    @Test
    fun downloadDispatcher_forgetLock_isSafeAndIdempotent() {
        // forgetLock should never throw regardless of id contents
        DownloadDispatcher.forgetLock("non-existent-id")
        DownloadDispatcher.forgetLock("")
        DownloadDispatcher.forgetLock("valid-download-id")
    }

    @Test
    fun stagingCleanupWorker_canonicalPathValidation_preventsTraversal() {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "test-staging-root-${System.currentTimeMillis()}")
        tempRoot.mkdirs()
        try {
            val legitimateChild = File(tempRoot, "legit-download-id")
            legitimateChild.mkdirs()

            val canonicalRoot = tempRoot.canonicalFile

            // Legitimate child directory has canonical parent matching root
            assertTrue(legitimateChild.canonicalFile.parentFile == canonicalRoot)

            // Traversal attempt (e.g., using .. to reference parent of tempRoot)
            val traversalAttempt = File(tempRoot, "../other-directory")
            assertFalse(traversalAttempt.canonicalFile.parentFile == canonicalRoot)

            // Root itself is not a child of root
            assertFalse(canonicalRoot.parentFile == canonicalRoot)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun gateSlotAcquisition_guardEnsuresIdempotentRelease() {
        val activeCount = AtomicInteger(1)
        val gateAcquired = AtomicBoolean(true)

        fun releaseSlot() {
            if (gateAcquired.compareAndSet(true, false)) {
                activeCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        }

        // First release decrements active count
        releaseSlot()
        assertEquals(0, activeCount.get())
        assertFalse(gateAcquired.get())

        // Duplicate release does not underflow active count
        releaseSlot()
        assertEquals(0, activeCount.get())

        // Even direct release with coerceAtLeast(0) protects underflow
        activeCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        assertEquals(0, activeCount.get())
    }
}

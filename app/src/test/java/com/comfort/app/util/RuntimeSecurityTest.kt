package com.comfort.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun unzipStreamTo_extractsValidEntriesSafely() {
        val destDir = tempFolder.newFolder("extract_dest")
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("sub/test.txt"))
            zos.write("hello world".toByteArray())
            zos.closeEntry()
        }

        PythonRuntime.unzipStreamTo(ByteArrayInputStream(baos.toByteArray()), destDir)

        val extracted = File(destDir, "sub/test.txt")
        assertTrue("Extracted file should exist", extracted.exists())
        assertEquals("hello world", extracted.readText())
    }

    @Test
    fun unzipStreamTo_rejectsZipSlipPathTraversal() {
        val destDir = tempFolder.newFolder("extract_safe")
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../traversal.txt"))
            zos.write("malicious payload".toByteArray())
            zos.closeEntry()
        }

        try {
            PythonRuntime.unzipStreamTo(ByteArrayInputStream(baos.toByteArray()), destDir)
            fail("Expected SecurityException on zip slip traversal attempt")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Zip entry is outside") == true)
        }
    }
}

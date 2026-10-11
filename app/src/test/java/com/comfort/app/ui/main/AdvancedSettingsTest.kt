package com.comfort.app.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsTest {

    @Test
    fun testValidateCustomHeaders() {
        // Valid headers
        assertNull(validateCustomHeaders("User-Agent: Mozilla/5.0"))
        assertNull(validateCustomHeaders("Authorization: Bearer token123\nAccept: */*"))
        assertNull(validateCustomHeaders(""))
        assertNull(validateCustomHeaders("   \n  \n"))

        // Invalid: missing colon
        assertNotNull(validateCustomHeaders("User-Agent Mozilla/5.0"))

        // Invalid: missing header name
        assertNotNull(validateCustomHeaders(": Mozilla/5.0"))

        // Invalid: space in header name
        assertNotNull(validateCustomHeaders("User Agent: Mozilla/5.0"))
    }

    @Test
    fun testIsValidFormatSort() {
        // Valid format sorts
        assertTrue(isValidFormatSort("res,fps"))
        assertTrue(isValidFormatSort("res:1080,vcodec:vp9"))
        assertTrue(isValidFormatSort("+size,-br"))
        assertTrue(isValidFormatSort(""))

        // Invalid: dangerous shell characters or quotes
        assertFalse(isValidFormatSort("res; rm -rf /"))
        assertFalse(isValidFormatSort("res && cat /etc/passwd"))
        assertFalse(isValidFormatSort("res | echo 1"))
        assertFalse(isValidFormatSort("res\""))
        assertFalse(isValidFormatSort("res'"))
        assertFalse(isValidFormatSort("res`id`"))
        assertFalse(isValidFormatSort("res\$VAR"))
        assertFalse(isValidFormatSort("res\nfps"))
    }

    @Test
    fun testHasUnmatchedQuotes() {
        // Balanced
        assertFalse(hasUnmatchedQuotes("--write-thumbnail"))
        assertFalse(hasUnmatchedQuotes("--extractor-args \"youtube:player_client=web\""))
        assertFalse(hasUnmatchedQuotes("--header 'Accept: application/json'"))
        assertFalse(hasUnmatchedQuotes("\"foo\" 'bar' \"baz 'qux'\""))
        assertFalse(hasUnmatchedQuotes("\\\" escaped"))

        // Unbalanced double quotes
        assertTrue(hasUnmatchedQuotes("--extractor-args \"youtube:player_client=web"))
        assertTrue(hasUnmatchedQuotes("\""))
        assertTrue(hasUnmatchedQuotes("foo \" bar \" baz \""))

        // Unbalanced single quotes
        assertTrue(hasUnmatchedQuotes("--header 'Accept: application/json"))
        assertTrue(hasUnmatchedQuotes("'"))
        assertTrue(hasUnmatchedQuotes("foo ' bar ' baz '"))
    }
}

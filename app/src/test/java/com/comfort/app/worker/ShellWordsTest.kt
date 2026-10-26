package com.comfort.app.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ShellWords splits as the wrappers' shlex.split does: every expectation here is what Python's
 * shlex.split returned for the same text (or a ValueError, here null). */
class ShellWordsTest {

    @Test
    fun splitsLikeShlex() {
        val cases = mapOf(
            "--a b" to listOf("--a", "b"),
            "--ua \"Mozilla 5\"" to listOf("--ua", "Mozilla 5"),
            "-o 'it''s'" to listOf("-o", "its"),
            "a\\ b" to listOf("a b"),
            "\"a\\\"b\"" to listOf("a\"b"),
            // In double quotes a backslash before anything but " or \ stays.
            "\"a\\nb\"" to listOf("a\\nb"),
            // In single quotes it's literal.
            "'a\\'" to listOf("a\\"),
            "x\\\\y" to listOf("x\\y"),
            "--filter \"title contains ' -p '\"" to listOf("--filter", "title contains ' -p '"),
            "  spaced   out " to listOf("spaced", "out"),
            "\"\"" to listOf(""),
            "''" to listOf(""),
            "-o \"%(title)s.%(ext)s\"" to listOf("-o", "%(title)s.%(ext)s"),
        )
        cases.forEach { (text, words) -> assertEquals(text, words, ShellWords.split(text)) }
    }

    @Test
    fun failsWhereShlexRaises() {
        listOf("--ua \"Mozilla", "it's", "foo\\", "\"a\\\"").forEach { assertNull(it, ShellWords.split(it)) }
    }
}

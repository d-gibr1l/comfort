package com.comfort.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieStoreTest {
    private fun row(domain: String, name: String, value: String, expiry: Long = 2_000_000_000L) =
        "$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"

    private val saved = listOf(
        CookieStore.HEADER,
        row(".example.com", "session", "aaa"),
        row(".example.com", "csrf", "bbb"),
        row(".other.org", "id", "ccc"),
    ).joinToString("\n")

    @Test
    fun `parse skips comments and blank lines but keeps HttpOnly rows`() {
        val text = "# Netscape HTTP Cookie File\n\n# a comment\n#HttpOnly_" + row(".example.com", "sid", "x")
        val cookies = parseCookiesFile(text)
        assertEquals(1, cookies.size)
        assertEquals("sid", cookies[0].name)
        assertEquals(".example.com", cookies[0].domain)
        assertTrue(cookies[0].rawLine.startsWith("#HttpOnly_"))
    }

    @Test
    fun `parse rebuilds tabs when an export's tabs became spaces`() {
        val cookies = parseCookiesFile(".example.com  TRUE  /  TRUE  2000000000  sid  value with spaces")
        assertEquals(1, cookies.size)
        assertEquals("value with spaces", cookies[0].value)
        assertEquals(7, cookies[0].rawLine.split("\t").size)
    }

    @Test
    fun `mergePasted replaces only the pasted site and keeps the header`() {
        val merged = CookieStore.mergePasted(saved, row(".example.com", "session", "new"))!!
        assertTrue(merged.startsWith(CookieStore.HEADER + "\n"))
        val cookies = parseCookiesFile(merged)
        assertEquals(listOf("id", "session"), cookies.map { it.name }.sorted())
        assertEquals("new", cookies.single { it.name == "session" }.value)
    }

    @Test
    fun `mergePasted refuses text with no valid rows`() {
        assertNull(CookieStore.mergePasted(saved, "session=aaa; csrf=bbb"))
    }

    @Test
    fun `withValues changes only the edited value`() {
        val target = parseCookiesFile(saved).single { it.name == "csrf" }
        val updated = parseCookiesFile(CookieStore.withValues(saved, mapOf(target to "edited\twith\ttabs")))
        assertEquals("editedwithtabs", updated.single { it.name == "csrf" }.value)
        assertEquals("aaa", updated.single { it.name == "session" }.value)
        assertEquals(3, updated.size)
    }

    @Test
    fun `without drops exactly the given cookies`() {
        val site = groupCookiesBySite(parseCookiesFile(saved)).single { it.rootDomain == "example.com" }
        val left = CookieStore.without(saved, site.cookies.toSet())
        assertTrue(left.startsWith(CookieStore.HEADER))
        assertEquals(listOf("id"), parseCookiesFile(left).map { it.name })
    }

    @Test
    fun `groups subdomains under one registrable domain`() {
        val cookies = parseCookiesFile(
            listOf(row("www.example.com", "a", "1"), row(".example.com", "b", "2"), row(".other.org", "c", "3")).joinToString("\n")
        )
        val sites = groupCookiesBySite(cookies)
        assertEquals(listOf("example.com", "other.org"), sites.map { it.rootDomain })
        assertEquals(2, sites[0].cookies.size)
        assertEquals("Example", sites[0].label)
    }

    @Test
    fun `soonest expiry ignores session cookies`() {
        val site = SiteCookies("Example", "example.com", parseCookiesFile(
            listOf(row(".example.com", "a", "1", expiry = 0), row(".example.com", "b", "2", expiry = 1_900_000_000)).joinToString("\n")
        ))
        assertEquals(1_900_000_000L, site.soonestExpiryEpochSeconds)
    }

    @Test
    fun `browser merge replaces that host's lines and adds a header once`() {
        val merged = mergeNetscapeCookies(saved, "example.com", "session=fresh; theme=dark")
        val cookies = parseCookiesFile(merged)
        assertEquals(listOf("id", "session", "theme"), cookies.map { it.name }.sorted())
        assertEquals("fresh", cookies.single { it.name == "session" }.value)
        assertEquals(1, merged.lines().count { it.startsWith("# Netscape") })
        assertFalse(cookies.any { it.name == "csrf" })
    }

    @Test
    fun `browser merge drops previous HttpOnly rows for that host`() {
        val withHttpOnly = listOf(
            CookieStore.HEADER,
            "#HttpOnly_" + row(".example.com", "session", "old"),
            row(".other.org", "id", "ccc"),
        ).joinToString("\n")
        val merged = mergeNetscapeCookies(withHttpOnly, "example.com", "session=fresh")
        val cookies = parseCookiesFile(merged)
        assertEquals(listOf("id", "session"), cookies.map { it.name }.sorted())
        assertEquals("fresh", cookies.single { it.name == "session" }.value)
        assertFalse(merged.contains("old"))
    }

    @Test
    fun `browser merge strips leading www from host so cookies apply to apex domain`() {
        val existing = listOf(
            CookieStore.HEADER,
            row("www.example.com", "session", "old"),
            row(".example.com", "csrf", "old_csrf"),
            row(".other.org", "id", "ccc"),
        ).joinToString("\n")
        val merged = mergeNetscapeCookies(existing, "www.example.com", "session=fresh; theme=dark")
        val cookies = parseCookiesFile(merged)
        assertEquals(listOf("id", "session", "theme"), cookies.map { it.name }.sorted())
        val sessionCookie = cookies.single { it.name == "session" }
        assertEquals("fresh", sessionCookie.value)
        assertEquals(".example.com", sessionCookie.domain)
        assertFalse(merged.contains("www.example.com"))
        assertFalse(merged.contains("old_csrf"))
    }
}

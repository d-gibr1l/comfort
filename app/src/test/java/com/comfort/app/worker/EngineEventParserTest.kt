package com.comfort.app.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineEventParserTest {
    private fun structured(json: String) = EngineEventParser.MARK + json
    private fun parse(line: String) = EngineEventParser.parse(line)

    // --- Structured reports (comfort_events.py) ---

    @Test
    fun structuredReports() {
        assertEquals(EngineEvent.Size(1234), parse(structured("""{"type":"size","bytes":1234}""")))
        assertEquals(EngineEvent.Size(6897840), parse(structured("""{"type":"size","bytes":6897840.0}""")))
        assertEquals(EngineEvent.Total(8), parse(structured("""{"type":"total","count":8}""")))
        assertEquals(EngineEvent.Phase(isAudio = true), parse(structured("""{"type":"phase","phase":"audio"}""")))
        assertEquals(EngineEvent.Phase(isAudio = false), parse(structured("""{"type":"phase","phase":"video"}""")))
        assertEquals(EngineEvent.Format("720p|MP4"), parse(structured("""{"type":"format","tags":"720p|MP4"}""")))
        assertEquals(EngineEvent.Progress(1024, 2048.5f), parse(structured("""{"type":"progress","downloaded":1024,"speed":2048.5}""")))
        assertEquals(EngineEvent.Progress(1024, 0f), parse(structured("""{"type":"progress","downloaded":1024,"speed":null}""")))
        assertEquals(EngineEvent.Thumbnail("https://x/t.jpg"), parse(structured("""{"type":"thumbnail","url":"https://x/t.jpg"}""")))
        assertEquals(EngineEvent.Artist("GROUPLOVE"), parse(structured("""{"type":"artist","artist":"GROUPLOVE"}""")))
        assertEquals(EngineEvent.Album("Never Trust a Happy Song"), parse(structured("""{"type":"album","album":"Never Trust a Happy Song"}""")))
        assertEquals(EngineEvent.Track("Colours"), parse(structured("""{"type":"track","track":"Colours"}""")))
        assertEquals(EngineEvent.File("/data/x/a [1].mp4"), parse(structured("""{"type":"file","path":"/data/x/a [1].mp4"}""")))
        assertEquals(EngineEvent.Status("Fetching info…"), parse(structured("""{"type":"status","message":"Fetching info…"}""")))
    }

    @Test
    fun structuredTitleKeepsTextThatLooksLikeMarkup() {
        // A title text protocol couldn't carry: a newline and a bracketed prefix.
        assertEquals(EngineEvent.Title("[error] not an error\nsecond line"), parse(structured("""{"type":"title","title":"[error] not an error\nsecond line"}""")))
    }

    @Test
    fun structuredErrorKeepsOnlyTheHeadlineOfATraceback() {
        // As comfort_events.py writes it: the traceback's newlines and quotes JSON-escaped.
        val json = """{"type":"error","message":"ERROR: unable to download video data: HTTP Error 403: Forbidden\nTraceback (most recent call last):\n  File \"x.py\", line 1"}"""
        assertEquals(EngineEvent.Error("ERROR: unable to download video data: HTTP Error 403: Forbidden"), parse(structured(json)))
    }

    @Test
    fun structuredTracebackOnItsOwnIsNoise() {
        val json = """{"type":"error","message":"Traceback (most recent call last):
  File \"YoutubeDL.py\", line 3666, in process_info"}"""
        assertEquals(EngineEvent.Ignored, parse(structured(json)))
    }

    @Test
    fun structuredErrorWithoutErrorPrefixIsStillAnError() {
        // Spotify's own errors don't start with "ERROR:" — as plain text they were dropped as noise.
        assertEquals(EngineEvent.Error("No tracks found at this Spotify link"), parse(structured("""{"type":"error","message":"No tracks found at this Spotify link"}""")))
    }

    @Test
    fun structuredReportsToIgnore() {
        for (json in listOf(
            """{"type":"warning","message":"x"}""", """{"type":"debug","message":"x"}""", """{"type":"exit","status":"Done"}""",
            """{"type":"something-new","value":1}""", """{"type":"size","bytes":0}""", """{"type":"title","title":"  "}""",
            """not json at all""", """[1,2,3]""",
        )) {
            assertEquals(json, EngineEvent.Ignored, parse(structured(json)))
        }
    }

    // --- Plain text: the engines' own loggers, and older wrapper scripts ---

    @Test
    fun legacyWrapperLines() {
        assertEquals(EngineEvent.Size(6897840), parse("[size] 6897840.0"))
        assertEquals(EngineEvent.Total(3), parse("[total] 3"))
        assertEquals(EngineEvent.Phase(isAudio = true), parse("[phase] audio"))
        assertEquals(EngineEvent.Format("720p|MP4"), parse("[format] 720p|MP4"))
        assertEquals(EngineEvent.Progress(500, 1.5f), parse("[progress] downloaded=500 speed=1.5"))
        assertEquals(EngineEvent.Thumbnail("https://x/t.jpg"), parse("[thumbnail] https://x/t.jpg"))
        assertEquals(EngineEvent.Title("A title"), parse("[title] A title"))
        assertEquals(EngineEvent.Status("Fetching info…"), parse("[status] Fetching info…"))
    }

    @Test
    fun ytDlpErrorAnnouncementVersusTracebackNoise() {
        assertEquals(EngineEvent.Error("ERROR: [instagram] x: Requested content is not available"), parse("[error] ERROR: [instagram] x: Requested content is not available"))
        assertEquals(EngineEvent.Ignored, parse("[error]   File \"/x/yt_dlp/extractor/common.py\", line 742, in extract"))
        assertEquals(EngineEvent.Ignored, parse("[error] ie_result = self._real_extract(url)"))
    }

    @Test
    fun galleryDlLoggerLines() {
        assertEquals(EngineEvent.Error("HTTP redirect to login page (https://www.instagram.com/accounts/login/)"),
            parse("[instagram][error] HTTP redirect to login page (https://www.instagram.com/accounts/login/)"))
        assertEquals(EngineEvent.NoResults, parse("[twitter][info] No results for https://x.com/a/status/1"))
        assertEquals(EngineEvent.Ignored, parse("[instagram][warning] Unable to fetch media info"))
        assertEquals(EngineEvent.Error("Error, exited with code 1"), parse("Error, exited with code 1"))
        assertEquals(EngineEvent.Error("Exception: boom"), parse("Exception: boom"))
    }

    @Test
    fun bareFilePathAndOtherText() {
        assertEquals(EngineEvent.File("/data/user/0/com.comfort.app/cache/staging/a.jpg"), parse("/data/user/0/com.comfort.app/cache/staging/a.jpg  "))
        assertEquals(EngineEvent.Ignored, parse("[warning] something"))
        assertEquals(EngineEvent.Ignored, parse("[__status__] Done"))
        assertEquals(EngineEvent.Ignored, parse("[download] Destination: a.mp4"))
        assertEquals(EngineEvent.Ignored, parse("just some text"))
        assertEquals(EngineEvent.Ignored, parse(""))
    }
}

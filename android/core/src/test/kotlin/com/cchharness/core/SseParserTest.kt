package com.cchharness.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SseParserTest {
    private fun feedAll(p: SseParser, text: String) =
        text.split("\n").mapNotNull { p.feed(it) }

    @Test
    fun parsesEventWithIdAndData() {
        val evs = feedAll(
            SseParser(),
            "retry: 2000\n\nid: 7\ndata: {\"seq\":7,\"ts\":99,\"data\":{\"type\":\"harness\",\"kind\":\"status\"}}\n\n",
        )
        assertEquals(1, evs.size)
        assertEquals(7, evs[0].seq)
        assertEquals(99, evs[0].ts)
        assertEquals("status", evs[0].data.str("kind"))
    }

    @Test
    fun ignoresCommentsAndBlankLinesWithoutData() {
        val p = SseParser()
        assertNull(p.feed(": ping"))
        assertNull(p.feed(""))
        assertNull(p.feed(""))
    }

    @Test
    fun liveEventsHaveSeqZero() {
        val p = SseParser()
        p.feed("data: {\"seq\":0,\"ts\":1,\"data\":{\"type\":\"stream_event\"}}")
        assertEquals(0, assertNotNull(p.feed("")).seq)
    }

    @Test
    fun malformedPayloadIsSkippedNotFatal() {
        val p = SseParser()
        p.feed("data: {not json")
        assertNull(p.feed(""))
        p.feed("data: {\"seq\":1,\"ts\":1,\"data\":{\"type\":\"x\"}}")
        assertEquals(1, assertNotNull(p.feed("")).seq)
    }

    @Test
    fun multiLineDataIsJoined() {
        val p = SseParser()
        p.feed("data: {\"seq\":2,")
        p.feed("data: \"ts\":1,\"data\":{}}")
        assertEquals(2, assertNotNull(p.feed("")).seq)
    }

    @Test
    fun dataWithoutSpaceAfterColon() {
        val p = SseParser()
        p.feed("data:{\"seq\":3,\"ts\":1,\"data\":{}}")
        assertEquals(3, assertNotNull(p.feed("")).seq)
    }
}

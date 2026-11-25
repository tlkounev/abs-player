package cz.ondra.absplayer.player

import cz.ondra.absplayer.data.ListeningSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private val ch = listOf(ChapterInfo("1", 0.0, 100.0), ChapterInfo("2", 100.0, 300.0), ChapterInfo("3", 300.0, 900.0))

    private fun s(
        id: String, start: Double, end: Double, updatedAt: Long,
        deviceId: String? = null, clientName: String? = null, deviceName: String? = null,
        model: String? = null, browserName: String? = null,
    ) = ListeningSession(id, start, end, updatedAt - 1000, updatedAt, deviceId, clientName, deviceName, model, browserName)

    @Test
    fun chapterAtAPosition() {
        assertEquals(0, History.chapterAt(ch, 0.0))
        assertEquals(1, History.chapterAt(ch, 150.0))
        assertEquals(2, History.chapterAt(ch, 899.0))
        assertNull(History.chapterAt(emptyList(), 10.0))
    }

    @Test
    fun newestFirstEmptyLeftOutNowAndFurthest() {
        // the TV kept an old place (410), the phone got further (800)
        val rows = History.build(
            listOf(s("old", 0.0, 120.0, 1000), s("tv", 400.0, 410.0, 5000), s("phone", 300.0, 800.0, 3000), s("empty", 50.0, 50.5, 9000)),
            ch, emptyMap(), 410.0,
        )
        assertEquals(listOf("tv", "phone", "old"), rows.map { it.id })
        assertTrue(rows[0].now && !rows[0].furthest)
        assertTrue(rows[1].furthest && !rows[1].now)
        assertEquals(2, rows[1].endChapter)
        assertEquals(500.0, rows[1].endInChapterSec, 0.0)
        assertEquals(2, rows[1].startChapter)
    }

    @Test
    fun noFurthestMarkWhenWeAreTheFurthest() {
        val rows = History.build(listOf(s("a", 0.0, 500.0, 2000), s("b", 0.0, 200.0, 1000)), ch, emptyMap(), 500.0)
        assertTrue(rows[0].now)
        assertFalse(rows.any { it.furthest })
    }

    @Test
    fun deviceNamesKnownIdsThenTheServers() {
        val known = mapOf("tv1" to KnownDevice("Obývák TV", DeviceKind.TV))
        assertEquals(KnownDevice("Obývák TV", DeviceKind.TV), History.label(s("x", 0.0, 1.0, 1, deviceId = "tv1", deviceName = "Philips"), known))
        assertEquals(
            KnownDevice("Windows 10 Chrome", DeviceKind.WEB),
            History.label(s("x", 0.0, 1.0, 1, clientName = "Abs Web", deviceName = "Windows 10 Chrome", browserName = "Chrome"), known),
        )
        assertEquals(KnownDevice("SM-X910", DeviceKind.PHONE), History.label(s("x", 0.0, 1.0, 1, deviceName = "Unknown ", model = "SM-X910"), known))
    }

    @Test
    fun tolerantParseOfTheServerPage() {
        val page = Json.parseToJsonElement(
            """{"total":4,"sessions":[
              {"id":"a","startTime":"12.5","currentTime":99,"updatedAt":1700000000000,"deviceInfo":{"deviceId":"d","deviceName":" Google Pixel 8 "}},
              {"id":"b"}, null, {"currentTime":3}]}""",
        )
        val list = ListeningSession.listFrom(page)
        assertEquals(1, list.size)
        assertEquals(12.5, list[0].startSec, 0.0)
        assertEquals("Google Pixel 8", list[0].deviceName)
        assertEquals(1700000000000L, list[0].updatedAt)
        assertEquals(0, ListeningSession.listFrom(Json.parseToJsonElement("[]")).size)
    }
}

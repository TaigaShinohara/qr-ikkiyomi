package app.qrikkiyomi

import app.qrikkiyomi.core.CodeKind
import app.qrikkiyomi.core.Entry
import app.qrikkiyomi.core.Origin
import app.qrikkiyomi.core.entryFromJson
import app.qrikkiyomi.core.pruneAgedScans
import app.qrikkiyomi.core.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R2-11 (favourites exempt from auto-deletion) and P2-7 (drawing
 * settings stored per entry, backward compatible with pre-v0.4.0 JSON).
 */
class StorePruneTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun entry(
        origin: Origin,
        ageDays: Long,
        favorite: Boolean = false,
    ) = Entry(
        rawValue = "https://example.com",
        kind = CodeKind.URL,
        formatName = "QR_CODE",
        origin = origin,
        createdAt = now - ageDays * day,
        favorite = favorite,
    )

    @Test
    fun prune_removes_only_old_unfavourited_scans() {
        val oldScan = entry(Origin.SCANNED, 40)
        val oldFavourite = entry(Origin.SCANNED, 40, favorite = true)
        val oldCreated = entry(Origin.CREATED, 40)
        val freshScan = entry(Origin.SCANNED, 5)
        val kept = pruneAgedScans(listOf(oldScan, oldFavourite, oldCreated, freshScan), 30, now)
        assertEquals(listOf(oldFavourite, oldCreated, freshScan), kept)
    }

    @Test
    fun prune_disabled_keeps_everything() {
        val all = listOf(entry(Origin.SCANNED, 400), entry(Origin.CREATED, 400))
        assertEquals(all, pruneAgedScans(all, 0, now))
    }

    @Test
    fun prune_boundary_exactly_at_cutoff_is_kept() {
        val boundary = entry(Origin.SCANNED, 30)
        assertEquals(listOf(boundary), pruneAgedScans(listOf(boundary), 30, now))
    }

    @Test
    fun entry_json_roundtrip_keeps_drawing_settings() {
        val e = Entry(
            rawValue = "https://example.com",
            kind = CodeKind.URL,
            formatName = "QR_CODE",
            origin = Origin.CREATED,
            createType = app.qrikkiyomi.core.CreateType.URL,
            fields = mapOf("url" to "example.com"),
            ecLevel = "H",
            pngSize = 512,
            qrMargin = 4,
        )
        val back = entryFromJson(JSONObject(e.toJson().toString()))
        assertEquals(e.id, back.id)
        assertEquals("H", back.ecLevel)
        assertEquals(512, back.pngSize)
        assertEquals(4, back.qrMargin)
    }

    @Test
    fun entry_json_legacy_without_drawing_settings_reads_nulls() {
        // A v0.3.1-era record: no ecLevel/pngSize/qrMargin keys at all.
        val legacy = JSONObject(
            """
            {"id":"abc","rawValue":"https://example.com","kind":"URL",
             "formatName":"QR_CODE","origin":"CREATED","memo":"","folder":"",
             "favorite":false,"createdAt":1700000000000,
             "createType":"URL","fields":{"url":"example.com"}}
            """.trimIndent()
        )
        val back = entryFromJson(legacy)
        assertNull(back.ecLevel)
        assertNull(back.pngSize)
        assertNull(back.qrMargin)
        assertEquals("https://example.com", back.rawValue)
    }
}

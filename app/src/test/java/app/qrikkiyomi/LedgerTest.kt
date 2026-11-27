package app.qrikkiyomi

import app.qrikkiyomi.core.CodeKind
import app.qrikkiyomi.core.LedgerAddOutcome
import app.qrikkiyomi.core.LedgerCsv
import app.qrikkiyomi.core.LedgerEntry
import app.qrikkiyomi.core.LedgerSession
import app.qrikkiyomi.core.defaultLedgerName
import app.qrikkiyomi.core.hasDefaultName
import app.qrikkiyomi.core.ledgerSessionFromJson
import app.qrikkiyomi.core.toJson
import app.qrikkiyomi.core.withScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 (§2.3): the ledger's pure rules — duplicates are kept as
 * rows and marked, the CSV is a faithful scan-order record with a
 * BOM, and sessions survive their JSON round-trip (an interrupted
 * session must lose nothing).
 */
class LedgerTest {

    private val t0 = 1_800_000_000_000L

    private fun entry(raw: String, at: Long, id: String = raw + "@" + at) = LedgerEntry(
        id = id,
        rawValue = raw,
        formatName = if (raw.startsWith("490")) "EAN_13" else "QR_CODE",
        kind = if (raw.startsWith("http")) CodeKind.URL else CodeKind.TEXT,
        scannedAt = at,
    )

    private fun session(entries: List<LedgerEntry> = emptyList()) = LedgerSession(
        id = "s1",
        name = "テスト台帳",
        startedAt = t0,
        entries = entries,
    )

    @Test
    fun withScan_firstReadIsAdded() {
        val (s, outcome) = session().withScan(entry("https://example.com", t0))
        assertEquals(LedgerAddOutcome.ADDED, outcome)
        assertEquals(1, s.entries.size)
    }

    @Test
    fun withScan_duplicateIsKeptAsItsOwnRowAndReported() {
        val first = session().withScan(entry("4901234567890", t0)).first
        val (s, outcome) = first.withScan(entry("4901234567890", t0 + 1000))
        // The row is kept (inventory counts scans) AND reported as a
        // duplicate (the reception desk's "already read" signal).
        assertEquals(LedgerAddOutcome.DUPLICATE, outcome)
        assertEquals(2, s.entries.size)
    }

    @Test
    fun duplicateRaws_and_isRepeat_markSecondOccurrenceOnwards() {
        val a1 = entry("A", t0, "a1")
        val b1 = entry("B", t0 + 1, "b1")
        val a2 = entry("A", t0 + 2, "a2")
        val s = session(listOf(a1, b1, a2))
        assertEquals(setOf("A"), s.duplicateRaws())
        assertFalse(s.isRepeat(a1))
        assertFalse(s.isRepeat(b1))
        assertTrue(s.isRepeat(a2))
    }

    @Test
    fun csv_hasBomHeaderAndScanOrderRows() {
        val s = session(
            listOf(
                entry("https://example.com", t0, "e1"),
                entry("4901234567890", t0 + 60_000, "e2"),
            )
        )
        val csv = LedgerCsv.build(s)
        assertTrue(csv.startsWith("\uFEFF"))
        val lines = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(LedgerCsv.HEADER, lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].endsWith("https://example.com,"))
        assertTrue(lines[2].contains("EAN_13"))
    }

    @Test
    fun csv_marksOnlyRepeatRowsAsDuplicate() {
        val s = session(
            listOf(
                entry("A", t0, "a1"),
                entry("B", t0 + 1, "b1"),
                entry("A", t0 + 2, "a2"),
            )
        )
        val lines = LedgerCsv.build(s).removePrefix("\uFEFF")
            .split("\r\n").filter { it.isNotEmpty() }
        assertTrue(lines[1].endsWith(",A,"))
        assertTrue(lines[2].endsWith(",B,"))
        assertTrue(lines[3].endsWith(",A,重複"))
    }

    @Test
    fun csv_escapesCommasQuotesAndNewlines() {
        val nasty = "he said \"hi, there\"\nbye"
        val s = session(listOf(entry(nasty, t0, "n1")))
        val csv = LedgerCsv.build(s)
        assertTrue(csv.contains("\"he said \"\"hi, there\"\"\nbye\""))
    }

    @Test
    fun session_jsonRoundTrip_keepsEntriesAndOpenState() {
        val s = session(listOf(entry("A", t0, "a1"), entry("B", t0 + 5, "b1")))
        val back = ledgerSessionFromJson(s.toJson())
        assertEquals(s, back)
        assertTrue(back.isOpen)
    }

    @Test
    fun session_jsonRoundTrip_keepsEndedAt() {
        val s = session().copy(endedAt = t0 + 3600_000)
        val back = ledgerSessionFromJson(s.toJson())
        assertEquals(t0 + 3600_000, back.endedAt)
        assertFalse(back.isOpen)
    }

    @Test
    fun defaultName_readsAsDateTimeLedger() {
        // 2026-11-27 14:52 JST (the test JVM is pinned to Asia/Tokyo).
        val name = defaultLedgerName(1_798_000_000_000L)
        assertTrue(name.endsWith(" の台帳"))
        assertTrue(name.contains(":"))
    }

    @Test
    fun hasDefaultName_trueOnlyWhileTheAutomaticNameIsKept() {
        // v0.5.1 (MINIMAL round 3, N3-3): the list/detail meta lines
        // drop their "開始" segment only for sessions whose name still
        // IS the start timestamp.
        val auto = LedgerSession(
            id = "s2",
            name = defaultLedgerName(t0),
            startedAt = t0,
        )
        assertTrue(auto.hasDefaultName())
        assertFalse(session().hasDefaultName())
        assertFalse(auto.copy(name = defaultLedgerName(t0 + 60_000L)).hasDefaultName())
    }
}

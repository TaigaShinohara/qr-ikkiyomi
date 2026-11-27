package app.qrikkiyomi.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Phase 2 (v0.5.0): the continuous-scan ledger （台帳） — spec §2.3.
 *
 * A session is one stretch of on-site work (a reception desk, an
 * inventory sweep, a merch table): it starts, codes are read one
 * after another without any result sheet in between, and it ends as
 * one reviewable, CSV-exportable record.
 *
 * Decisions taken where the spec was silent (recorded in
 * docs/PHASE2_2026-11-27.md):
 * - A duplicate read is KEPT as its own row (an inventory count is a
 *   count of scans — the same EAN on ten items is ten rows), and is
 *   made to stand out: the scan itself warns (vibration + banner,
 *   §2.3) and every row whose content occurs more than once carries
 *   a 重複 marker in the list and in the CSV. At a reception desk the
 *   warning is the "already checked in" signal, and the row can be
 *   removed on the spot （直近の取り消し／個別削除）.
 * - Ledger scans are NOT written to the library history （宝箱）.
 *   The ledger is the record of the work; flooding the personal
 *   history with hundreds of work scans is exactly the "宝箱が汚れる"
 *   failure the history rules were written against, and keeping one
 *   home per scan means nothing is saved twice and nothing is lost.
 *   The ledger itself is persisted on every scan, so an app restart
 *   mid-shift loses nothing — an unfinished session simply waits in
 *   the ledger list to be resumed or closed.
 */

/** One read inside a [LedgerSession], in scan order. */
data class LedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val rawValue: String,
    val formatName: String,
    val kind: CodeKind,
    val scannedAt: Long = System.currentTimeMillis(),
)

/** One stretch of continuous scanning, start to finish. */
data class LedgerSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startedAt: Long,
    /** Null while the session is still open (in progress or interrupted). */
    val endedAt: Long? = null,
    val entries: List<LedgerEntry> = emptyList(),
) {
    val isOpen: Boolean get() = endedAt == null

    /** True when [rawValue] already has a row in this session. */
    fun contains(rawValue: String): Boolean = entries.any { it.rawValue == rawValue }

    /** Contents that occur more than once — the rows that must stand out. */
    fun duplicateRaws(): Set<String> =
        entries.groupingBy { it.rawValue }.eachCount()
            .filterValues { it > 1 }.keys

    /**
     * True when an earlier row (in scan order) already holds the same
     * content — i.e. this row is a 2nd-or-later occurrence. This is
     * the per-row 重複 marker used by the list and the CSV.
     */
    fun isRepeat(entry: LedgerEntry): Boolean {
        val idx = entries.indexOfFirst { it.id == entry.id }
        if (idx <= 0) return false
        return entries.subList(0, idx).any { it.rawValue == entry.rawValue }
    }
}

/** The outcome of feeding one freshly-read code into a session. */
enum class LedgerAddOutcome { ADDED, DUPLICATE }

/**
 * Returns the session with [entry] appended, plus whether it was a
 * duplicate of an earlier row. Pure, so the rule is unit-testable.
 */
fun LedgerSession.withScan(entry: LedgerEntry): Pair<LedgerSession, LedgerAddOutcome> {
    val outcome = if (contains(entry.rawValue)) {
        LedgerAddOutcome.DUPLICATE
    } else {
        LedgerAddOutcome.ADDED
    }
    return copy(entries = entries + entry) to outcome
}

/** The automatic name a new session starts with: "11/27 14:52 の台帳". */
fun defaultLedgerName(startedAt: Long): String =
    SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(startedAt)) + " の台帳"

/**
 * v0.5.1 (MINIMAL round 3, N3-3): while a session still carries its
 * automatic name, the name itself IS the start timestamp — meta lines
 * that also print "M/d HH:mm 開始" would say the same fact twice.
 * Views use this to drop the start segment for default-named sessions
 * only; a renamed session keeps the full meta line.
 */
fun LedgerSession.hasDefaultName(): Boolean = name == defaultLedgerName(startedAt)

// ---- JSON persistence (same flat shape discipline as Store's Entry) ----

fun LedgerEntry.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("rawValue", rawValue)
    put("formatName", formatName)
    put("kind", kind.name)
    put("scannedAt", scannedAt)
}

fun ledgerEntryFromJson(o: JSONObject): LedgerEntry = LedgerEntry(
    id = o.optString("id", UUID.randomUUID().toString()),
    rawValue = o.optString("rawValue"),
    formatName = o.optString("formatName", "QR_CODE"),
    kind = runCatching { CodeKind.valueOf(o.optString("kind")) }.getOrDefault(CodeKind.TEXT),
    scannedAt = o.optLong("scannedAt", 0L),
)

fun LedgerSession.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("startedAt", startedAt)
    if (endedAt != null) put("endedAt", endedAt) else put("endedAt", JSONObject.NULL)
    val arr = JSONArray()
    entries.forEach { arr.put(it.toJson()) }
    put("entries", arr)
}

fun ledgerSessionFromJson(o: JSONObject): LedgerSession {
    val arr = o.optJSONArray("entries")
    val list = mutableListOf<LedgerEntry>()
    val seenIds = HashSet<String>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val e = runCatching { ledgerEntryFromJson(arr.getJSONObject(i)) }.getOrNull()
            if (e != null && seenIds.add(e.id)) list += e
        }
    }
    return LedgerSession(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", ""),
        startedAt = o.optLong("startedAt", 0L),
        endedAt = if (o.isNull("endedAt")) null else o.optLong("endedAt").takeIf { it > 0 },
        entries = list,
    )
}

// ---- CSV (FR-EXPORT-02, ledger form) ----

/**
 * The ledger CSV: one row per scan, in scan order, so a spreadsheet
 * opens it as the paper ledger it replaces.
 *
 * Columns: 日時， 形式， 種別， 内容， 重複. FR-EXPORT-02 names
 * 日時, 形式, 種別, 内容, メモ, フォルダ for history export; ledger
 * rows carry no memo or folder, and 重複 takes their place because
 * duplicate visibility is the ledger's whole point. UTF-8 with BOM
 * so Excel does not mangle the Japanese (FR-EXPORT-02).
 *
 * 内容 is the raw payload — the CSV is the work record (a Wi-Fi
 * password scanned for a venue setup is the data the operator needs),
 * and the file only leaves the device when the user shares it.
 */
object LedgerCsv {

    const val HEADER = "日時,形式,種別,内容,重複"

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    fun build(session: LedgerSession): String {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN)
        val sb = StringBuilder()
        sb.append('\uFEFF') // BOM (FR-EXPORT-02)
        sb.append(HEADER).append("\r\n")
        val seen = HashSet<String>()
        for (e in session.entries) {
            val repeat = !seen.add(e.rawValue)
            sb.append(escape(fmt.format(Date(e.scannedAt)))).append(',')
                .append(escape(e.formatName)).append(',')
                .append(escape(e.kind.label)).append(',')
                .append(escape(e.rawValue)).append(',')
                .append(if (repeat) "重複" else "")
                .append("\r\n")
        }
        return sb.toString()
    }

    /** ASCII-safe file name for the share sheet: ledger_20261127_1452.csv. */
    fun fileName(session: LedgerSession): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.JAPAN)
        return "ledger_" + fmt.format(Date(session.startedAt)) + ".csv"
    }
}

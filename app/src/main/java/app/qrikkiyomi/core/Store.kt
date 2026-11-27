package app.qrikkiyomi.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Origin { SCANNED, CREATED }

/**
 * One library entry: either a scan-history item or a created QR.
 * For CREATED entries, [createType] + [fields] keep the form input so the QR
 * can be re-edited and duplicated (spec FR-CREATE-05).
 */
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val origin: Origin,
    val rawValue: String,
    val formatName: String, // e.g. QR_CODE, EAN_13; "QR_CODE" for created
    val kind: CodeKind,
    val createdAt: Long = System.currentTimeMillis(),
    val favorite: Boolean = false,
    val folder: String = "",
    val memo: String = "",
    val createType: CreateType? = null,
    val fields: Map<String, String> = emptyMap(),
    // P2-7: the drawing settings a created QR was made with, so re-editing
    // and PNG export from the library reproduce the same rendering.
    // All three are optional and absent in pre-v0.4.0 data (null = the
    // defaults M / 1024 / 2), which keeps old backups readable.
    val ecLevel: String? = null,
    val pngSize: Int? = null,
    val qrMargin: Int? = null,
)

fun Entry.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("origin", origin.name)
    put("rawValue", rawValue)
    put("formatName", formatName)
    put("kind", kind.name)
    put("createdAt", createdAt)
    put("favorite", favorite)
    put("folder", folder)
    put("memo", memo)
    put("createType", createType?.name ?: JSONObject.NULL)
    put("fields", JSONObject(fields as Map<*, *>))
    // P2-7: written only when present, so files stay byte-compatible in
    // shape with what older versions wrote.
    ecLevel?.let { put("ecLevel", it) }
    pngSize?.let { put("pngSize", it) }
    qrMargin?.let { put("qrMargin", it) }
}

fun entryFromJson(o: JSONObject): Entry {
    val fieldsObj = o.optJSONObject("fields")
    val fields = mutableMapOf<String, String>()
    if (fieldsObj != null) {
        val keys = fieldsObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            fields[k] = fieldsObj.optString(k)
        }
    }
    return Entry(
        id = o.optString("id", UUID.randomUUID().toString()),
        origin = runCatching { Origin.valueOf(o.optString("origin")) }.getOrDefault(Origin.SCANNED),
        rawValue = o.optString("rawValue"),
        formatName = o.optString("formatName", "QR_CODE"),
        kind = runCatching { CodeKind.valueOf(o.optString("kind")) }.getOrDefault(CodeKind.TEXT),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        favorite = o.optBoolean("favorite"),
        folder = o.optString("folder"),
        memo = o.optString("memo"),
        createType = if (o.isNull("createType")) null else
            runCatching { CreateType.valueOf(o.optString("createType")) }.getOrNull(),
        fields = fields,
        // P2-7: absent in pre-v0.4.0 files — read as null (defaults apply).
        ecLevel = if (o.has("ecLevel") && !o.isNull("ecLevel")) {
            o.optString("ecLevel").ifEmpty { null }
        } else {
            null
        },
        pngSize = if (o.has("pngSize") && !o.isNull("pngSize")) o.optInt("pngSize") else null,
        qrMargin = if (o.has("qrMargin") && !o.isNull("qrMargin")) o.optInt("qrMargin") else null,
    )
}

/**
 * FR-LIB-02 pruning, as a pure function so the rule is testable: scanned
 * history older than [days] is dropped at startup. Created QRs are never
 * touched, and — R2-11 — neither are favourites: a star is the user's
 * explicit "keep this", and silently deleting starred items broke the
 * trust the library is built on.
 */
fun pruneAgedScans(entries: List<Entry>, days: Int, now: Long): List<Entry> {
    if (days <= 0) return entries
    val cutoff = now - days * 86_400_000L
    return entries.filter {
        it.origin != Origin.SCANNED || it.favorite || it.createdAt >= cutoff
    }
}

/**
 * Local persistence.
 *
 * NOTE (deviation from spec section 11, decided at Phase 1 implementation):
 * the spec names Room, but Room needs KSP/kapt annotation processing which is
 * not reliably available in the author's build VM. Following the precedent of
 * the "Uroboe" app, Phase 1 stores everything in a single on-device JSON file
 * instead. Data shape is kept Room-like (flat entries + fields map) so a later
 * migration is mechanical. Everything stays on the device; nothing is sent out.
 */
class Store(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val ledgerFile = File(context.filesDir, "ledgers.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(): MutableList<Entry> = synchronized(lock) {
        if (!file.exists()) return mutableListOf()
        try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            // B-01: convert per element so one broken row cannot empty the
            // whole library (which the next save would then make permanent),
            // and drop duplicate ids — the library list keys rows by id, so
            // a duplicate would crash the screen.
            val out = mutableListOf<Entry>()
            val seenIds = HashSet<String>()
            for (i in 0 until arr.length()) {
                val entry = runCatching { entryFromJson(arr.getJSONObject(i)) }.getOrNull()
                if (entry != null && seenIds.add(entry.id)) out += entry
            }
            out.sortByDescending { it.createdAt }
            out
        } catch (e: Exception) {
            // The file itself is unreadable: keep a copy for recovery
            // instead of letting the next save overwrite it silently.
            runCatching {
                file.copyTo(File(file.parentFile, "library.json.corrupt"), overwrite = true)
            }
            mutableListOf()
        }
    }

    fun saveAll(entries: List<Entry>) = synchronized(lock) {
        // B-02: a failed write (disk full etc.) must not crash the caller —
        // every persist() runs inside a UI event handler.
        runCatching {
            val arr = JSONArray()
            entries.forEach { arr.put(it.toJson()) }
            val tmp = File(file.parentFile, "library.json.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    var autoSaveHistory: Boolean
        get() = prefs.getBoolean("autoSaveHistory", true)
        set(v) { prefs.edit().putBoolean("autoSaveHistory", v).apply() }

    /**
     * v0.4.2 (FR-SCAN-07 revised): save a code to history the moment it is
     * detected, not only when opened. Default on — the behaviour the user
     * asked for after trying v0.4.1 on a real device. When off, saving
     * falls back to the pre-0.4.2 rule (opened codes + "全部を履歴に保存").
     */
    var autoSaveOnDetect: Boolean
        get() = prefs.getBoolean("autoSaveOnDetect", true)
        set(v) { prefs.edit().putBoolean("autoSaveOnDetect", v).apply() }

    /** Vibrate once when the detection set grows (UX-2 / R-2). Default on. */
    var vibrateOnDetect: Boolean
        get() = prefs.getBoolean("vibrateOnDetect", true)
        set(v) { prefs.edit().putBoolean("vibrateOnDetect", v).apply() }

    /** Days after which scanned history is pruned at startup; 0 = never (FR-LIB-02). */
    var autoDeleteDays: Int
        get() = prefs.getInt("autoDeleteDays", 0)
        set(v) { prefs.edit().putInt("autoDeleteDays", v).apply() }

    /** True once the user has seen the first-run multi-scan hint (D-5). */
    var multiHintDone: Boolean
        get() = prefs.getBoolean("multiHintDone", false)
        set(v) { prefs.edit().putBoolean("multiHintDone", v).apply() }

    /**
     * R2-7: whether the camera permission has ever been requested. Together
     * with the system's rationale flag this distinguishes "not asked yet"
     * from "permanently denied", which needs a route into system Settings
     * instead of another request that can never show a dialog.
     */
    var cameraPermissionRequested: Boolean
        get() = prefs.getBoolean("cameraPermissionRequested", false)
        set(v) { prefs.edit().putBoolean("cameraPermissionRequested", v).apply() }

    // ---- Phase 2: continuous-scan ledgers (spec §2.3) ----
    //
    // Ledgers live in their own file, apart from the library: a work
    // session is not history, and the two never mix (the decision is
    // recorded at the top of core/Ledger.kt). Same discipline as the
    // library — one broken row cannot empty the file, writes are
    // atomic, and a failed write never crashes the caller. Sessions
    // come back newest-first.

    fun loadLedgers(): MutableList<LedgerSession> = synchronized(lock) {
        if (!ledgerFile.exists()) return mutableListOf()
        try {
            val arr = JSONArray(ledgerFile.readText(Charsets.UTF_8))
            val out = mutableListOf<LedgerSession>()
            val seenIds = HashSet<String>()
            for (i in 0 until arr.length()) {
                val s = runCatching { ledgerSessionFromJson(arr.getJSONObject(i)) }.getOrNull()
                if (s != null && seenIds.add(s.id)) out += s
            }
            out.sortByDescending { it.startedAt }
            out
        } catch (e: Exception) {
            runCatching {
                ledgerFile.copyTo(File(ledgerFile.parentFile, "ledgers.json.corrupt"), overwrite = true)
            }
            mutableListOf()
        }
    }

    fun saveLedgers(sessions: List<LedgerSession>) = synchronized(lock) {
        runCatching {
            val arr = JSONArray()
            sessions.forEach { arr.put(it.toJson()) }
            val tmp = File(ledgerFile.parentFile, "ledgers.json.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(ledgerFile)) {
                tmp.copyTo(ledgerFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * Phase 2 (§2.3): warn — vibration pattern + banner — when a code
     * that is already in the active ledger is read again. The spec
     * makes the warning optional ("設定でオフ可"); default on, because
     * at a reception desk the warning IS the feature.
     */
    var warnOnLedgerDuplicate: Boolean
        get() = prefs.getBoolean("warnOnLedgerDuplicate", true)
        set(v) { prefs.edit().putBoolean("warnOnLedgerDuplicate", v).apply() }

    /** Serialises the whole library as the backup JSON (FR-EXPORT-04). */
    fun backupJson(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    /**
     * Parses a backup JSON back into entries, or null when it is not one.
     * B-01: import replaces the whole library, so be strict — every element
     * must be an object carrying our "rawValue" key (any backup this app
     * writes always does); anything else is some other JSON file and is
     * refused before the current data is touched. Duplicate ids are
     * dropped for the same reason as in load().
     */
    fun parseBackup(text: String): List<Entry>? = try {
        val arr = JSONArray(text)
        val out = mutableListOf<Entry>()
        val seenIds = HashSet<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (!obj.has("rawValue")) return null
            val entry = entryFromJson(obj)
            if (seenIds.add(entry.id)) out += entry
        }
        out.sortByDescending { it.createdAt }
        out
    } catch (e: Exception) {
        null
    }
}

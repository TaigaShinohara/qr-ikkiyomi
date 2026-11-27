package app.qrikkiyomi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.qrikkiyomi.core.CodeClassifier
import app.qrikkiyomi.core.CreateType
import app.qrikkiyomi.core.Entry
import app.qrikkiyomi.core.LedgerAddOutcome
import app.qrikkiyomi.core.LedgerEntry
import app.qrikkiyomi.core.LedgerSession
import app.qrikkiyomi.core.Origin
import app.qrikkiyomi.core.Store
import app.qrikkiyomi.core.defaultLedgerName
import app.qrikkiyomi.core.pruneAgedScans
import app.qrikkiyomi.core.withScan
import app.qrikkiyomi.ui.CreateScreen
import app.qrikkiyomi.ui.CreateState
import app.qrikkiyomi.ui.LibraryScreen
import app.qrikkiyomi.ui.QrTheme
import app.qrikkiyomi.ui.ScanScreen
import app.qrikkiyomi.ui.ScanState
import app.qrikkiyomi.ui.SettingsScreen
import app.qrikkiyomi.ui.createFormKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R2-6: something that wants to become the create form's content — a
 * shared text/place, a "QRにする" from a result, a re-edit from the
 * library. When the form already holds the user's own input, the draft
 * is not applied silently; it waits here for the user's choice.
 */
private sealed interface PendingDraft {
    data class TextDraft(val text: String) : PendingDraft
    data class GeoDraft(val fields: Map<String, String>) : PendingDraft
    data class EditDraft(val entry: Entry) : PendingDraft
}

class MainActivity : ComponentActivity() {

    // Navigation/share state lives at activity level so that onNewIntent
    // (UX-13-8: shares received while the app is already running) can drive
    // the same Compose state as the initial intent.
    private var tab by mutableStateOf(0)
    private var sharedImageUri by mutableStateOf<Uri?>(null)
    private var pendingDraft by mutableStateOf<PendingDraft?>(null)

    // R2-6: both working states live as long as the activity does, so tab
    // switches dispose only the screens' compositions (and their camera /
    // executor resources), never the user's in-progress work.
    private val scanState = ScanState()
    private val createState = CreateState()

    /** Applies a draft to the create form, replacing what is there. */
    private fun applyDraft(draft: PendingDraft) {
        when (draft) {
            is PendingDraft.TextDraft -> {
                createState.type = CreateType.TEXT
                createState.fields.clear()
                createState.fields["text"] = draft.text
                createState.editingId = null
                createState.lastSavedKey = null
            }
            is PendingDraft.GeoDraft -> {
                createState.type = CreateType.GEO
                createState.fields["lat"] = draft.fields["lat"] ?: ""
                createState.fields["lng"] = draft.fields["lng"] ?: ""
                createState.fields["query"] = draft.fields["query"] ?: ""
                createState.editingId = null
                createState.lastSavedKey = null
            }
            is PendingDraft.EditDraft -> {
                val e = draft.entry
                val t = e.createType ?: return
                createState.type = t
                createState.fields.clear()
                createState.fields.putAll(e.fields)
                createState.editingId = e.id
                // P2-7: the drawing settings travel with the entry.
                createState.ecLabel = e.ecLevel ?: "M"
                createState.pngSize = e.pngSize ?: 1024
                createState.margin = e.qrMargin ?: 2
                createState.lastSavedKey = createFormKey(
                    t, e.fields, createState.ecLabel, createState.pngSize, createState.margin,
                )
            }
        }
    }

    /**
     * Offers a draft to the create form. Applies it immediately (and
     * returns true) when the form is empty; when the user has input of
     * their own, the draft is parked in [pendingDraft] for the
     * confirmation dialog instead of silently replacing it (R2-6).
     */
    private fun requestDraft(draft: PendingDraft): Boolean {
        // B-22: an entry with no create type (hand-made backup) has
        // nothing to re-edit; consume the request without a fuss.
        if (draft is PendingDraft.EditDraft && draft.entry.createType == null) return false
        // Re-editing the entry already open in the form is not a conflict.
        if (draft is PendingDraft.EditDraft && draft.entry.id == createState.editingId) {
            applyDraft(draft)
            return true
        }
        return if (!createState.hasContent()) {
            applyDraft(draft)
            true
        } else {
            pendingDraft = draft
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            QrTheme {
                val appContext = LocalContext.current
                val store = remember { Store(applicationContext) }
                val entries = remember {
                    mutableStateListOf<Entry>().apply {
                        val loaded = store.load()
                        // FR-LIB-02 / UX-13-3: prune aged scan history once
                        // at startup when the user enabled auto-deletion.
                        // R2-11: favourites are exempt (pruneAgedScans).
                        val kept = pruneAgedScans(loaded, store.autoDeleteDays, System.currentTimeMillis())
                        addAll(kept)
                        if (kept.size != loaded.size) store.saveAll(kept)
                    }
                }
                // Phase 2 (v0.5.0): the ledger book. Sessions persist
                // on EVERY change (each scan included) — an app that
                // dies mid-shift must lose nothing, and an unfinished
                // session simply waits in the list (§2.3 record).
                val ledgers = remember {
                    mutableStateListOf<LedgerSession>().apply { addAll(store.loadLedgers()) }
                }
                var warnDuplicate by remember { mutableStateOf(store.warnOnLedgerDuplicate) }

                fun persistLedgers() { store.saveLedgers(ledgers.toList()) }
                fun replaceLedger(session: LedgerSession) {
                    val idx = ledgers.indexOfFirst { it.id == session.id }
                    if (idx >= 0) ledgers[idx] = session
                    persistLedgers()
                }

                var autoSave by remember { mutableStateOf(store.autoSaveHistory) }
                var autoSaveOnDetect by remember { mutableStateOf(store.autoSaveOnDetect) }
                var vibrate by remember { mutableStateOf(store.vibrateOnDetect) }
                var autoDeleteDays by remember { mutableStateOf(store.autoDeleteDays) }
                var multiHintDone by remember { mutableStateOf(store.multiHintDone) }
                var cameraRequested by remember { mutableStateOf(store.cameraPermissionRequested) }
                // R2-10: a parsed backup waits here for the user's
                // confirmation before it may replace anything.
                var pendingImport by remember { mutableStateOf<List<Entry>?>(null) }
                // P2-9: backup file work runs off the main thread, with
                // a quiet progress state while it does.
                var backupBusy by remember { mutableStateOf(false) }
                val uiScope = rememberCoroutineScope()

                fun persist() { store.saveAll(entries.toList()) }
                fun upsert(entry: Entry) {
                    val idx = entries.indexOfFirst { it.id == entry.id }
                    if (idx >= 0) {
                        // B-20: replacing an entry (re-edit, memo, folder…)
                        // must not reset its creation time — the library is
                        // ordered newest-first by it.
                        entries[idx] = entry.copy(createdAt = entries[idx].createdAt)
                    } else {
                        entries.add(0, entry)
                    }
                    persist()
                }
                // Returns whether the entry was actually added, so the
                // scan screen can tell "saved" from "already saved" (P2-5).
                fun saveScanned(entry: Entry): Boolean {
                    // Avoid duplicate history rows for the same content.
                    if (entries.any { it.origin == Origin.SCANNED && it.rawValue == entry.rawValue }) {
                        return false
                    }
                    entries.add(0, entry)
                    persist()
                    return true
                }

                // FR-EXPORT-04 / UX-13-4: backup via the system file picker.
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri != null) {
                        backupBusy = true
                        uiScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                                        out.write(store.backupJson(entries.toList()).toByteArray(Charsets.UTF_8))
                                    } ?: throw IllegalStateException("no stream")
                                }.isSuccess
                            }
                            backupBusy = false
                            Toast.makeText(
                                appContext,
                                if (ok) "バックアップを書き出しました" else "書き出しに失敗しました",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) {
                        backupBusy = true
                        uiScope.launch {
                            val text = withContext(Dispatchers.IO) {
                                runCatching {
                                    appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                                }.getOrNull()
                            }
                            val parsed = text?.let { store.parseBackup(it) }
                            backupBusy = false
                            if (parsed != null) {
                                // R2-10: no replacement yet — the dialog
                                // below shows both counts and asks first.
                                pendingImport = parsed
                            } else {
                                Toast.makeText(appContext, "バックアップファイルを読めませんでした", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                                label = { Text("スキャン") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.Filled.AddCircle, contentDescription = null) },
                                label = { Text("作る") },
                            )
                            NavigationBarItem(
                                selected = tab == 2,
                                onClick = { tab = 2 },
                                icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                label = { Text("宝箱") },
                            )
                            NavigationBarItem(
                                selected = tab == 3,
                                onClick = { tab = 3 },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                label = { Text("設定") },
                            )
                        }
                    },
                ) { padding ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
                        when (tab) {
                            0 -> ScanScreen(
                                state = scanState,
                                sharedImageUri = sharedImageUri,
                                onSharedImageConsumed = { sharedImageUri = null },
                                autoSaveHistory = autoSave,
                                autoSaveOnDetect = autoSaveOnDetect,
                                vibrateOnDetect = vibrate,
                                multiHintDone = multiHintDone,
                                onMultiHintDone = {
                                    multiHintDone = true
                                    store.multiHintDone = true
                                },
                                permissionRequestedBefore = cameraRequested,
                                onPermissionRequested = {
                                    cameraRequested = true
                                    store.cameraPermissionRequested = true
                                },
                                isInLibrary = { raw ->
                                    entries.any { it.origin == Origin.SCANNED && it.rawValue == raw }
                                },
                                onScanned = { e -> saveScanned(e) },
                                onSaveEntries = { list -> list.count { saveScanned(it) } },
                                ledgers = ledgers.toList(),
                                activeSession = ledgers.firstOrNull {
                                    it.id == scanState.activeSessionId
                                },
                                warnOnLedgerDuplicate = warnDuplicate,
                                onLedgerStart = {
                                    val now = System.currentTimeMillis()
                                    val session = LedgerSession(
                                        name = defaultLedgerName(now),
                                        startedAt = now,
                                    )
                                    ledgers.add(0, session)
                                    persistLedgers()
                                    session.id
                                },
                                onLedgerAdd = { sessionId, entry ->
                                    val session = ledgers.firstOrNull { it.id == sessionId }
                                    if (session == null) {
                                        LedgerAddOutcome.ADDED
                                    } else {
                                        val (updated, outcome) = session.withScan(entry)
                                        replaceLedger(updated)
                                        outcome
                                    }
                                },
                                onLedgerUndoLast = { sessionId ->
                                    ledgers.firstOrNull { it.id == sessionId }?.let { s ->
                                        if (s.entries.isNotEmpty()) {
                                            replaceLedger(
                                                s.copy(entries = s.entries.dropLast(1))
                                            )
                                        }
                                    }
                                },
                                onLedgerDeleteEntry = { sessionId, entryId ->
                                    ledgers.firstOrNull { it.id == sessionId }?.let { s ->
                                        replaceLedger(
                                            s.copy(entries = s.entries.filterNot { it.id == entryId })
                                        )
                                    }
                                },
                                onLedgerEnd = { sessionId ->
                                    ledgers.firstOrNull { it.id == sessionId }?.let { s ->
                                        if (s.isOpen) {
                                            replaceLedger(
                                                s.copy(endedAt = System.currentTimeMillis())
                                            )
                                        }
                                    }
                                },
                                onLedgerRename = { sessionId, name ->
                                    ledgers.firstOrNull { it.id == sessionId }?.let { s ->
                                        replaceLedger(s.copy(name = name))
                                    }
                                },
                                onLedgerDelete = { sessionId ->
                                    ledgers.removeAll { it.id == sessionId }
                                    persistLedgers()
                                },
                                onMakeQrFromText = { text ->
                                    if (requestDraft(PendingDraft.TextDraft(text))) tab = 1
                                },
                            )
                            1 -> CreateScreen(
                                state = createState,
                                onSaved = { upsert(it) },
                                onGoLibrary = { tab = 2 },
                            )
                            2 -> LibraryScreen(
                                entries = entries.toList(),
                                onUpdate = { upsert(it) },
                                onAdd = { upsert(it) },
                                onDelete = { id ->
                                    entries.removeAll { it.id == id }
                                    persist()
                                },
                                onEditCreated = { e ->
                                    if (requestDraft(PendingDraft.EditDraft(e))) tab = 1
                                },
                                onMakeQrFromText = { text ->
                                    if (requestDraft(PendingDraft.TextDraft(text))) tab = 1
                                },
                                onGoScan = { tab = 0 },
                            )
                            else -> SettingsScreen(
                                autoSaveOnDetect = autoSaveOnDetect,
                                onToggleAutoSaveOnDetect = { v ->
                                    autoSaveOnDetect = v
                                    store.autoSaveOnDetect = v
                                },
                                autoSaveHistory = autoSave,
                                onToggleAutoSave = { v ->
                                    autoSave = v
                                    store.autoSaveHistory = v
                                },
                                vibrateOnDetect = vibrate,
                                onToggleVibrate = { v ->
                                    vibrate = v
                                    store.vibrateOnDetect = v
                                },
                                warnOnLedgerDuplicate = warnDuplicate,
                                onToggleWarnOnLedgerDuplicate = { v ->
                                    warnDuplicate = v
                                    store.warnOnLedgerDuplicate = v
                                },
                                autoDeleteDays = autoDeleteDays,
                                onSetAutoDeleteDays = { v ->
                                    autoDeleteDays = v
                                    store.autoDeleteDays = v
                                },
                                onExportBackup = { exportLauncher.launch("qr-ikkiyomi-backup.json") },
                                onImportBackup = { importLauncher.launch("application/json") },
                                onDeleteAllHistory = {
                                    entries.removeAll { it.origin == Origin.SCANNED }
                                    persist()
                                },
                                entryCount = entries.size,
                            )
                        }
                    }
                }

                // R2-6: a draft is waiting because the create form holds
                // the user's own input. Replacing is their decision.
                val draft = pendingDraft
                if (draft != null) {
                    AlertDialog(
                        onDismissRequest = { pendingDraft = null },
                        title = { Text("入力中の内容があります") },
                        text = {
                            // v0.4.3 (MINIMAL N-16): the title already says
                            // there is input in progress; the body keeps
                            // only the question and the consequence.
                            Text("新しい内容で置き換えますか？入力中の内容は消えます。")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                applyDraft(draft)
                                pendingDraft = null
                                tab = 1
                            }) { Text("置き換える") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingDraft = null }) { Text("やめる") }
                        },
                    )
                }

                // R2-10: backup import replaces the whole library — the
                // counts are shown and the user decides, before anything
                // is touched. Cancelling changes nothing.
                val import = pendingImport
                if (import != null) {
                    AlertDialog(
                        onDismissRequest = { pendingImport = null },
                        title = { Text("バックアップを読み込みますか") },
                        text = {
                            Text(
                                "現在の宝箱 ${entries.size} 件が、バックアップの ${import.size} 件に置き換わります。" +
                                    "現在の内容は元に戻せません。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                entries.clear()
                                entries.addAll(import)
                                persist()
                                pendingImport = null
                                Toast.makeText(appContext, "バックアップを読み込みました", Toast.LENGTH_SHORT).show()
                            }) { Text("置き換える") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingImport = null }) { Text("やめる") }
                        },
                    )
                }

                // P2-9: backup file work in flight.
                if (backupBusy) {
                    Dialog(onDismissRequest = { }) {
                        Surface(shape = MaterialTheme.shapes.medium) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.width(16.dp))
                                Text("バックアップを処理しています")
                            }
                        }
                    }
                }
            }
        }
    }

    // UX-13-8: with launchMode singleTop, shares that arrive while the app
    // is running come through here instead of a fresh onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        extractSharedImage(intent)?.let {
            sharedImageUri = it
            // R2-2: an image share is handled on the scan screen, so go
            // there — previously the URI sat unprocessed until the user
            // happened to open the scan tab.
            tab = 0
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (text.isNotEmpty()) {
                // D-4: shared place text with coordinates becomes a GEO
                // draft; anything else becomes a text-QR draft. R2-6: if
                // the create form holds the user's own input, the draft
                // waits for their choice instead of replacing it.
                val geo = CodeClassifier.parseGeoText(text)
                val applied = if (geo != null) {
                    requestDraft(
                        PendingDraft.GeoDraft(
                            mapOf(
                                "lat" to geo.lat,
                                "lng" to geo.lng,
                                "query" to (geo.query ?: ""),
                            )
                        )
                    )
                } else {
                    requestDraft(PendingDraft.TextDraft(text))
                }
                if (applied) tab = 1
            }
        }
    }

    private fun extractSharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("image/") != true) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

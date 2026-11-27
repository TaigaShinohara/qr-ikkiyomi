package app.qrikkiyomi.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.qrikkiyomi.core.CodeClassifier
import app.qrikkiyomi.core.LedgerCsv
import app.qrikkiyomi.core.hasDefaultName
import app.qrikkiyomi.core.LedgerEntry
import app.qrikkiyomi.core.LedgerSession
import app.qrikkiyomi.core.QrFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 2 (v0.5.0): the ledger list and ledger detail — where finished
 * (and interrupted) continuous-scan sessions live. They are hosted by
 * the scan tab as full-screen states (spec §4 keeps four tabs; the
 * ledger is the scan screen's own work record, not a fifth tab).
 */

/** HH:mm:ss — the working clock of a session, finer than the library's. */
private fun ledgerTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(millis))

/** Catalog: LedgerSessionRow — one session in the ledger list. */
@Composable
fun LedgerSessionRow(session: LedgerSession, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (session.isOpen) {
                    Spacer(Modifier.width(8.dp))
                    MiniChip("未終了")
                }
            }
            Text(
                // v0.5.1 (MINIMAL round 3, N3-3): a default-named
                // session's name already IS the start time, so the
                // sub-line keeps only the count; renamed sessions keep
                // the full "開始" segment.
                if (session.hasDefaultName()) {
                    "${session.entries.size}件"
                } else {
                    "${QrFactory.formatTimestamp(session.startedAt)} 開始 ・ ${session.entries.size}件"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // v0.5.2 (MINIMAL round 4, R4-6): the drill-in sign is the
        // platform's chevron glyph, not a text character wearing an
        // icon's job — same meaning, drawn in the icon vocabulary.
        // Decorative for TalkBack (the row itself is the action).
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Catalog: LedgerRow — one scan inside a session, shown in scan order
 * with its sequence number. A row whose content occurs more than once
 * in the session carries the 重複 marker (the list-side half of the
 * §2.3 duplicate rule; the scan-side half is the warning at read
 * time). [onDelete] is null in read-only contexts.
 */
@Composable
fun LedgerRow(
    index: Int,
    entry: LedgerEntry,
    duplicated: Boolean,
    onDelete: (() -> Unit)?,
) {
    val summaryText = CodeClassifier.summary(entry.rawValue, entry.kind)
    val rawLine = CodeClassifier.maskedForDisplay(entry.rawValue).replace("\n", " ").trim()
    val showRawLine = rawLine.isNotEmpty() &&
        !CodeClassifier.rawLineRedundant(entry.rawValue, entry.kind, summaryText)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${index + 1}",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeChip(entry.kind)
                if (duplicated) DuplicateChip()
            }
            Text(summaryText, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (showRawLine) {
                Text(
                    rawLine.take(60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            ledgerTime(entry.scannedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "この行を削除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // The chip uses the session-wide duplicated set, so the FIRST
    // occurrence is marked too — at a reception desk both rows are
    // "the duplicate pair", not just the second one. (The CSV marks
    // only 2nd-and-later rows; see LedgerCsv.)
}

/** The 重複 marker, in the warning role colours so it reads as one. */
@Composable
fun DuplicateChip() {
    val (bg, fg) = warningColors(strong = false)
    Box(
        modifier = Modifier.clip(MaterialTheme.shapes.small).background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("重複", style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerListView(
    sessions: List<LedgerSession>,
    onBack: () -> Unit,
    onOpen: (LedgerSession) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("台帳") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
            },
        )
        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyGuide("まだ台帳がありません。連続スキャンを始めると、ここに残ります")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sessions, key = { it.id }) { s -> LedgerSessionRow(s, onClick = { onOpen(s) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerDetailView(
    session: LedgerSession,
    onBack: () -> Unit,
    onResume: (LedgerSession) -> Unit,
    onEnd: (LedgerSession) -> Unit,
    onRename: (LedgerSession, String) -> Unit,
    onDeleteEntry: (LedgerSession, String) -> Unit,
    onDeleteSession: (LedgerSession) -> Unit,
) {
    val context = LocalContext.current
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val dupRaws = remember(session.entries) { session.duplicateRaws() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(session.name, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
            },
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                // v0.5.1 (MINIMAL round 3, N3-3): same rule as the list
                // row — for a default-named session the title already
                // carries the start time, so the meta line starts from
                // the end / open state instead of repeating it.
                if (session.hasDefaultName()) {
                    (session.endedAt?.let { "${QrFactory.formatTimestamp(it)} 終了" } ?: "未終了") +
                        " ・ ${session.entries.size}件"
                } else {
                    "${QrFactory.formatTimestamp(session.startedAt)} 開始" +
                        (session.endedAt?.let { " 〜 ${QrFactory.formatTimestamp(it)} 終了" } ?: " ・ 未終了") +
                        " ・ ${session.entries.size}件"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (session.isOpen) {
                PrimaryActionButton("この台帳で続ける") { onResume(session) }
                Spacer(Modifier.height(8.dp))
                SecondaryActionButton(
                    "台帳を終了する",
                    onClick = { onEnd(session) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            // FR-EXPORT-02: the CSV leaves only through the user's own
            // share-sheet choice — the app itself sends nothing.
            SecondaryActionButton(
                "CSVで書き出す",
                onClick = { shareLedgerCsv(context, session) },
                modifier = Modifier.fillMaxWidth(),
                enabled = session.entries.isNotEmpty(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton(
                    "名前を変える",
                    onClick = { renameOpen = true },
                    modifier = Modifier.weight(1f),
                )
                SecondaryActionButton(
                    "台帳を削除",
                    onClick = { deleteOpen = true },
                    modifier = Modifier.weight(1f),
                    danger = true,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (session.entries.isEmpty()) {
            EmptyGuide("この台帳にはまだ何も読んでいません")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(session.entries, key = { _, e -> e.id }) { index, e ->
                    LedgerRow(
                        index = index,
                        entry = e,
                        duplicated = e.rawValue in dupRaws,
                        onDelete = { onDeleteEntry(session, e.id) },
                    )
                }
            }
        }
    }

    if (renameOpen) {
        var draft by remember { mutableStateOf(session.name) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("台帳の名前") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "台帳の名前"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = draft.trim()
                        if (name.isNotEmpty()) onRename(session, name)
                        renameOpen = false
                    },
                ) { Text("変える") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("やめる") }
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("台帳を削除しますか") },
            text = { Text("「${session.name}」の ${session.entries.size} 件がすべて消えます。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    onDeleteSession(session)
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("やめる") }
            },
        )
    }
}

/**
 * Writes the session CSV into the share cache and hands it to the
 * system share sheet (the PngIO.sharePng pattern). The app has no
 * INTERNET permission; the file travels only through the app the
 * user picks here.
 */
fun shareLedgerCsv(context: Context, session: LedgerSession) {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, LedgerCsv.fileName(session))
        file.writeText(LedgerCsv.build(session), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, session.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "台帳CSVを共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "共有に失敗しました", Toast.LENGTH_SHORT).show()
    }
}

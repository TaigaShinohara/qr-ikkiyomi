package app.qrikkiyomi.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.qrikkiyomi.core.Entry
import app.qrikkiyomi.core.Origin
import java.util.UUID

/**
 * R-4: memo display/editing inside the detail sheet. The memo is plain
 * on-device text ("which shelf was this?") and is part of library search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoEditor(entry: Entry, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember(entry.id, entry.memo) { mutableStateOf(entry.memo) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // v0.4.3 (MINIMAL N-11): no standalone "メモ" heading — the
        // field's own label ("メモ（検索で引けます）") says it, with the
        // one extra fact that matters.
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("メモ（検索で引けます）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 4,
        )
        Spacer(Modifier.height(6.dp))
        SecondaryActionButton(
            "メモを保存",
            onClick = {
                onSave(text.trim())
                Toast.makeText(context, "メモを保存しました", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = text.trim() != entry.memo,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    entries: List<Entry>,
    onUpdate: (Entry) -> Unit,
    onAdd: (Entry) -> Unit,
    onDelete: (String) -> Unit,
    onEditCreated: (Entry) -> Unit,
    onMakeQrFromText: (String) -> Unit,
    onGoScan: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Entry?>(null) }
    var folderDialogFor by remember { mutableStateOf<Entry?>(null) }
    var deleteConfirmFor by remember { mutableStateOf<Entry?>(null) }

    val folders = remember(entries) {
        entries.map { it.folder }.filter { it.isNotEmpty() }.distinct().sorted()
    }
    // Look the selection up in the current list so edits (memo, folder)
    // made from the sheet are reflected without reopening it.
    val sel = selected?.let { s -> entries.firstOrNull { it.id == s.id } ?: s }
    val filtered = remember(entries, query, folderFilter) {
        entries.filter { e ->
            val folderOk = when (folderFilter) {
                null -> true
                FAVORITES -> e.favorite
                else -> e.folder == folderFilter
            }
            val q = query.trim()
            // P2-6: everything a row visibly offers as a clue is
            // searchable — folder name and 作成/読取 included, not only
            // the payload, kind and memo.
            val queryOk = q.isEmpty() ||
                e.rawValue.contains(q, ignoreCase = true) ||
                e.kind.label.contains(q, ignoreCase = true) ||
                e.memo.contains(q, ignoreCase = true) ||
                e.folder.contains(q, ignoreCase = true) ||
                (if (e.origin == Origin.CREATED) "作成" else "読取").contains(q) ||
                e.formatName.replace("_", " ").contains(q, ignoreCase = true)
            folderOk && queryOk
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            // v0.4.3 (MINIMAL N-10): the label is just "検索" — listing
            // the searchable fields in the label was a sentence doing a
            // word's job. Search still covers content, kind, memo and
            // folder, all of which are visible on the rows themselves.
            label = { Text("検索") },
            leadingIcon = {
                androidx.compose.material3.Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                )
            },
            modifier = Modifier.fillMaxWidth().padding(QrSpacing.gutter),
            singleLine = true,
        )
        // D-7 / UX-10: the filter row exists only when there is something
        // to filter by — folders, or at least one favourite.
        if (folders.isNotEmpty() || entries.any { it.favorite }) {
            FolderChipRow(
                folders = folders,
                selected = folderFilter,
                onSelect = { folderFilter = it },
                hasFavorites = entries.any { it.favorite },
            )
        }

        if (filtered.isEmpty()) {
            if (entries.isEmpty()) {
                // v0.3.0: a truly empty library is a state, not a list —
                // centre the guide in the remaining space instead of
                // pinning it to the top over a void.
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    EmptyGuide(
                        message = "まだ何もありません。スキャンするか、作ってみてください",
                        actionLabel = "スキャンへ",
                        onAction = onGoScan,
                    )
                }
            } else {
                // v0.4.6 (BEGINNER): a search/filter dead end — the
                // guide now carries the one way out (clear both), so a
                // first-time user is not left wondering whether the
                // entries are gone. The entries are untouched; only
                // the filter is lifted.
                EmptyGuide(
                    message = "条件に合うものがありません",
                    actionLabel = "検索と絞り込みを解除",
                    onAction = {
                        query = ""
                        folderFilter = null
                    },
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onClick = { selected = entry },
                        onToggleFavorite = { onUpdate(entry.copy(favorite = !entry.favorite)) },
                    )
                }
            }
        }
    }

    // ---- Detail sheet ----
    // D-6 / UX-9: both origins share one structure —
    // summary/QR → one recommended action → auxiliary → management → delete.
    if (sel != null) {
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                if (sel.origin == Origin.CREATED) {
                    Column(modifier = Modifier.padding(horizontal = QrSpacing.gutter)) {
                        QrPreview(
                            payload = sel.rawValue,
                            modifier = Modifier.fillMaxWidth(),
                            sizeDp = 200,
                            // P2-7: redraw with the settings it was made with.
                            ec = app.qrikkiyomi.core.QrFactory.errorCorrectionLevel(sel.ecLevel),
                            margin = sel.qrMargin ?: 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        // R2-3: display-safe form (Wi-Fi password masked).
                        Text(
                            app.qrikkiyomi.core.CodeClassifier.maskedForDisplay(sel.rawValue),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(modifier = Modifier.padding(horizontal = QrSpacing.gutter)) {
                        // The one recommended action for a created QR.
                        // B-19: background generation/sharing with the
                        // failure reason shown (was a silent no-op when
                        // the payload could not be rendered).
                        // P2-7: PNG export uses the saved size/EC/margin.
                        PrimaryActionButton("PNGを共有") {
                            shareQrPngAsync(
                                context,
                                sel.rawValue,
                                sel.pngSize ?: 1024,
                                app.qrikkiyomi.core.QrFactory.errorCorrectionLevel(sel.ecLevel),
                                sel.qrMargin ?: 2,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        // v0.3.0: auxiliaries share the one secondary-button
                        // shape and flow onto the next line instead of
                        // squeezing into a single row.
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            SecondaryActionButton("PNGを保存", onClick = {
                                saveQrPngAsync(
                                    context,
                                    sel.rawValue,
                                    sel.pngSize ?: 1024,
                                    app.qrikkiyomi.core.QrFactory.errorCorrectionLevel(sel.ecLevel),
                                    sel.qrMargin ?: 2,
                                )
                            })
                            SecondaryActionButton("再編集", onClick = {
                                selected = null
                                onEditCreated(sel)
                            })
                            SecondaryActionButton("複製", onClick = {
                                onAdd(
                                    sel.copy(
                                        id = UUID.randomUUID().toString(),
                                        createdAt = System.currentTimeMillis(),
                                    )
                                )
                                selected = null
                            })
                        }
                    }
                } else {
                    // P2-3: the QR itself comes first, inside the first
                    // screenful — re-showing a code from history (T-07)
                    // must not require scrolling past the whole payload.
                    Column(modifier = Modifier.padding(horizontal = QrSpacing.gutter)) {
                        Text("同じQRを表示", style = MaterialTheme.typography.labelLarge)
                        QrPreview(payload = sel.rawValue, modifier = Modifier.fillMaxWidth(), sizeDp = 140)
                    }
                    Spacer(Modifier.height(8.dp))
                    ResultContent(
                        rawValue = sel.rawValue,
                        formatName = sel.formatName,
                        onMakeQrFromText = { text ->
                            selected = null
                            onMakeQrFromText(text)
                        },
                        scrollable = false,
                    )
                }

                // Management: memo (R-4) and folder, then delete set apart.
                Column(modifier = Modifier.padding(horizontal = QrSpacing.gutter)) {
                    Spacer(Modifier.height(16.dp))
                    MemoEditor(entry = sel, onSave = { memo -> onUpdate(sel.copy(memo = memo)) })
                    Spacer(Modifier.height(12.dp))
                    SecondaryActionButton(
                        "フォルダを設定",
                        onClick = { folderDialogFor = sel },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = { deleteConfirmFor = sel }) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // ---- Folder dialog ----
    val fTarget = folderDialogFor
    if (fTarget != null) {
        var folderText by remember(fTarget.id) { mutableStateOf(fTarget.folder) }
        AlertDialog(
            onDismissRequest = { folderDialogFor = null },
            title = { Text("フォルダを設定") },
            text = {
                Column {
                    OutlinedTextField(
                        value = folderText,
                        onValueChange = { folderText = it },
                        label = { Text("フォルダ名（空で未分類）") },
                        singleLine = true,
                    )
                    if (folders.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("既存: " + folders.joinToString("、"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(fTarget.copy(folder = folderText.trim()))
                    folderDialogFor = null
                }) { Text("設定") }
            },
            dismissButton = {
                TextButton(onClick = { folderDialogFor = null }) { Text("やめる") }
            },
        )
    }

    // ---- Delete confirm ----
    val dTarget = deleteConfirmFor
    if (dTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmFor = null },
            title = { Text("削除しますか") },
            text = { Text("この項目を宝箱から削除します。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(dTarget.id)
                    deleteConfirmFor = null
                    selected = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmFor = null }) { Text("やめる") }
            },
        )
    }
}

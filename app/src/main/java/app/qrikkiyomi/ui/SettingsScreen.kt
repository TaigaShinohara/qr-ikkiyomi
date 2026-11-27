package app.qrikkiyomi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    autoSaveOnDetect: Boolean,
    onToggleAutoSaveOnDetect: (Boolean) -> Unit,
    autoSaveHistory: Boolean,
    onToggleAutoSave: (Boolean) -> Unit,
    vibrateOnDetect: Boolean,
    onToggleVibrate: (Boolean) -> Unit,
    warnOnLedgerDuplicate: Boolean,
    onToggleWarnOnLedgerDuplicate: (Boolean) -> Unit,
    autoDeleteDays: Int,
    onSetAutoDeleteDays: (Int) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onDeleteAllHistory: () -> Unit,
    entryCount: Int,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val versionLabel = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (e: Exception) {
            "0.4.3 (8)"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = QrSpacing.gutter, vertical = QrSpacing.lg),
    ) {
        // v0.4.3 (MINIMAL N-12): no "設定" headline — the selected
        // bottom-nav tab already says it; the headline was a re-display.
        // v0.4.3 (MINIMAL N-13): toggle descriptions keep only the part
        // the label does not already say.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("スキャンしたら自動で履歴に保存", style = MaterialTheme.typography.bodyLarge)
                Text(
                    // v0.4.2 (FR-SCAN-07 revised): the user asked for this
                    // after real-device testing — finding a code is enough
                    // for it to land in history, once per appearance.
                    // The description keeps only that guarantee.
                    "同じコードが映り続けても、保存は1回だけです",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoSaveOnDetect, onCheckedChange = onToggleAutoSaveOnDetect)
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("開いたコードを履歴に保存", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "上の自動保存がオフのときだけ働きます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoSaveHistory, onCheckedChange = onToggleAutoSave)
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // No description: the label is the whole fact.
                Text("検出時に振動", style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = vibrateOnDetect, onCheckedChange = onToggleVibrate)
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // Phase 2 (§2.3): the duplicate warning is spec'd as
                // optional — this is its switch.
                // v0.5.1 (MINIMAL round 3, N3-4): no description — as
                // with 検出時に振動 (round 1, T4), the label is the
                // whole fact; the old line only restated it with the
                // delivery channels spelled out.
                Text("連続スキャンで重複を警告", style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = warnOnLedgerDuplicate, onCheckedChange = onToggleWarnOnLedgerDuplicate)
        }

        Spacer(Modifier.height(QrSpacing.section))
        Text("データ", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            // v0.4.3 (MINIMAL N-14): the privacy sentence lived here AND
            // in "このアプリについて" below; the about section is the
            // canonical home, so this line keeps only the count.
            "保存件数: $entryCount 件",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        // FR-LIB-02 / UX-13-3: prune old scan history at startup.
        Text("古い履歴を自動削除", style = MaterialTheme.typography.bodyLarge)
        Text(
            // R2-11: favourites are exempt — the text says so, and the
            // pruning code (pruneAgedScans) does so.
            "読取履歴だけが対象で、作成したQRとお気に入りは消えません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "しない", 30 to "30日", 90 to "90日", 180 to "180日").forEach { (days, label) ->
                FilterChip(
                    selected = autoDeleteDays == days,
                    onClick = { onSetAutoDeleteDays(days) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        // FR-EXPORT-04 / UX-13-4: backup out/in as a JSON file, chosen by
        // the user through the system file picker. Nothing leaves the
        // device unless the user picks a destination themselves.
        Text("バックアップ", style = MaterialTheme.typography.bodyLarge)
        Text(
            // v0.4.3 (MINIMAL N-15): the buttons below already say what
            // they do (書き出す／読み込む); the description keeps only
            // the warning that is not on any button.
            "読み込むと、今の宝箱はバックアップの内容に置き換わります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // v0.3.0 design pass: action buttons are full-width and stacked, so
        // a long label can never be crushed into a narrow column (the old
        // side-by-side Row wrapped 読み込む into three lines / an oval).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryActionButton(
                "バックアップを書き出す",
                onClick = onExportBackup,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryActionButton(
                "バックアップから読み込む",
                onClick = onImportBackup,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryActionButton(
                "履歴をすべて削除",
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                danger = true,
            )
        }

        Spacer(Modifier.height(QrSpacing.section))
        Text("このアプリについて", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "QRいっき読み バージョン $versionLabel\n" +
                "アカウントなし・広告なし・通信なし（アプリ自体にインターネット権限がありません）。\n" +
                "外部のサイトやアプリを開くときだけ、選んだアプリに内容を渡します。\n" +
                "アンインストールすると、保存したデータはすべて消えます。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("履歴をすべて削除しますか") },
            text = { Text("読み取り履歴をすべて削除します。作成したQRは残ります。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAllHistory()
                    confirmDelete = false
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
            },
        )
    }
}

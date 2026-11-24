package app.qrikkiyomi.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.qrikkiyomi.core.CodeClassifier
import app.qrikkiyomi.core.CreateType
import app.qrikkiyomi.core.Entry
import app.qrikkiyomi.core.Origin
import app.qrikkiyomi.core.QrFactory

private data class FieldSpec(
    val key: String,
    val label: String,
    val required: Boolean = false,
    val keyboard: KeyboardType = KeyboardType.Text,
)

private fun fieldsOf(type: CreateType): List<FieldSpec> = when (type) {
    CreateType.URL -> listOf(FieldSpec("url", "URL", required = true, keyboard = KeyboardType.Uri))
    CreateType.TEXT -> listOf(FieldSpec("text", "テキスト", required = true))
    CreateType.WIFI -> listOf(
        FieldSpec("ssid", "ネットワーク名 (SSID)", required = true),
        FieldSpec("password", "パスワード"),
    )
    CreateType.CONTACT -> listOf(
        FieldSpec("name", "名前", required = true),
        FieldSpec("org", "会社・所属"),
        FieldSpec("tel", "電話番号", keyboard = KeyboardType.Phone),
        FieldSpec("email", "メールアドレス", keyboard = KeyboardType.Email),
        FieldSpec("url", "URL", keyboard = KeyboardType.Uri),
    )
    CreateType.EVENT -> listOf(
        FieldSpec("title", "タイトル", required = true),
        FieldSpec("start", "開始 (例: 2026-11-20 10:30)"),
        FieldSpec("end", "終了 (例: 2026-11-20 11:30)"),
        FieldSpec("location", "場所"),
    )
    CreateType.GEO -> listOf(
        FieldSpec("lat", "緯度", required = true, keyboard = KeyboardType.Decimal),
        FieldSpec("lng", "経度", required = true, keyboard = KeyboardType.Decimal),
        FieldSpec("query", "場所の名前（任意）"),
    )
    CreateType.EMAIL -> listOf(
        FieldSpec("email", "宛先メールアドレス", required = true, keyboard = KeyboardType.Email),
        FieldSpec("subject", "件名"),
        FieldSpec("body", "本文"),
    )
    CreateType.SMS_PHONE -> listOf(
        FieldSpec("number", "電話番号", required = true, keyboard = KeyboardType.Phone),
        FieldSpec("body", "本文（SMSのとき）"),
    )
}

private fun createTypeIcon(type: CreateType): ImageVector = when (type) {
    CreateType.URL -> Icons.Filled.Link
    CreateType.TEXT -> Icons.AutoMirrored.Filled.Notes
    CreateType.WIFI -> Icons.Filled.Wifi
    CreateType.CONTACT -> Icons.Filled.Person
    CreateType.EVENT -> Icons.Filled.Event
    CreateType.GEO -> Icons.Filled.Place
    CreateType.EMAIL -> Icons.Filled.Email
    CreateType.SMS_PHONE -> Icons.AutoMirrored.Filled.Message
}

/**
 * R2-6: the create form's working state, held at Activity level (like
 * ScanState) so switching tabs — to peek at the library, say — no longer
 * throws away a half-typed form. Only the screen's composition is
 * disposable; the draft is not.
 */
class CreateState {
    var type by mutableStateOf(CreateType.URL)
    val fields: SnapshotStateMap<String, String> = mutableStateMapOf()
    var ecLabel by mutableStateOf("M")
    var pngSize by mutableStateOf(1024)
    var margin by mutableStateOf(2)
    var showDetails by mutableStateOf(false)
    var editingId by mutableStateOf<String?>(null)
    /** P2-4: form key as of the last successful save; equality = saved. */
    var lastSavedKey by mutableStateOf<String?>(null)

    /** True when there is anything a new draft would destroy. */
    fun hasContent(): Boolean =
        editingId != null || fields.values.any { it.isNotBlank() }

    fun reset() {
        fields.clear()
        editingId = null
        lastSavedKey = null
    }
}

/**
 * B-21: the form's field map is shared by all creation types, so values
 * typed under another type linger under the same keys ("url", "email",
 * "body"…). A saved entry keeps only the keys its own type's form uses,
 * plus the type's auxiliary keys (encryption/hidden, mode).
 */
internal fun relevantFields(type: CreateType, fields: Map<String, String>): Map<String, String> {
    val keys = fieldsOf(type).map { it.key }.toMutableSet()
    when (type) {
        CreateType.WIFI -> { keys += "encryption"; keys += "hidden" }
        CreateType.SMS_PHONE -> keys += "mode"
        else -> Unit
    }
    return fields.filterKeys { it in keys }
}

/**
 * P2-4: a stable fingerprint of everything that defines the QR being
 * made (type, its relevant inputs, and the drawing settings). The form
 * compares the current key with the one captured at save time to know
 * whether what is on screen is exactly what is in the library.
 */
internal fun createFormKey(
    type: CreateType,
    fields: Map<String, String>,
    ecLabel: String,
    pngSize: Int,
    margin: Int,
): String {
    val body = relevantFields(type, fields).toSortedMap().entries
        .joinToString("&") { "${it.key}=${it.value}" }
    return "${type.name}|$body|$ecLabel|$pngSize|$margin"
}

/**
 * UX-8 validation, run live in the form. Event date-times that do not match
 * an accepted format and non-numeric / out-of-range coordinates produce an
 * error under their field and block payload generation — a broken QR is
 * never produced silently.
 */
private fun validateFields(type: CreateType, f: Map<String, String>): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    fun g(k: String) = f[k]?.trim().orEmpty()
    when (type) {
        CreateType.WIFI -> {
            // R2-4: an encrypted network needs its password. The reason
            // goes under the password field itself; the way to make an
            // open network is the "なし" encryption chip, said out loud.
            val enc = g("encryption").ifEmpty { "WPA" }
            if (enc != "nopass" && g("password").isEmpty()) {
                errors["password"] =
                    "暗号化ありのネットワークにはパスワードが必要です（鍵なしの回線は暗号化方式で「なし」を選んでください）"
            }
        }
        CreateType.EVENT -> {
            for (k in listOf("start", "end")) {
                val v = g(k)
                if (v.isNotEmpty() && !QrFactory.isValidEventDateTime(v)) {
                    errors[k] = "形式が違います（例：2026-11-20 10:30）"
                }
            }
            // B-11: flag an end that is before the start, here in the form
            // (QrFactory refuses it as well). Normalised forms are
            // fixed-width, so they compare correctly as strings.
            if (errors.isEmpty()) {
                val s = g("start")
                val e = g("end")
                if (s.isNotEmpty() && e.isNotEmpty()) {
                    val si = QrFactory.normalizeEventDateTime(s)
                    val ei = QrFactory.normalizeEventDateTime(e)
                    if (si != null && ei != null && ei < si) {
                        errors["end"] = "終了は開始より後にしてください"
                    }
                }
            }
        }
        CreateType.GEO -> {
            val lat = g("lat")
            if (lat.isNotEmpty()) {
                val d = lat.toDoubleOrNull()
                if (d == null) errors["lat"] = "数値で入力してください"
                else if (d !in -90.0..90.0) errors["lat"] = "緯度は -90〜90 の範囲です"
            }
            val lng = g("lng")
            if (lng.isNotEmpty()) {
                val d = lng.toDoubleOrNull()
                if (d == null) errors["lng"] = "数値で入力してください"
                else if (d !in -180.0..180.0) errors["lng"] = "経度は -180〜180 の範囲です"
            }
        }
        else -> Unit
    }
    return errors
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateScreen(
    state: CreateState,
    onSaved: (Entry) -> Unit,
    onGoLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val type = state.type
    val fields = state.fields
    val ecLabel = state.ecLabel
    val pngSize = state.pngSize
    val margin = state.margin
    val editingId = state.editingId

    val fieldErrors = remember(type, fields.toMap()) { validateFields(type, fields.toMap()) }
    val payload = remember(type, fields.toMap(), fieldErrors) {
        if (fieldErrors.isEmpty()) QrFactory.buildPayload(type, fields.toMap()) else null
    }
    val ec = QrFactory.errorCorrectionLevel(ecLabel)
    // R2-5: a payload that exists as a string can still be too dense to
    // ever become a QR. Generation success — not payload existence — is
    // what enables saving, and the preview / PNG / library verdicts agree.
    val generatable = remember(payload, ec, margin) {
        payload != null && QrFactory.canEncode(payload, ec, margin)
    }
    val currentKey = remember(type, fields.toMap(), ecLabel, pngSize, margin) {
        createFormKey(type, fields.toMap(), ecLabel, pngSize, margin)
    }
    // P2-4: what is on screen is exactly what was saved most recently.
    val isSavedState = payload != null && state.lastSavedKey != null &&
        state.lastSavedKey == currentKey

    fun pasteFromClipboard() {
        // FR-SCAN-08 / UX-13-2: an explicit paste button (the clipboard is
        // never read automatically).
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(context, "クリップボードにテキストがありません", Toast.LENGTH_SHORT).show()
            return
        }
        when (type) {
            CreateType.URL -> fields["url"] = text
            CreateType.TEXT -> fields["text"] = text
            else -> {
                state.type = CreateType.TEXT
                fields["text"] = text
            }
        }
        state.editingId = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // D-2 / UX-7: while a payload exists, the QR itself stays pinned at
        // the top so it can be watched while the form below is edited.
        if (payload != null) {
            QrPreview(
                payload = payload,
                modifier = Modifier.fillMaxWidth(),
                sizeDp = 120,
                ec = ec,
                margin = margin,
            )
            Text(
                CodeClassifier.summary(payload, CodeClassifier.classify(payload)),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("作る種類", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // v0.3.0: paste is a supporting action, not a peer of the
                // section title — a quiet text button with an icon, no box.
                TextButton(onClick = { pasteFromClipboard() }) {
                    Icon(
                        Icons.Filled.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("クリップボードから貼り付け")
                }
            }
            Spacer(Modifier.height(8.dp))
            // Catalog: TypePickerGrid (R-5: icon + name)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CreateType.values().forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = {
                            state.type = t
                            state.editingId = null
                        },
                        label = { Text(t.label) },
                        leadingIcon = {
                            Icon(createTypeIcon(t), contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            if (editingId != null) {
                Text("保存済みのQRを再編集しています", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            fieldsOf(type).forEach { spec ->
                val error = fieldErrors[spec.key]
                OutlinedTextField(
                    value = fields[spec.key] ?: "",
                    onValueChange = { fields[spec.key] = it },
                    label = { Text(spec.label + if (spec.required) " *" else "") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    singleLine = spec.key != "text" && spec.key != "body",
                    keyboardOptions = KeyboardOptions(keyboardType = spec.keyboard),
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error, color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                )
            }

            if (type == CreateType.GEO) {
                // D-4 hint: coordinates can arrive by sharing, not only typing.
                // v0.4.3 (MINIMAL N-9): one sentence only — the second
                // sentence (Google Maps URLs belong to the URL type) was
                // about a different form and read as noise here.
                Text(
                    "ヒント：地図アプリで場所を共有→このアプリを選ぶと、緯度経度が自動で入ります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (type == CreateType.WIFI) {
                Spacer(Modifier.height(8.dp))
                Text("暗号化方式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("WPA", "WEP", "nopass").forEach { enc ->
                        FilterChip(
                            selected = (fields["encryption"] ?: "WPA") == enc,
                            onClick = { fields["encryption"] = enc },
                            label = { Text(if (enc == "nopass") "なし" else enc) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = fields["hidden"] == "true",
                        onCheckedChange = { fields["hidden"] = it.toString() },
                    )
                    Text("隠しネットワーク (SSID非公開)")
                }
            }
            if (type == CreateType.SMS_PHONE) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = (fields["mode"] ?: "sms") == "sms",
                        onClick = { fields["mode"] = "sms" },
                        label = { Text("SMS") },
                    )
                    FilterChip(
                        selected = fields["mode"] == "tel",
                        onClick = { fields["mode"] = "tel" },
                        label = { Text("電話") },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            // D-2 / UX-7 + UX-13-1: specialist settings live behind one
            // "詳細設定" fold; the defaults (M / 1024 / 2) are only noted.
            Row(
                modifier = Modifier.fillMaxWidth().clickable { state.showDetails = !state.showDetails }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("詳細設定", style = MaterialTheme.typography.labelLarge)
                    // v0.3.0: the summary mirrors the actual values and is
                    // split into two short lines, so device text scaling can
                    // no longer strand a two-character orphan line.
                    Text(
                        "誤り訂正 $ecLabel／PNG ${pngSize}px／余白 $margin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // v0.4.3 (MINIMAL N-8): the reassurance line ("普段は
                    // 変えなくて大丈夫です") is gone — a closed fold
                    // already says exactly that.
                }
                Icon(
                    imageVector = if (state.showDetails) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (state.showDetails) "詳細設定を閉じる" else "詳細設定を開く",
                )
            }
            if (state.showDetails) {
                Text("誤り訂正レベル", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("L", "M", "Q", "H").forEach { lv ->
                        FilterChip(selected = ecLabel == lv, onClick = { state.ecLabel = lv }, label = { Text(lv) })
                    }
                }
                Text(
                    // v0.4.6 (BEGINNER): the chips alone (L/M/Q/H) do not
                    // say which end is stronger; the sentence now walks
                    // the chip order itself.
                    "L→M→Q→H の順に、汚れ・欠けに強くなります（既定は M）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("PNGのサイズ", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(512, 1024, 2048).forEach { sizePx ->
                        FilterChip(
                            selected = pngSize == sizePx,
                            onClick = { state.pngSize = sizePx },
                            label = { Text("${sizePx}px") },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // v0.4.6 (BEGINNER): "クワイエットゾーン" is spec
                // vocabulary; the everyday word (余白) already leads,
                // so the parenthesis keeps only the unit.
                Text("余白（マスの数）", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 2, 4, 8).forEach { m ->
                        FilterChip(
                            selected = margin == m,
                            onClick = { state.margin = m },
                            label = { Text(m.toString()) },
                        )
                    }
                }
                Text(
                    "印刷物に貼るときは余白を広めにすると読み取りやすくなります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            if (payload != null) {
                // R2-5: too dense to ever render — say why, next to the
                // action it blocks, instead of letting the user save a
                // "created QR" that only fails later in the library.
                if (!generatable) {
                    Text(
                        "内容が長すぎてQRにできません。内容を短くするか、詳細設定で誤り訂正を下げてください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                PrimaryActionButton(
                    text = if (editingId != null) "変更を保存" else "宝箱に保存",
                    enabled = generatable && !isSavedState,
                ) {
                    val entry = Entry(
                        id = editingId ?: java.util.UUID.randomUUID().toString(),
                        origin = Origin.CREATED,
                        rawValue = payload,
                        formatName = "QR_CODE",
                        kind = CodeClassifier.classify(payload),
                        createType = type,
                        fields = relevantFields(type, fields.toMap()),
                        // P2-7: keep the drawing settings with the entry.
                        ecLevel = ecLabel,
                        pngSize = pngSize,
                        qrMargin = margin,
                    )
                    onSaved(entry)
                    state.editingId = entry.id
                    state.lastSavedKey = currentKey
                    Toast.makeText(context, "宝箱に保存しました", Toast.LENGTH_SHORT).show()
                }
                if (isSavedState) {
                    // P2-4: a clear "done" state with the two natural
                    // next steps, instead of a save button that stays
                    // pressable over an unchanged form.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "この内容で宝箱に保存済みです",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryActionButton(
                            "宝箱で見る",
                            onClick = onGoLibrary,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryActionButton(
                            "新しく作る",
                            onClick = { state.reset() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // B-19: generation + IO run in the background and an
                    // over-capacity payload now says why, instead of the
                    // buttons silently doing nothing.
                    SecondaryActionButton(
                        "PNGを保存",
                        onClick = { saveQrPngAsync(context, payload, pngSize, ec, margin) },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryActionButton(
                        "PNGを共有",
                        onClick = { shareQrPngAsync(context, payload, pngSize, ec, margin) },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (fieldErrors.isEmpty()) {
                // v0.4.3 (MINIMAL N-7): no bottom error summary — each
                // error already sits under its own field; repeating the
                // first one down here was a re-display. With errors, the
                // bottom stays empty and the fields speak for themselves.
                EmptyGuide("必須の項目（*）を入れるとQRが出ます")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

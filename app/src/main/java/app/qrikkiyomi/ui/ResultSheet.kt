package app.qrikkiyomi.ui

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.qrikkiyomi.core.CodeClassifier
import app.qrikkiyomi.core.CodeKind
import app.qrikkiyomi.core.QrFactory

fun copyToClipboard(context: Context, text: String, label: String = "QR") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
}

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "共有"))
}

/**
 * v0.4.6 (BEGINNER): the format name in words a first-time user
 * already knows. The stored formatName itself is unchanged (it is
 * what library search matches); this is only how it is spelled on
 * screen — "QR_CODE" and "EAN_13" are spec-sheet vocabulary, while
 * the distinction a person needs is QRコード vs バーコード. The
 * rarer 2D formats (AZTEC, PDF417…) keep their names: there is no
 * plainer word for them, and they are rare enough to be the
 * footnote this line is.
 */
private fun friendlyFormatName(formatName: String): String = when {
    formatName == "QR_CODE" -> "QRコード"
    formatName.startsWith("EAN") || formatName.startsWith("UPC") ||
        formatName.startsWith("CODE") || formatName == "ITF" ||
        formatName == "CODABAR" -> "バーコード"
    else -> formatName.replace("_", " ")
}

/**
 * Catalog: ResultSheet — shows what a code contains and offers exactly one
 * big recommended action plus quiet secondary actions (spec section 6).
 * URL codes are never opened automatically; this sheet is the confirmation,
 * with the host shown large and shortener / IDN warnings when they apply.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ResultContent(
    rawValue: String,
    formatName: String,
    onMakeQrFromText: ((String) -> Unit)? = null,
    scrollable: Boolean = true,
    // P2-5: true when this code was already in the library before the
    // sheet was opened, so "already saved" can be said out loud instead
    // of the save silently doing nothing.
    alreadySaved: Boolean = false,
    // v0.4.4 (MINIMAL J-1, decided by the user 2026-11-24): in the scan
    // result sheet the raw block starts folded for every kind, replacing
    // the v0.4.3 "hide only when identical to the headline" rule. The
    // library detail screen passes false and keeps the v0.4.3 behaviour
    // (it is the place users come back to re-read a code, R2-11).
    collapseRawByDefault: Boolean = false,
) {
    val context = LocalContext.current
    val kind = remember(rawValue) { CodeClassifier.classify(rawValue) }
    val summary = remember(rawValue, kind) { CodeClassifier.summary(rawValue, kind) }
    val urlInfo = remember(rawValue, kind) {
        if (kind == CodeKind.URL) CodeClassifier.parseUrl(rawValue) else null
    }
    val wifi = remember(rawValue, kind) {
        if (kind == CodeKind.WIFI) CodeClassifier.parseWifi(rawValue) else null
    }
    // R2-3: what is shown is the display-safe form (Wi-Fi password
    // masked); the full raw value is one explicit tap away and is what
    // every action below keeps using internally.
    val displayRaw = remember(rawValue) { CodeClassifier.maskedForDisplay(rawValue) }
    val isMasked = displayRaw != rawValue
    // v0.4.4: with collapseRawByDefault this is also the raw block's
    // fold state (false = folded, nothing of the block on screen).
    var showRaw by remember(rawValue) { androidx.compose.runtime.mutableStateOf(false) }
    // R2-8: event times are parsed once, up front, so the sheet can show
    // them readably and refuse to invent a start time when there is none.
    val eventStart = remember(rawValue, kind) {
        if (kind == CodeKind.EVENT) {
            CodeClassifier.eventField(rawValue, "DTSTART")
                ?.let { QrFactory.parseIcalDateTime(it) }
        } else {
            null
        }
    }
    val eventEnd = remember(rawValue, kind) {
        if (kind == CodeKind.EVENT) {
            CodeClassifier.eventField(rawValue, "DTEND")
                ?.let { QrFactory.parseIcalDateTime(it) }
        } else {
            null
        }
    }
    val eventLocation = remember(rawValue, kind) {
        if (kind == CodeKind.EVENT) CodeClassifier.eventField(rawValue, "LOCATION") else null
    }

    // UX-13-10: long payloads (vCard, PDF417) must not push the recommended
    // action out of reach, so the sheet content scrolls.
    val scrollMod = if (scrollable) {
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier = scrollMod.padding(horizontal = QrSpacing.gutter, vertical = 20.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TypeChip(kind)
            // R-5: the format name is a footnote-level fact, not a peer chip.
            // v0.4.6 (BEGINNER): spelled in everyday words (see
            // friendlyFormatName) — the spec name stays in storage.
            Text(
                friendlyFormatName(formatName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        if (kind == CodeKind.URL && urlInfo != null) {
            // FR-SAFE-01: the host is the headline, shown before any open action.
            // v0.4.3 (MINIMAL N-4): no "開く前に確認" label above it — the
            // sheet itself is the confirmation step; the label only
            // restated the headline's job.
            Text(
                urlInfo.host,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            if (urlInfo.isShortener) {
                WarningBanner("短縮URLです。転送先は開くまで分かりません。")
                Spacer(Modifier.height(8.dp))
            }
            if (urlInfo.punycodeHost != null) {
                WarningBanner("日本語などを含むドメインです。実際の表記: ${urlInfo.punycodeHost}")
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Text(
                summary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (kind == CodeKind.WIFI && wifi != null) {
            Spacer(Modifier.height(6.dp))
            // FR-SAFE-03: SSID and encryption are shown before connecting.
            // v0.4.3 (MINIMAL N-6): the SSID is already the headline
            // ("Wi-Fi: <ssid>"), so this line carries only the encryption;
            // the pair is still fully visible in the sheet.
            // v0.4.6 (BEGINNER): the label is "セキュリティ" — the word
            // the system's own Wi-Fi settings use — and an open network
            // says what it means for the user (no password needed), not
            // just the spec token "nopass".
            val securityLabel = when {
                wifi.encryption.isEmpty() ||
                    wifi.encryption.equals("nopass", ignoreCase = true) ->
                    "なし（パスワード不要）"
                else -> wifi.encryption
            }
            Text("セキュリティ: $securityLabel")
            // R2-12: the connect action is a suggestion to the OS, not a
            // connection this app makes itself; say so before the tap.
            // v0.4.3 (MINIMAL N-6): trimmed to the two facts — a request
            // is sent, the system's approval is what connects.
            // v0.5.2 (MINIMAL round 4, C4-2): the first fact is now a
            // re-display — the button itself is labelled 接続リクエスト
            // を送る — so the note keeps only the delta (the connection
            // happens after the system's approval, not at the tap).
            // Where the approval appears （通知またはダイアログ） is
            // spelled out by the post-tap toast, at the moment it is
            // actually on its way.
            Text(
                "接続は、システムの承認後に行われます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (kind == CodeKind.CONTACT) {
            // P2-8: show the fields a person checks before adding —
            // name / phone / mail / organisation / URL — in readable
            // form instead of only the raw vCard text below.
            val cName = CodeClassifier.contactName(rawValue)
            val cTel = CodeClassifier.contactField(rawValue, "TEL")
            val cEmail = CodeClassifier.contactField(rawValue, "EMAIL")
            val cOrg = CodeClassifier.contactField(rawValue, "ORG")
            val cUrl = CodeClassifier.contactField(rawValue, "URL")
            if (cName != null || cTel != null || cEmail != null || cOrg != null || cUrl != null) {
                Spacer(Modifier.height(6.dp))
                // v0.5.2 (MINIMAL round 4, R4-3): the field names are
                // the kind icons the app already uses (Call / Email /
                // Business / Link — the same vocabulary as TypeChip),
                // so the rows are glyph + value. Each glyph carries
                // its field name as contentDescription, so TalkBack
                // still hears 電話／メール／所属／URL before the value.
                if (cTel != null) ContactFieldRow(Icons.Filled.Call, "電話", cTel)
                if (cEmail != null) ContactFieldRow(Icons.Filled.Email, "メール", cEmail)
                if (cOrg != null) ContactFieldRow(Icons.Filled.Business, "所属", cOrg)
                if (cUrl != null) ContactFieldRow(Icons.Filled.Link, "URL", cUrl)
            }
        }

        if (kind == CodeKind.EVENT) {
            // R2-8 / FR-SAFE-03: the when and where, formatted for a
            // human — not the raw iCalendar lines further down.
            Spacer(Modifier.height(6.dp))
            if (eventStart != null) {
                Text("開始: ${QrFactory.formatEventDateTime(eventStart)}")
                // A missing or backwards end falls back to one hour,
                // as before (B-11); a missing start never does (below).
                val shownEnd = if (eventEnd != null && eventEnd >= eventStart) {
                    eventEnd
                } else {
                    eventStart + 60 * 60 * 1000
                }
                Text("終了: ${QrFactory.formatEventDateTime(shownEnd)}")
            }
            if (eventLocation != null) Text("場所: $eventLocation")
            if (eventStart == null) {
                Spacer(Modifier.height(8.dp))
                WarningBanner("開始日時が読み取れないため、カレンダーに追加できません。", strong = true)
            }
        }

        if (collapseRawByDefault) {
            // v0.4.4 (MINIMAL J-1): folded for every kind, whatever the
            // payload — nothing of the raw block is on screen until the
            // "元のデータを表示" button below is tapped. Expanded, it
            // shows the full raw value, so for Wi-Fi the same tap is
            // also the R2-3 unmask; masked-by-default still holds
            // because the folded state shows nothing at all.
            if (showRaw) {
                Spacer(Modifier.height(10.dp))
                Text(
                    rawValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // v0.4.3 (MINIMAL N-5): when the raw block would print exactly
            // what the headline already says (a short single-line text, a
            // bare host…), it is a re-display and is dropped. When it adds
            // anything — more lines, a URL path, the masked Wi-Fi form, or
            // the explicitly revealed raw value — it stays.
            val rawText = if (showRaw) rawValue else displayRaw
            if (rawText.trim() != summary.trim()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    rawText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (alreadySaved) {
            Spacer(Modifier.height(6.dp))
            Text(
                "このコードは宝箱に保存済みです",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(18.dp))

        // ---- The one recommended action ----
        when (kind) {
            CodeKind.URL -> PrimaryActionButton("ブラウザで開く") {
                runIntent(context) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(urlInfo?.url ?: rawValue))
                }
            }
            CodeKind.WIFI -> PrimaryActionButton("接続リクエストを送る") {
                if (wifi != null) connectWifi(context, wifi.ssid, wifi.password, wifi.encryption, wifi.hidden)
            }
            CodeKind.CONTACT -> PrimaryActionButton("連絡先に追加") {
                runIntent(context) {
                    Intent(Intent.ACTION_INSERT).apply {
                        type = ContactsContract.Contacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, CodeClassifier.contactName(rawValue) ?: "")
                        CodeClassifier.contactField(rawValue, "TEL")?.let {
                            putExtra(ContactsContract.Intents.Insert.PHONE, it)
                        }
                        CodeClassifier.contactField(rawValue, "EMAIL")?.let {
                            putExtra(ContactsContract.Intents.Insert.EMAIL, it)
                        }
                        CodeClassifier.contactField(rawValue, "ORG")?.let {
                            putExtra(ContactsContract.Intents.Insert.COMPANY, it)
                        }
                    }
                }
            }
            CodeKind.EVENT -> PrimaryActionButton(
                "カレンダーに追加",
                // R2-8: with no readable start there is nothing honest to
                // register — never substitute "now" for a missing date.
                enabled = eventStart != null,
            ) {
                val begin = eventStart ?: return@PrimaryActionButton
                // B-11: an external code can carry an end before its start;
                // hand the calendar a sane one-hour event instead of a
                // broken range.
                val end = if (eventEnd != null && eventEnd >= begin) {
                    eventEnd
                } else {
                    begin + 60 * 60 * 1000
                }
                runIntent(context) {
                    Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                        putExtra(CalendarContract.Events.TITLE, CodeClassifier.eventField(rawValue, "SUMMARY") ?: "予定")
                        CodeClassifier.eventField(rawValue, "LOCATION")?.let {
                            putExtra(CalendarContract.Events.EVENT_LOCATION, it)
                        }
                    }
                }
            }
            CodeKind.GEO -> PrimaryActionButton("地図で開く") {
                runIntent(context) { Intent(Intent.ACTION_VIEW, Uri.parse(rawValue.trim())) }
            }
            CodeKind.EMAIL -> PrimaryActionButton("メールを作成") {
                runIntent(context) {
                    val addr = CodeClassifier.emailAddress(rawValue) ?: ""
                    if (rawValue.trim().startsWith("mailto:", ignoreCase = true)) {
                        // B-07: pass the original URI so the subject/body
                        // parameters the code carries are not thrown away.
                        Intent(Intent.ACTION_SENDTO, Uri.parse(rawValue.trim()))
                    } else {
                        // MATMSG: rebuild from its fields, subject/body too.
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$addr")).apply {
                            CodeClassifier.emailSubject(rawValue)?.let {
                                putExtra(Intent.EXTRA_SUBJECT, it)
                            }
                            CodeClassifier.emailBody(rawValue)?.let {
                                putExtra(Intent.EXTRA_TEXT, it)
                            }
                        }
                    }
                }
            }
            CodeKind.SMS -> PrimaryActionButton("メッセージを作成") {
                runIntent(context) {
                    val number = rawValue.substringAfter(':').substringBefore(':').substringBefore('?')
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                        // B-06: carry the message body the code was made with.
                        CodeClassifier.smsBody(rawValue)?.let { putExtra("sms_body", it) }
                    }
                }
            }
            CodeKind.PHONE -> PrimaryActionButton("発信画面を開く") {
                runIntent(context) { Intent(Intent.ACTION_DIAL, Uri.parse(rawValue.trim())) }
            }
            CodeKind.TEXT -> PrimaryActionButton("内容をコピー") {
                copyToClipboard(context, rawValue)
            }
        }

        Spacer(Modifier.height(10.dp))
        // v0.3.0: auxiliaries use the shared secondary button and flow, so
        // three of them never squeeze each other on a narrow screen.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (kind != CodeKind.TEXT) {
                SecondaryActionButton("コピー", onClick = { copyToClipboard(context, rawValue) })
            }
            SecondaryActionButton("共有", onClick = { shareText(context, rawValue) })
            if (kind == CodeKind.TEXT) {
                SecondaryActionButton("ウェブ検索", onClick = {
                    runIntent(context) {
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, rawValue)
                        }
                    }
                })
            }
            if (kind == CodeKind.WIFI && wifi != null && wifi.password.isNotEmpty()) {
                SecondaryActionButton(
                    "パスワードをコピー",
                    onClick = { copyToClipboard(context, wifi.password, "Wi-Fiパスワード") },
                )
            }
            // R2-3: the raw payload is masked by default (Wi-Fi password);
            // revealing it is an explicit act, and hiding it again is too.
            // v0.4.4 (J-1): in the result sheet this button exists for
            // every kind — it is the fold/unfold control for the raw
            // block — and carries the fold state for screen readers;
            // the copy/share actions above work the same folded or not.
            if (collapseRawByDefault || isMasked) {
                SecondaryActionButton(
                    // v0.4.6 (BEGINNER): "生データ" is developer
                    // vocabulary; the button now says what the tap
                    // shows — the code's content exactly as read.
                    if (showRaw) "元のデータを隠す" else "元のデータを表示",
                    onClick = { showRaw = !showRaw },
                    modifier = if (collapseRawByDefault) {
                        Modifier.semantics {
                            stateDescription =
                                if (showRaw) "元のデータは表示中" else "元のデータは折りたたみ中"
                        }
                    } else {
                        Modifier
                    },
                )
            }
            // UX-13-7: "QRで再共有" is a spec section 6 auxiliary action for
            // URL and text alike, so it is offered for every kind.
            if (onMakeQrFromText != null) {
                SecondaryActionButton("QRにする", onClick = { onMakeQrFromText(rawValue) })
            }
        }
    }
}

/**
 * v0.5.2 (MINIMAL round 4, R4-3): one contact field — a kind glyph
 * and the value. The glyph's contentDescription is the field name,
 * so the row announces exactly what the old "電話: …" text said.
 */
@Composable
private fun ContactFieldRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fieldName: String,
    value: String,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = fieldName,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        Text(value)
    }
}

private fun runIntent(context: Context, build: () -> Intent) {
    try {
        context.startActivity(build())
    } catch (e: Exception) {
        Toast.makeText(context, "開けるアプリが見つかりませんでした", Toast.LENGTH_SHORT).show()
    }
}

private fun connectWifi(context: Context, ssid: String, password: String, encryption: String, hidden: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Toast.makeText(context, "この端末では自動接続に未対応です。パスワードをコピーして設定から接続してください", Toast.LENGTH_LONG).show()
        return
    }
    try {
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid).setIsHiddenSsid(hidden)
        when {
            encryption.equals("WPA", true) || encryption.equals("WPA2", true) ||
                encryption.equals("WPA3", true) || encryption.contains("WPA", true) ->
                builder.setWpa2Passphrase(password)
            encryption.equals("WEP", true) ->
                Toast.makeText(context, "WEPは自動接続に未対応です", Toast.LENGTH_LONG).show().also { return }
        }
        val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val status = manager.addNetworkSuggestions(listOf(builder.build()))
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Toast.makeText(context, "接続リクエストを送りました。通知またはダイアログで承認すると接続されます", Toast.LENGTH_LONG).show()
        } else {
            // v0.4.6 (BEGINNER): the raw status code was a number only
            // a developer can act on; the message keeps the fact and
            // drops the jargon.
            Toast.makeText(context, "接続リクエストに失敗しました", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "接続リクエストに失敗しました", Toast.LENGTH_SHORT).show()
    }
}

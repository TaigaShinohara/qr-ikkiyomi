package app.qrikkiyomi.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.graphics.vector.ImageVector
import app.qrikkiyomi.core.CodeKind
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.qrikkiyomi.core.CodeClassifier
import app.qrikkiyomi.core.Entry
import app.qrikkiyomi.core.QrFactory

/** Catalog: PrimaryActionButton — the one big recommended action. */
@Composable
fun PrimaryActionButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Catalog: SecondaryActionButton — every non-recommended action in the app
 * (v0.3.0). One height (48dp), one radius (shapes.medium), outlined style,
 * so buttons never change shape or wrap just because they sit side by side.
 * Use [danger] for destructive actions that are not the screen's main act
 * (deletion itself still goes through a confirmation dialog + TextButton
 * inside sheets; this variant is for standalone rows like settings).
 */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val borderColor = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outline
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Icon per content kind (R-5): kinds are told apart by shape, not colour. */
fun kindIcon(kind: CodeKind): ImageVector = when (kind) {
    CodeKind.URL -> Icons.Filled.Link
    CodeKind.WIFI -> Icons.Filled.Wifi
    CodeKind.CONTACT -> Icons.Filled.Person
    CodeKind.EVENT -> Icons.Filled.Event
    CodeKind.GEO -> Icons.Filled.Place
    CodeKind.EMAIL -> Icons.Filled.Email
    CodeKind.SMS -> Icons.AutoMirrored.Filled.Message
    CodeKind.PHONE -> Icons.Filled.Call
    CodeKind.TEXT -> Icons.AutoMirrored.Filled.Notes
}

/** Catalog: TypeChip — kind shown by icon + short word, not colour alone. */
@Composable
fun TypeChip(kind: CodeKind) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(kindIcon(kind), contentDescription = null, modifier = Modifier.size(14.dp))
        Text(kind.label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Small plain chip for counts etc. (kept visually below TypeChip). */
@Composable
fun MiniChip(text: String) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Catalog: WarningBanner — only present when there is something to warn
 * about. Colours come from the QrRoleColors tokens (Theme.kt), chosen per
 * light/dark so the banner does not glow against a dark surface.
 */
@Composable
fun WarningBanner(text: String, strong: Boolean = false) {
    val (bg, fg) = warningColors(strong)
    Box(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Catalog: EmptyGuide — one sentence + one next action, no illustration. */
@Composable
fun EmptyGuide(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryActionButton(actionLabel, onClick = onAction)
        }
    }
}

/** Catalog: QrPreview — QR always on white so it stays scannable. */
@Composable
fun QrPreview(
    payload: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 240,
    ec: com.google.zxing.qrcode.decoder.ErrorCorrectionLevel =
        com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
    margin: Int = 2,
) {
    // B-28: render near the displayed size (3x for dense screens, capped
    // at 720) instead of always paying for a 720px bitmap — the preview in
    // the create form regenerates on every keystroke.
    val renderPx = (sizeDp * 3).coerceIn(240, 720)
    val bmp: Bitmap? = remember(payload, ec, margin, renderPx) {
        QrFactory.makeBitmap(payload, renderPx, ec, margin)
    }
    Box(
        // The white card is a deliberate, documented exception (readability
        // of the code itself); it borrows the app radius scale so it still
        // belongs to the same shape language.
        modifier = modifier.clip(MaterialTheme.shapes.medium).background(Color.White).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "QRコード",
                modifier = Modifier.size(sizeDp.dp),
            )
        } else {
            Text("QRを生成できませんでした", color = Color.Black)
        }
    }
}

/** Catalog: HistoryRow — one library entry in the 宝箱 list. */
@Composable
fun HistoryRow(entry: Entry, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // UX-11: the raw-value line is dropped when it would just repeat the
        // summary (always the case for plain text, whose summary IS the
        // first line), so each row shows summary + timestamp at minimum.
        // R2-3: the line shown is the display-safe form (Wi-Fi passwords
        // masked); P2-2 shares the redundancy rule with the scan list.
        val summaryText = CodeClassifier.summary(entry.rawValue, entry.kind)
        val rawLine = CodeClassifier.maskedForDisplay(entry.rawValue).replace("\n", " ").trim()
        val showRawLine = rawLine.isNotEmpty() &&
            !CodeClassifier.rawLineRedundant(entry.rawValue, entry.kind, summaryText)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeChip(entry.kind)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (entry.origin.name == "CREATED") "作成" else "読取",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.folder.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        entry.folder,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                summaryText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (showRawLine) {
                Text(
                    rawLine.take(60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                QrFactory.formatTimestamp(entry.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // P2-10: the star keeps its 28dp visual size but gets a 48dp
        // touch target — it is the row's only direct action and was easy
        // to miss with a fingertip.
        Box(
            modifier = Modifier.size(48.dp).clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (entry.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (entry.favorite) "お気に入りを外す" else "お気に入りにする",
                tint = if (entry.favorite) favoriteColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Catalog: FolderChipRow — folder filter chips; hidden when there are no folders. */
@Composable
fun FolderChipRow(
    folders: List<String>,
    selected: String?, // null = all, "" special handled by caller via label
    onSelect: (String?) -> Unit,
    // P2-12: with folders but zero favourites, an "お気に入り" chip that
    // always leads to an empty list is a dead end; show it only when it
    // can match something.
    hasFavorites: Boolean = true,
) {
    Row(
        // B-23: with several folders the row overflowed the screen and the
        // chips past the edge could not be reached; scroll horizontally.
        modifier = Modifier.fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("すべて") })
        if (hasFavorites) {
            FilterChip(
                selected = selected == FAVORITES,
                onClick = { onSelect(FAVORITES) },
                label = { Text("お気に入り") },
            )
        }
        folders.forEach { f ->
            FilterChip(selected = selected == f, onClick = { onSelect(f) }, label = { Text(f) })
        }
    }
}

const val FAVORITES = " FAVORITES "

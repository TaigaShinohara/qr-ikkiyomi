package app.qrikkiyomi.core

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The 8 creation types of the MVP (spec FR-CREATE-01). */
enum class CreateType(val label: String) {
    URL("URL"),
    TEXT("テキスト"),
    WIFI("Wi-Fi"),
    CONTACT("連絡先"),
    EVENT("予定"),
    GEO("場所"),
    EMAIL("メール"),
    SMS_PHONE("SMS・電話"),
}

object QrFactory {

    /** Escapes a value for the WIFI:/MECARD: style formats. */
    internal fun escapeField(v: String): String =
        v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")
            // B-10: the ZXing WIFI format also treats '"' as a special char.
            .replace("\"", "\\\"")

    private fun escapeVCard(v: String): String =
        v.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")

    /**
     * Builds the QR payload for a creation type from its form fields.
     * Returns null when a required field is missing.
     */
    fun buildPayload(type: CreateType, f: Map<String, String>): String? {
        fun g(k: String) = f[k]?.trim().orEmpty()
        return when (type) {
            CreateType.URL -> {
                val u = g("url"); if (u.isEmpty()) return null
                val withScheme = if (u.startsWith("http://", true) || u.startsWith("https://", true)) {
                    u
                } else {
                    "https://$u"
                }
                // B-17: a bare scheme ("https://" alone) is not a URL.
                if (withScheme.substringAfter("://").isEmpty()) return null
                withScheme
            }
            CreateType.TEXT -> g("text").ifEmpty { return null }
            CreateType.WIFI -> {
                val ssid = g("ssid"); if (ssid.isEmpty()) return null
                val enc = g("encryption").ifEmpty { "WPA" }
                // R2-4: an encrypted network without a password produces a
                // QR that can never connect (or silently means something
                // else than the user picked). Refuse it here as well as in
                // the form; open networks must be chosen as "nopass".
                if (enc != "nopass" && g("password").isEmpty()) return null
                buildString {
                    append("WIFI:T:").append(enc).append(';')
                    append("S:").append(escapeField(ssid)).append(';')
                    if (enc != "nopass") append("P:").append(escapeField(g("password"))).append(';')
                    if (g("hidden") == "true") append("H:true;")
                    append(';')
                }
            }
            CreateType.CONTACT -> {
                val name = g("name"); if (name.isEmpty()) return null
                buildString {
                    append("BEGIN:VCARD\nVERSION:3.0\n")
                    append("FN:").append(escapeVCard(name)).append('\n')
                    if (g("org").isNotEmpty()) append("ORG:").append(escapeVCard(g("org"))).append('\n')
                    if (g("tel").isNotEmpty()) append("TEL:").append(g("tel")).append('\n')
                    if (g("email").isNotEmpty()) append("EMAIL:").append(g("email")).append('\n')
                    if (g("url").isNotEmpty()) append("URL:").append(g("url")).append('\n')
                    append("END:VCARD")
                }
            }
            CreateType.EVENT -> {
                val title = g("title"); if (title.isEmpty()) return null
                val start = g("start"); val end = g("end")
                // UX-8: a malformed date-time must not silently become a broken
                // iCalendar payload. The form shows the error; here we refuse.
                val startIcal = if (start.isEmpty()) null else normalizeEventDateTime(start) ?: return null
                val endIcal = if (end.isEmpty()) null else normalizeEventDateTime(end) ?: return null
                // B-11: an event that ends before it starts is broken; refuse
                // it here (the form flags the end field). The fixed-width
                // normalised form compares correctly as a string.
                if (startIcal != null && endIcal != null && endIcal < startIcal) return null
                buildString {
                    append("BEGIN:VEVENT\n")
                    // B-18: RFC 5545 requires UID and DTSTAMP in a VEVENT;
                    // strict calendar parsers may refuse the event without.
                    append("UID:").append(java.util.UUID.randomUUID().toString())
                        .append("@qrikkiyomi\n")
                    append("DTSTAMP:").append(utcNowStamp()).append('\n')
                    append("SUMMARY:").append(escapeVCard(title)).append('\n')
                    if (startIcal != null) append("DTSTART:").append(startIcal).append('\n')
                    if (endIcal != null) append("DTEND:").append(endIcal).append('\n')
                    if (g("location").isNotEmpty()) append("LOCATION:").append(escapeVCard(g("location"))).append('\n')
                    append("END:VEVENT")
                }
            }
            CreateType.GEO -> {
                val lat = g("lat"); val lng = g("lng")
                if (lat.isEmpty() || lng.isEmpty()) return null
                // UX-8: coordinates must be numbers inside their valid ranges.
                val latD = lat.toDoubleOrNull() ?: return null
                val lngD = lng.toDoubleOrNull() ?: return null
                if (latD !in -90.0..90.0 || lngD !in -180.0..180.0) return null
                val q = g("query")
                // B-16: build from the validated numbers, not the raw text,
                // so inputs like "3.5e1" or "35." cannot leak into the URI.
                if (q.isNotEmpty()) "geo:$latD,$lngD?q=${urlEncode(q)}" else "geo:$latD,$lngD"
            }
            CreateType.EMAIL -> {
                val to = g("email"); if (to.isEmpty()) return null
                val params = mutableListOf<String>()
                if (g("subject").isNotEmpty()) params += "subject=${urlEncode(g("subject"))}"
                if (g("body").isNotEmpty()) params += "body=${urlEncode(g("body"))}"
                "mailto:$to" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
            }
            CreateType.SMS_PHONE -> {
                val num = g("number"); if (num.isEmpty()) return null
                when (g("mode")) {
                    "tel" -> "tel:$num"
                    else -> {
                        val body = g("body")
                        if (body.isNotEmpty()) "smsto:$num:${body}" else "smsto:$num"
                    }
                }
            }
        }
    }

    internal fun urlEncode(v: String): String =
        java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    // Accepted event date-time inputs (UX-8). The form validates with the
    // same list, so what the user sees and what gets encoded cannot drift.
    private val EVENT_INPUT_FORMATS = listOf(
        "yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy-MM-dd",
    )

    /**
     * Normalises an event date-time typed in the form to iCalendar form
     * ("20261120T103000"), or returns null when the text is not a valid
     * date-time in one of the accepted formats. A date-only value becomes
     * midnight at the start of that day.
     */
    fun normalizeEventDateTime(v: String): String? {
        val t = v.trim()
        if (t.isEmpty()) return null
        for (pattern in EVENT_INPUT_FORMATS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.isLenient = false
                val pos = java.text.ParsePosition(0)
                val date = fmt.parse(t, pos) ?: continue
                if (pos.index != t.length) continue // trailing garbage: not this format
                return SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(date)
            } catch (e: Exception) {
                // try the next format
            }
        }
        return null
    }

    /** True when [v] is a valid event date-time in an accepted format. */
    fun isValidEventDateTime(v: String): Boolean = normalizeEventDateTime(v) != null

    /** Maps a stored error-correction label ("L"/"M"/"Q"/"H") to its level. */
    fun errorCorrectionLevel(label: String?): ErrorCorrectionLevel = when (label) {
        "L" -> ErrorCorrectionLevel.L
        "Q" -> ErrorCorrectionLevel.Q
        "H" -> ErrorCorrectionLevel.H
        else -> ErrorCorrectionLevel.M
    }

    /**
     * R2-5: true when [payload] actually fits in a QR at the given error
     * correction level and margin. Pixel size does not affect capacity, so
     * this encodes at the matrix's natural size — cheap enough to run on
     * every keystroke, unlike a full-size bitmap render. The create form
     * uses it to make "can be saved" mean "can really be generated".
     */
    fun canEncode(
        payload: String,
        ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        margin: Int = 2,
    ): Boolean = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ec,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        true
    } catch (e: Exception) {
        false
    }

    /** Renders a QR bitmap: black modules on white, always (spec: QrPreview). */
    fun makeBitmap(
        payload: String,
        sizePx: Int = 720,
        ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        margin: Int = 2,
    ): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ec,
                EncodeHintType.MARGIN to margin,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /** Current UTC time in iCalendar stamp form ("20261114T103000Z"). */
    private fun utcNowStamp(): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** Parses an iCalendar date-time ("20261120T103000" / "...Z") to epoch millis. */
    fun parseIcalDateTime(v: String): Long? {
        val t = v.trim()
        // B-09: strict parsing — non-lenient and the whole string must be
        // consumed, so a month-13 value or trailing garbage is rejected
        // instead of silently rolling over into a different date.
        fun parseStrict(pattern: String, utc: Boolean): Long? = try {
            val fmt = SimpleDateFormat(pattern, Locale.US)
            fmt.isLenient = false
            if (utc) fmt.timeZone = TimeZone.getTimeZone("UTC")
            val pos = java.text.ParsePosition(0)
            val date = fmt.parse(t, pos)
            if (date != null && pos.index == t.length) date.time else null
        } catch (e: Exception) {
            null
        }
        return when {
            t.endsWith("Z") -> parseStrict("yyyyMMdd'T'HHmmss'Z'", utc = true)
            t.contains('T') -> parseStrict("yyyyMMdd'T'HHmmss", utc = false)
            else -> parseStrict("yyyyMMdd", utc = false)
        }
    }

    fun formatTimestamp(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(millis))

    /** R2-8: human-readable event date-time for the result sheet. */
    fun formatEventDateTime(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd (E) HH:mm", Locale.JAPAN).format(Date(millis))
}

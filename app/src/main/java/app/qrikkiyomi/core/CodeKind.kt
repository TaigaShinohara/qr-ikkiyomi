package app.qrikkiyomi.core

import java.net.IDN
import java.util.Locale

/** Content kinds recognised by the result sheet (spec section 6). */
enum class CodeKind(val label: String) {
    URL("URL"),
    WIFI("Wi-Fi"),
    CONTACT("連絡先"),
    EVENT("予定"),
    GEO("場所"),
    EMAIL("メール"),
    SMS("SMS"),
    PHONE("電話"),
    TEXT("テキスト"),
}

data class WifiInfo(
    val ssid: String,
    val password: String,
    val encryption: String, // WPA / WEP / nopass
    val hidden: Boolean,
)

data class UrlInfo(
    val url: String,
    val host: String,
    /** Punycode form of the host when the visible host contains non-ASCII. */
    val punycodeHost: String?,
    val isShortener: Boolean,
) {
    val hasWarning: Boolean get() = punycodeHost != null || isShortener
}

object CodeClassifier {

    private val SHORTENERS = setOf(
        "bit.ly", "t.co", "goo.gl", "tinyurl.com", "ow.ly", "is.gd", "buff.ly",
        "cutt.ly", "rb.gy", "s.id", "shorturl.at", "tiny.cc", "rebrand.ly",
        "bl.ink", "short.io", "lnkd.in", "qr.ae", "x.gd", "v.gd",
    )

    fun classify(raw: String): CodeKind {
        // B-30: a leading BOM (some generators prepend one) must not hide
        // the payload's real prefix.
        val t = raw.trim().trim('\uFEFF')
        val lower = t.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("wifi:") -> CodeKind.WIFI
            lower.startsWith("begin:vcard") || lower.startsWith("mecard:") -> CodeKind.CONTACT
            lower.startsWith("begin:vevent") || lower.startsWith("begin:vcalendar") -> CodeKind.EVENT
            lower.startsWith("geo:") -> CodeKind.GEO
            lower.startsWith("mailto:") || lower.startsWith("matmsg:") -> CodeKind.EMAIL
            lower.startsWith("smsto:") || lower.startsWith("sms:") -> CodeKind.SMS
            lower.startsWith("tel:") -> CodeKind.PHONE
            lower.startsWith("http://") || lower.startsWith("https://") -> CodeKind.URL
            // Bare URLs printed without a scheme are still URLs in practice.
            isBareUrl(t) -> CodeKind.URL
            else -> CodeKind.TEXT
        }
    }

    /**
     * Extensions that make a bare string a file name, not a URL (UX-12).
     * A bare "report.pdf" must not be offered as "ブラウザで開く".
     */
    private val FILE_EXTENSIONS = setOf(
        "pdf", "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "txt", "csv",
        "mp3", "mp4", "mov", "apk",
    )

    private fun isBareUrl(t: String): Boolean {
        if (t.contains(' ') || t.contains('\n')) return false
        // Anything with "@" is an e-mail address (or contains credentials),
        // never a bare URL worth opening blindly (UX-12).
        if (t.contains('@')) return false
        // Strings ending in a known file extension are file names (UX-12).
        val lastSegment = t.substringBefore('?').substringAfterLast('/')
        val ext = lastSegment.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in FILE_EXTENSIONS) return false
        val hostPart = t.substringBefore('/').substringBefore('?')
        if (!hostPart.contains('.')) return false
        // B-05: drop an optional ":port" before judging the TLD, so
        // "example.com:8080/path" is recognised as a URL too.
        val hostNoPort = hostPart.substringBefore(':')
        val tld = hostNoPort.substringAfterLast('.')
        return tld.length in 2..24 && tld.all { it.isLetter() }
    }

    // ---- Shared-place parsing (D-4: build a GEO draft from shared text) ----

    /** A latitude/longitude pair extracted from shared text, if any. */
    data class GeoPoint(val lat: String, val lng: String, val query: String?)

    private fun validLatLng(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0

    /**
     * Finds coordinates in text shared from a maps app etc. Handles a geo:
     * URI, Google Maps URLs (the "@lat,lng" viewport form and the
     * q=/query=/ll= parameter forms), and a bare "lat, lng" line.
     * Shortened map links (maps.app.goo.gl/...) carry no coordinates and
     * cannot be resolved offline, so they return null here.
     */
    fun parseGeoText(text: String): GeoPoint? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val coord = """(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)"""

        Regex("""geo:\s*$coord""").find(t)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lng = m.groupValues[2].toDouble()
            if (validLatLng(lat, lng)) return GeoPoint(m.groupValues[1], m.groupValues[2], null)
        }
        Regex("""@$coord""").find(t)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lng = m.groupValues[2].toDouble()
            if (validLatLng(lat, lng)) {
                // A textual q= parameter next to the viewport coords is the place name.
                val q = Regex("""[?&](?:q|query)=([^&]+)""").find(t)?.groupValues?.get(1)
                    ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                    ?.takeIf { it.isNotBlank() && !it.matches(Regex("""-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?""")) }
                return GeoPoint(m.groupValues[1], m.groupValues[2], q)
            }
        }
        Regex("""[?&](?:q|query|ll)=$coord""").find(t)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lng = m.groupValues[2].toDouble()
            if (validLatLng(lat, lng)) return GeoPoint(m.groupValues[1], m.groupValues[2], null)
        }
        // maps URLs percent-encode the comma in q=lat%2Clng.
        Regex("""[?&](?:q|query|ll)=(-?\d+(?:\.\d+)?)%2C(-?\d+(?:\.\d+)?)""").find(t)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lng = m.groupValues[2].toDouble()
            if (validLatLng(lat, lng)) return GeoPoint(m.groupValues[1], m.groupValues[2], null)
        }
        Regex("""^\s*$coord\s*$""", RegexOption.MULTILINE).find(t)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lng = m.groupValues[2].toDouble()
            // Bare pairs need decimals on both sides to avoid matching dates etc.
            if (m.groupValues[1].contains('.') && m.groupValues[2].contains('.') && validLatLng(lat, lng)) {
                return GeoPoint(m.groupValues[1], m.groupValues[2], null)
            }
        }
        return null
    }

    /** One-line human summary shown as the headline of the result sheet. */
    fun summary(raw: String, kind: CodeKind): String = when (kind) {
        CodeKind.URL -> parseUrl(raw)?.host ?: raw
        CodeKind.WIFI -> parseWifi(raw)?.let { "Wi-Fi: ${it.ssid}" } ?: "Wi-Fi"
        CodeKind.CONTACT -> contactName(raw) ?: "連絡先"
        CodeKind.EVENT -> eventField(raw, "SUMMARY") ?: "予定"
        CodeKind.GEO -> geoSummary(raw)
        CodeKind.EMAIL -> emailAddress(raw) ?: raw
        CodeKind.SMS, CodeKind.PHONE ->
            raw.substringAfter(':').substringBefore(':').substringBefore('?').substringBefore(';')
        CodeKind.TEXT -> raw.lineSequence().firstOrNull()?.take(80) ?: raw
    }

    /**
     * P2-12: a geo: URI often carries the place name in its q= parameter
     * ("geo:0,0?q=東京タワー"); the name is far more recognisable in lists
     * than the bare coordinates, so it wins when present. A q= that is
     * itself a coordinate pair is not a name and falls through.
     */
    private fun geoSummary(raw: String): String {
        val body = raw.replaceFirst(Regex("(?i)^geo:"), "")
        val q = Regex("""[?&]q=([^&;]+)""").find(raw)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.trim()
        val qIsCoords = q != null &&
            q.matches(Regex("""-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?"""))
        return if (!q.isNullOrEmpty() && !qIsCoords) {
            "場所: $q"
        } else {
            "場所: " + body.substringBefore(";").substringBefore("?")
        }
    }

    /**
     * R2-3: display-safe form of a raw payload. A Wi-Fi code's password
     * (the "P:" field) is masked; every other kind is returned unchanged.
     * Internal processing (copy, connect, re-create) always uses the
     * original raw value — this is only for what is shown on screen.
     */
    fun maskedForDisplay(raw: String): String =
        if (classify(raw) == CodeKind.WIFI) maskWifiPassword(raw) else raw

    /**
     * Replaces the value of the WIFI format's "P:" field with bullets,
     * honouring backslash escapes when splitting fields (an escaped ';'
     * inside the password must not end the field early). Field order,
     * separators and trailing ";;" are preserved.
     */
    fun maskWifiPassword(raw: String): String {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> { sb.append(c); sb.append(raw[i + 1]); i += 2 }
                c == ';' -> { tokens += sb.toString(); sb.clear(); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        tokens += sb.toString()
        return tokens.joinToString(";") { tok ->
            val idx = tok.indexOf(':')
            if (idx > 0 && tok.substring(0, idx).equals("P", ignoreCase = true) &&
                tok.substring(idx + 1).isNotEmpty()
            ) {
                tok.substring(0, idx + 1) + "••••••"
            } else {
                tok
            }
        }
    }

    /**
     * True when a one-line raw preview would add nothing next to the
     * summary (UX-11's rule, shared by the library rows and — since
     * P2-2 — the scan result list): plain text's summary IS its first
     * line, and a line that merely restates the summary is noise.
     */
    fun rawLineRedundant(raw: String, kind: CodeKind, summary: String): Boolean {
        if (kind == CodeKind.TEXT) return true
        val line = raw.replace("\n", " ").trim()
        if (line.isEmpty()) return true
        return line.startsWith(summary) || summary.startsWith(line.take(60))
    }

    fun parseUrl(raw: String): UrlInfo? {
        val t = raw.trim()
        // NOTE: java.net.URI.getHost() returns null for non-ASCII (IDN) hosts,
        // which are exactly the ones we must warn about, so the host is
        // extracted manually here instead.
        val afterScheme = t.substringAfter("://", t)
        val hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPort.substringAfterLast('@').substringBefore(':')
        if (host.isEmpty() || !host.contains('.')) return null
        val lowerHost = host.lowercase(Locale.ROOT)
        val puny = if (host.any { it.code > 127 }) {
            try { IDN.toASCII(host) } catch (e: Exception) { null }
        } else {
            null
        }
        // B-04: a bare host ("example.com/path") has no scheme, and an
        // ACTION_VIEW with a scheme-less URI finds no app. Normalise the
        // openable URL here so every caller gets one that can be opened.
        val openable = if (t.contains("://")) t else "https://$t"
        return UrlInfo(
            url = openable,
            host = host,
            punycodeHost = puny,
            isShortener = lowerHost in SHORTENERS,
        )
    }

    fun parseWifi(raw: String): WifiInfo? {
        // WIFI:T:WPA;S:ssid;P:pass;H:false;;
        // B-03: classification accepts any case of the prefix ("Wifi:" etc.),
        // so stripping it must be case-insensitive too — otherwise the first
        // field is misread and the encryption comes out as "nopass".
        val body = raw.trim().replaceFirst(Regex("(?i)^wifi:"), "")
        val fields = parseEscapedFields(body)
        val ssid = fields["S"] ?: return null
        return WifiInfo(
            ssid = ssid,
            password = fields["P"] ?: "",
            encryption = fields["T"] ?: "nopass",
            hidden = fields["H"]?.equals("true", ignoreCase = true) == true,
        )
    }

    /** Splits "K:v;K:v;;" honouring backslash escapes used by the WIFI/MeCard formats. */
    internal fun parseEscapedFields(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { sb.append(body[i + 1]); i += 2 }
                c == ';' -> { tokens += sb.toString(); sb.clear(); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        for (tok in tokens) {
            val idx = tok.indexOf(':')
            if (idx > 0) out[tok.substring(0, idx).uppercase(Locale.ROOT)] = tok.substring(idx + 1)
        }
        return out
    }

    fun contactName(raw: String): String? {
        val t = raw.trim()
        return if (t.startsWith("MECARD:", ignoreCase = true)) {
            parseEscapedFields(t.substringAfter(':'))["N"]?.replace(',', ' ')
        } else {
            // vCard: FN wins, fall back to N.
            lineValue(t, "FN") ?: lineValue(t, "N")?.replace(';', ' ')
        }
    }

    fun contactField(raw: String, key: String): String? {
        val t = raw.trim()
        return if (t.startsWith("MECARD:", ignoreCase = true)) {
            parseEscapedFields(t.substringAfter(':'))[key]
        } else {
            lineValue(t, key)
        }
    }

    fun eventField(raw: String, key: String): String? = lineValue(raw, key)

    private fun lineValue(text: String, key: String): String? {
        for (line in text.lineSequence()) {
            val clean = line.trim()
            val name = clean.substringBefore(':').substringBefore(';')
            if (name.equals(key, ignoreCase = true) && clean.contains(':')) {
                // B-08: vCard / iCalendar text values arrive escaped
                // ("FN:山田\,太郎"); hand callers the real text, the same
                // way parseEscapedFields does for MECARD/WIFI values.
                return clean.substringAfter(':').trim().ifEmpty { null }
                    ?.let { unescapeTextValue(it) }
            }
        }
        return null
    }

    /** Reverses the escaping used for vCard / iCalendar text values. */
    internal fun unescapeTextValue(v: String): String {
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun emailAddress(raw: String): String? {
        val t = raw.trim()
        return when {
            t.startsWith("mailto:", ignoreCase = true) ->
                t.substringAfter(':').substringBefore('?').ifEmpty { null }
            t.startsWith("MATMSG:", ignoreCase = true) ->
                parseEscapedFields(t.substringAfter(':'))["TO"]
            else -> null
        }
    }

    /** Subject of a MATMSG: payload ("SUB:"), or null for other formats. */
    fun emailSubject(raw: String): String? {
        val t = raw.trim()
        return if (t.startsWith("MATMSG:", ignoreCase = true)) {
            parseEscapedFields(t.substringAfter(':'))["SUB"]?.ifEmpty { null }
        } else {
            null
        }
    }

    /** Body of a MATMSG: payload ("BODY:"), or null for other formats. */
    fun emailBody(raw: String): String? {
        val t = raw.trim()
        return if (t.startsWith("MATMSG:", ignoreCase = true)) {
            parseEscapedFields(t.substringAfter(':'))["BODY"]?.ifEmpty { null }
        } else {
            null
        }
    }

    /**
     * The message body carried by an SMS code (B-06): the part after the
     * second colon in "smsto:number:body", or the "body=" parameter of an
     * "sms:number?body=..." URI. Null when the code carries no body.
     */
    fun smsBody(raw: String): String? {
        val t = raw.trim()
        val lower = t.lowercase(Locale.ROOT)
        if (!lower.startsWith("smsto:") && !lower.startsWith("sms:")) return null
        val rest = t.substringAfter(':')
        // URI form: sms:number?body=... (percent-encoded)
        if (rest.contains('?')) {
            val query = rest.substringAfter('?')
            for (param in query.split('&')) {
                if (param.startsWith("body=")) {
                    return runCatching {
                        java.net.URLDecoder.decode(param.substringAfter('='), "UTF-8")
                    }.getOrNull()?.ifEmpty { null }
                }
            }
            return null
        }
        // smsto form: everything after the number's colon is the body.
        return if (rest.contains(':')) rest.substringAfter(':').ifEmpty { null } else null
    }
}

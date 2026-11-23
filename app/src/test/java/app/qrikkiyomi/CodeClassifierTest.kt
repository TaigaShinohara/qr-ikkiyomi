package app.qrikkiyomi

import app.qrikkiyomi.core.CodeClassifier
import app.qrikkiyomi.core.CodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeClassifierTest {

    @Test
    fun classify_url() {
        assertEquals(CodeKind.URL, CodeClassifier.classify("https://example.com/page"))
        assertEquals(CodeKind.URL, CodeClassifier.classify("http://example.com"))
        assertEquals(CodeKind.URL, CodeClassifier.classify("example.com/path"))
    }

    @Test
    fun classify_wifi_and_parse() {
        val raw = "WIFI:T:WPA;S:MyHome;P:p@ss\\;word;H:true;;"
        assertEquals(CodeKind.WIFI, CodeClassifier.classify(raw))
        val info = CodeClassifier.parseWifi(raw)
        assertNotNull(info)
        assertEquals("MyHome", info!!.ssid)
        assertEquals("p@ss;word", info.password)
        assertEquals("WPA", info.encryption)
        assertTrue(info.hidden)
    }

    @Test
    fun classify_contact_event_geo_mail_sms_phone_text() {
        assertEquals(CodeKind.CONTACT, CodeClassifier.classify("BEGIN:VCARD\nVERSION:3.0\nFN:山田太郎\nEND:VCARD"))
        assertEquals(CodeKind.CONTACT, CodeClassifier.classify("MECARD:N:山田,太郎;TEL:09012345678;;"))
        assertEquals(CodeKind.EVENT, CodeClassifier.classify("BEGIN:VEVENT\nSUMMARY:会議\nEND:VEVENT"))
        assertEquals(CodeKind.GEO, CodeClassifier.classify("geo:35.6812,139.7671"))
        assertEquals(CodeKind.EMAIL, CodeClassifier.classify("mailto:a@example.com"))
        assertEquals(CodeKind.EMAIL, CodeClassifier.classify("MATMSG:TO:a@example.com;SUB:hi;;"))
        assertEquals(CodeKind.SMS, CodeClassifier.classify("smsto:09012345678:hello"))
        assertEquals(CodeKind.PHONE, CodeClassifier.classify("tel:09012345678"))
        assertEquals(CodeKind.TEXT, CodeClassifier.classify("ただのテキスト"))
    }

    @Test
    fun classify_file_names_and_emails_are_not_urls() {
        // UX-12: bare strings that merely look dotted must not be offered
        // as "open in browser".
        assertEquals(CodeKind.TEXT, CodeClassifier.classify("report.pdf"))
        assertEquals(CodeKind.TEXT, CodeClassifier.classify("name@example.co.jp"))
        assertEquals(CodeKind.TEXT, CodeClassifier.classify("photo.jpg"))
        assertEquals(CodeKind.TEXT, CodeClassifier.classify("a@example.com"))
        // A scheme-ful URL to a file is still a URL.
        assertEquals(CodeKind.URL, CodeClassifier.classify("https://example.com/report.pdf"))
        // And ordinary bare hosts still classify as URLs.
        assertEquals(CodeKind.URL, CodeClassifier.classify("example.com"))
    }

    @Test
    fun parse_geo_text_from_shares() {
        val geoUri = CodeClassifier.parseGeoText("geo:35.6812,139.7671")
        assertNotNull(geoUri)
        assertEquals("35.6812", geoUri!!.lat)
        assertEquals("139.7671", geoUri.lng)

        val mapsQuery = CodeClassifier.parseGeoText("https://www.google.com/maps?q=35.6812,139.7671")
        assertNotNull(mapsQuery)
        assertEquals("35.6812", mapsQuery!!.lat)

        val mapsViewport = CodeClassifier.parseGeoText("https://www.google.com/maps/@35.6812,139.7671,15z")
        assertNotNull(mapsViewport)
        assertEquals("139.7671", mapsViewport!!.lng)

        val bare = CodeClassifier.parseGeoText("東京タワー\n35.6586, 139.7454")
        assertNotNull(bare)
        assertEquals("35.6586", bare!!.lat)

        // A shortened maps link carries no coordinates and cannot be
        // resolved offline, and plain text has none either.
        assertEquals(null, CodeClassifier.parseGeoText("https://maps.app.goo.gl/abc123"))
        assertEquals(null, CodeClassifier.parseGeoText("ただのテキスト"))
        // Out-of-range pairs are not coordinates.
        assertEquals(null, CodeClassifier.parseGeoText("123.4567, 999.9999"))
    }

    @Test
    fun summary_shows_host_and_wifi_ssid() {
        assertEquals("example.com", CodeClassifier.summary("https://example.com/a/b?c=1", CodeKind.URL))
        assertEquals(
            "Wi-Fi: Cafe",
            CodeClassifier.summary("WIFI:T:WPA;S:Cafe;P:x;;", CodeKind.WIFI),
        )
    }

    @Test
    fun parse_wifi_mixed_case_prefix() {
        // B-03: classification accepts "Wifi:" etc., so parsing must too.
        val info = CodeClassifier.parseWifi("Wifi:T:WPA;S:Home;P:secret;;")
        assertNotNull(info)
        assertEquals("Home", info!!.ssid)
        assertEquals("secret", info.password)
        assertEquals("WPA", info.encryption)

        val upper = CodeClassifier.parseWifi("WIFI:T:WEP;S:Cafe;;")
        assertEquals("WEP", upper!!.encryption)
    }

    @Test
    fun bare_url_with_port() {
        // B-05 + B-04: a bare host with a port is a URL, and the URL kept
        // for opening carries a scheme.
        assertEquals(CodeKind.URL, CodeClassifier.classify("example.com:8080/path"))
        val info = CodeClassifier.parseUrl("example.com:8080/path")
        assertNotNull(info)
        assertEquals("example.com", info!!.host)
        assertEquals("https://example.com:8080/path", info.url)

        val bare = CodeClassifier.parseUrl("example.com/path")
        assertEquals("https://example.com/path", bare!!.url)
        // A scheme-ful URL is passed through untouched.
        val full = CodeClassifier.parseUrl("http://example.com/a")
        assertEquals("http://example.com/a", full!!.url)
    }

    @Test
    fun sms_body_extraction() {
        // B-06
        assertEquals("こんにちは", CodeClassifier.smsBody("smsto:09012345678:こんにちは"))
        assertEquals("hello world", CodeClassifier.smsBody("sms:09012345678?body=hello%20world"))
        assertEquals("a:b", CodeClassifier.smsBody("smsto:09012345678:a:b"))
        assertEquals(null, CodeClassifier.smsBody("smsto:09012345678"))
        assertEquals(null, CodeClassifier.smsBody("tel:09012345678"))
    }

    @Test
    fun matmsg_subject_and_body() {
        // B-07 (MATMSG side; mailto keeps its parameters via the raw URI)
        val raw = "MATMSG:TO:a@example.com;SUB:件名です;BODY:本文です;;"
        assertEquals("a@example.com", CodeClassifier.emailAddress(raw))
        assertEquals("件名です", CodeClassifier.emailSubject(raw))
        assertEquals("本文です", CodeClassifier.emailBody(raw))
        assertEquals(null, CodeClassifier.emailSubject("mailto:a@example.com"))
    }

    @Test
    fun escaped_values_are_unescaped_on_read() {
        // B-08: what this app writes escaped must read back unescaped.
        assertEquals(
            "山田,太郎",
            CodeClassifier.contactName("BEGIN:VCARD\nVERSION:3.0\nFN:山田\\,太郎\nEND:VCARD"),
        )
        assertEquals(
            "会議, 東京",
            CodeClassifier.eventField(
                "BEGIN:VEVENT\nSUMMARY:会議\\, 東京\nEND:VEVENT",
                "SUMMARY",
            ),
        )
    }

    @Test
    fun classify_strips_bom() {
        // B-30
        assertEquals(
            CodeKind.CONTACT,
            CodeClassifier.classify("﻿BEGIN:VCARD\nVERSION:3.0\nFN:x\nEND:VCARD"),
        )
        assertEquals(CodeKind.WIFI, CodeClassifier.classify("﻿WIFI:T:WPA;S:x;;"))
    }

    @Test
    fun geo_text_does_not_misfire_on_plain_numbers() {
        // S-2 guards: whole-line integer pairs and numbers inside a
        // sentence are not coordinates.
        assertEquals(null, CodeClassifier.parseGeoText("2026, 11"))
        assertEquals(null, CodeClassifier.parseGeoText("注文は 3.5, 2.5 個です"))
        // A maps URL for a named place carries no coordinates either.
        assertEquals(null, CodeClassifier.parseGeoText("https://www.google.com/maps?q=東京タワー"))
    }

    @Test
    fun url_warnings_shortener_and_idn() {
        val short = CodeClassifier.parseUrl("https://bit.ly/3abc")
        assertNotNull(short)
        assertTrue(short!!.isShortener)
        assertTrue(short.hasWarning)

        val idn = CodeClassifier.parseUrl("https://例え.jp/path")
        assertNotNull(idn)
        assertEquals("xn--r8jz45g.jp", idn!!.punycodeHost)

        val plain = CodeClassifier.parseUrl("https://example.com/")
        assertNotNull(plain)
        assertEquals(null, plain!!.punycodeHost)
        assertTrue(!plain.isShortener)
    }

    // ---- R2-3: display masking (v0.4.0) ----

    @Test
    fun maskWifiPassword_replaces_only_the_password_value() {
        assertEquals(
            "WIFI:T:WPA;S:HomeNet;P:••••••;;",
            CodeClassifier.maskWifiPassword("WIFI:T:WPA;S:HomeNet;P:secret123;;"),
        )
    }

    @Test
    fun maskWifiPassword_respects_escaped_semicolons() {
        // "pa\;ss" is one password containing an escaped ';' — the mask
        // must cover the whole value, not stop at the escaped separator.
        assertEquals(
            "WIFI:T:WPA;S:Net;P:••••••;;",
            CodeClassifier.maskWifiPassword("""WIFI:T:WPA;S:Net;P:pa\;ss;;"""),
        )
    }

    @Test
    fun maskWifiPassword_leaves_open_networks_untouched() {
        val open = "WIFI:T:nopass;S:Cafe;;"
        assertEquals(open, CodeClassifier.maskWifiPassword(open))
        val empty = "WIFI:T:WPA;S:Net;P:;;"
        assertEquals(empty, CodeClassifier.maskWifiPassword(empty))
    }

    @Test
    fun maskWifiPassword_handles_lowercase_field() {
        assertEquals(
            "WIFI:T:WPA;S:Net;p:••••••;;",
            CodeClassifier.maskWifiPassword("WIFI:T:WPA;S:Net;p:abc;;"),
        )
    }

    @Test
    fun maskedForDisplay_only_masks_wifi() {
        assertEquals(
            "WIFI:T:WPA;S:Net;P:••••••;;",
            CodeClassifier.maskedForDisplay("WIFI:T:WPA;S:Net;P:abc;;"),
        )
        assertEquals("https://example.com", CodeClassifier.maskedForDisplay("https://example.com"))
        assertEquals("ただのテキスト", CodeClassifier.maskedForDisplay("ただのテキスト"))
    }

    // ---- P2-12: GEO summary prefers a place name in q= ----

    @Test
    fun geoSummary_prefers_place_name_from_query() {
        val raw = "geo:0,0?q=%E6%9D%B1%E4%BA%AC%E9%A7%85"
        assertEquals(CodeKind.GEO, CodeClassifier.classify(raw))
        assertEquals("場所: 東京駅", CodeClassifier.summary(raw, CodeKind.GEO))
    }

    @Test
    fun geoSummary_coordinate_query_falls_back_to_coordinates() {
        val raw = "geo:35.681,139.767?q=35.681,139.767"
        assertEquals("場所: 35.681,139.767", CodeClassifier.summary(raw, CodeKind.GEO))
    }
}

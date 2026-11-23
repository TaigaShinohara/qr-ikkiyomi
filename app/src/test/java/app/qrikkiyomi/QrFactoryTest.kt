package app.qrikkiyomi

import app.qrikkiyomi.core.CreateType
import app.qrikkiyomi.core.QrFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrFactoryTest {

    @Test
    fun url_adds_scheme_when_missing() {
        assertEquals("https://example.com", QrFactory.buildPayload(CreateType.URL, mapOf("url" to "example.com")))
        assertEquals("http://a.b", QrFactory.buildPayload(CreateType.URL, mapOf("url" to "http://a.b")))
        assertNull(QrFactory.buildPayload(CreateType.URL, mapOf("url" to "  ")))
    }

    @Test
    fun wifi_payload_escapes_and_omits_password_when_open() {
        val payload = QrFactory.buildPayload(
            CreateType.WIFI,
            mapOf("ssid" to "A;B", "password" to "p:1", "encryption" to "WPA"),
        )
        assertEquals("WIFI:T:WPA;S:A\\;B;P:p\\:1;;", payload)

        val open = QrFactory.buildPayload(
            CreateType.WIFI,
            mapOf("ssid" to "Cafe", "encryption" to "nopass"),
        )
        assertEquals("WIFI:T:nopass;S:Cafe;;", open)
    }

    @Test
    fun contact_payload_is_vcard() {
        val payload = QrFactory.buildPayload(
            CreateType.CONTACT,
            mapOf("name" to "山田太郎", "tel" to "09012345678", "email" to "a@example.com"),
        )
        assertNotNull(payload)
        assertTrue(payload!!.startsWith("BEGIN:VCARD"))
        assertTrue(payload.contains("FN:山田太郎"))
        assertTrue(payload.contains("TEL:09012345678"))
    }

    @Test
    fun event_payload_formats_datetime() {
        val payload = QrFactory.buildPayload(
            CreateType.EVENT,
            mapOf("title" to "会議", "start" to "2026-11-20 10:30", "end" to "2026-11-20 11:30"),
        )
        assertNotNull(payload)
        assertTrue(payload!!.contains("DTSTART:20261120T1030"))
        assertTrue(payload.contains("DTEND:20261120T1130"))
    }

    @Test
    fun mailto_and_geo_and_sms() {
        assertEquals(
            "mailto:a@example.com?subject=Hi%20there",
            QrFactory.buildPayload(CreateType.EMAIL, mapOf("email" to "a@example.com", "subject" to "Hi there")),
        )
        assertEquals(
            "geo:35.0,139.0",
            QrFactory.buildPayload(CreateType.GEO, mapOf("lat" to "35.0", "lng" to "139.0")),
        )
        assertNull(QrFactory.buildPayload(CreateType.GEO, mapOf("lat" to "35.0")))
        assertEquals(
            "tel:09012345678",
            QrFactory.buildPayload(CreateType.SMS_PHONE, mapOf("number" to "09012345678", "mode" to "tel")),
        )
    }

    @Test
    fun event_datetime_validation() {
        // UX-8: accepted formats normalise; anything else is rejected
        // instead of silently producing a broken iCalendar payload.
        assertEquals("20261120T103000", QrFactory.normalizeEventDateTime("2026-11-20 10:30"))
        assertEquals("20261120T000000", QrFactory.normalizeEventDateTime("2026-11-20"))
        assertTrue(QrFactory.isValidEventDateTime("2026/11/20 10:30"))
        assertEquals(null, QrFactory.normalizeEventDateTime("来週の金曜 10時"))
        assertEquals(null, QrFactory.normalizeEventDateTime("2026-13-40 10:30"))
        assertEquals(null, QrFactory.normalizeEventDateTime("2026-11-20 10:30xyz"))
        assertNull(
            QrFactory.buildPayload(
                CreateType.EVENT,
                mapOf("title" to "会議", "start" to "あした10時"),
            )
        )
        // Coordinates outside their ranges are refused too.
        assertNull(QrFactory.buildPayload(CreateType.GEO, mapOf("lat" to "999", "lng" to "139.0")))
        assertNull(QrFactory.buildPayload(CreateType.GEO, mapOf("lat" to "abc", "lng" to "139.0")))
    }

    @Test
    fun ical_datetime_parse() {
        val millis = QrFactory.parseIcalDateTime("20261120T103000")
        assertNotNull(millis)
        assertNull(QrFactory.parseIcalDateTime("not-a-date"))
    }

    @Test
    fun wifi_payload_escapes_quote() {
        // B-10: '"' is one of the WIFI format's special characters.
        val payload = QrFactory.buildPayload(
            CreateType.WIFI,
            mapOf("ssid" to "Cafe", "password" to "p\"1\\2", "encryption" to "WPA"),
        )
        assertEquals("WIFI:T:WPA;S:Cafe;P:p\\\"1\\\\2;;", payload)
    }

    @Test
    fun event_end_before_start_is_refused() {
        // B-11
        assertNull(
            QrFactory.buildPayload(
                CreateType.EVENT,
                mapOf(
                    "title" to "会議",
                    "start" to "2026-11-20 11:30",
                    "end" to "2026-11-20 10:30",
                ),
            )
        )
        // Same start and end is a valid (instant) event.
        assertNotNull(
            QrFactory.buildPayload(
                CreateType.EVENT,
                mapOf(
                    "title" to "会議",
                    "start" to "2026-11-20 10:30",
                    "end" to "2026-11-20 10:30",
                ),
            )
        )
    }

    @Test
    fun event_payload_has_uid_and_dtstamp() {
        // B-18: RFC 5545 requires both in a VEVENT.
        val payload = QrFactory.buildPayload(
            CreateType.EVENT,
            mapOf("title" to "会議", "start" to "2026-11-20 10:30"),
        )
        assertNotNull(payload)
        assertTrue(payload!!.contains("\nUID:"))
        assertTrue(Regex("DTSTAMP:\\d{8}T\\d{6}Z").containsMatchIn(payload))
    }

    @Test
    fun geo_payload_uses_validated_numbers() {
        // B-16: raw input text must not leak into the geo: URI.
        assertEquals(
            "geo:35.0,139.0",
            QrFactory.buildPayload(CreateType.GEO, mapOf("lat" to "3.5e1", "lng" to "139.")),
        )
    }

    @Test
    fun url_scheme_only_is_refused() {
        // B-17
        assertNull(QrFactory.buildPayload(CreateType.URL, mapOf("url" to "https://")))
        assertNull(QrFactory.buildPayload(CreateType.URL, mapOf("url" to "http://")))
    }

    @Test
    fun ical_parse_rejects_invalid_dates() {
        // B-09: lenient parsing used to roll month 13 into the next year,
        // and trailing garbage was silently ignored.
        assertNull(QrFactory.parseIcalDateTime("20261301T103000"))
        assertNull(QrFactory.parseIcalDateTime("20260230T103000"))
        assertNull(QrFactory.parseIcalDateTime("20261120T103000xyz"))
        assertNull(QrFactory.parseIcalDateTime("20261120T256000"))
        assertNotNull(QrFactory.parseIcalDateTime("20261120T103000Z"))
        assertNotNull(QrFactory.parseIcalDateTime("20261120"))
    }

    // ---- R2-4: Wi-Fi creation requires a password for secured types ----

    @Test
    fun wifi_payload_requires_password_for_secured_types() {
        val base = mapOf("ssid" to "HomeNet", "password" to "")
        assertNull(QrFactory.buildPayload(CreateType.WIFI, base + ("encryption" to "WPA")))
        assertNull(QrFactory.buildPayload(CreateType.WIFI, base + ("encryption" to "WEP")))
        assertNotNull(QrFactory.buildPayload(CreateType.WIFI, base + ("encryption" to "nopass")))
        assertNotNull(
            QrFactory.buildPayload(
                CreateType.WIFI,
                mapOf("ssid" to "HomeNet", "password" to "secret", "encryption" to "WPA"),
            )
        )
    }

    // ---- R2-5: encodability is the save gate ----

    @Test
    fun canEncode_accepts_normal_and_rejects_over_capacity() {
        assertTrue(QrFactory.canEncode("https://example.com"))
        // Version-40 byte-mode capacity tops out at 2,953 bytes (EC L);
        // 4,000 ASCII chars cannot fit at any error-correction level.
        val huge = "a".repeat(4000)
        assertTrue(!QrFactory.canEncode(huge))
        assertTrue(
            !QrFactory.canEncode(
                huge,
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
            )
        )
    }

    // ---- R2-8: event date formatting for the result sheet ----

    @Test
    fun formatEventDateTime_is_human_readable() {
        // The formatter renders in the device default timezone, so the
        // fixture instant is built in that same timezone.
        val cal = java.util.Calendar.getInstance()
        cal.set(2026, 10, 20, 10, 30, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val text = QrFactory.formatEventDateTime(cal.timeInMillis)
        assertTrue(text.contains("2026/11/20"))
        assertTrue(text.contains("10:30"))
    }
}

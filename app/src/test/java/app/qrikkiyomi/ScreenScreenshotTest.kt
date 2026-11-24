package app.qrikkiyomi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.qrikkiyomi.core.CodeKind
import app.qrikkiyomi.core.CreateType
import app.qrikkiyomi.core.Entry
import app.qrikkiyomi.core.Origin
import app.qrikkiyomi.ui.CreateScreen
import app.qrikkiyomi.ui.CreateState
import app.qrikkiyomi.ui.DetectedCode
import app.qrikkiyomi.ui.LibraryScreen
import app.qrikkiyomi.ui.QrTheme
import app.qrikkiyomi.ui.ResultContent
import app.qrikkiyomi.ui.ScanScreen
import app.qrikkiyomi.ui.ScanState
import app.qrikkiyomi.ui.SettingsScreen
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pre-delivery UI verification (docs/VERIFICATION.md): the app's main
 * screens rendered on the JVM by Robolectric's native graphics and
 * pinned to golden screenshots by Roborazzi. There is no emulator in
 * the build VM, so this is what catches a collapsed / overflowing /
 * blank layout before an APK ships.
 *
 * Golden files live in src/test/screenshots/. Record them with
 * `./run-tests.sh record`, verify with `./run-tests.sh verify`.
 *
 * The shots are taken with Roborazzi's composable capture, which hosts
 * the content in its own Robolectric-registered activity — the Compose
 * test rule's ComponentActivity cannot be resolved by Robolectric
 * because no manifest declares it.
 *
 * The content is wrapped in a Surface with the theme background, the
 * same content-colour provision the real screens get from MainActivity's
 * Scaffold. Without it, LocalContentColor stays black in the capture
 * host and every Text without an explicit colour renders invisibly
 * black-on-black — a harness artifact, not app behaviour (checked
 * against the device-confirmed screens 2026-11-23).
 *
 * The night qualifier pins the dark colour scheme (the art direction's
 * primary presentation) so goldens are stable across machines.
 *
 * The scan screen is covered in the states that do not need a real
 * camera: the permission-rationale state (what a first launch shows —
 * this app has no onboarding flow; the first-run hint lives on this
 * screen once the camera is live) and the image mode with detections.
 * The live-camera state itself cannot be rendered without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "w411dp-h891dp-night",
    application = QrTestApplication::class,
)
class ScreenScreenshotTest {

    private fun shot(name: String, content: @Composable () -> Unit) {
        val dir = File("src/test/screenshots")
        dir.mkdirs()
        captureRoboImage(file = File(dir, name)) {
            QrTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
    }

    private fun scanScreenContent(state: ScanState): @Composable () -> Unit = {
        ScanScreen(
            state = state,
            sharedImageUri = null,
            onSharedImageConsumed = {},
            autoSaveHistory = true,
            autoSaveOnDetect = true,
            vibrateOnDetect = true,
            multiHintDone = false,
            onMultiHintDone = {},
            permissionRequestedBefore = false,
            onPermissionRequested = {},
            isInLibrary = { false },
            onScanned = {},
            onSaveEntries = { 0 },
            onMakeQrFromText = {},
        )
    }

    /** First launch: no camera permission yet — reason first, then the ask. */
    @Test
    fun scan_permissionRationale() {
        shot("scan_permission.png", scanScreenContent(ScanState()))
    }

    /** Image mode: the still image, its pins, and the result list below. */
    @Test
    fun scan_imageModeWithDetections() {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(0xFFDDDDDD.toInt())
            val paint = Paint().apply { color = 0xFF222222.toInt() }
            drawRect(150f, 120f, 350f, 320f, paint)
            drawRect(480f, 300f, 640f, 460f, paint)
        }
        val state = ScanState().apply {
            imageMode = true
            imageBitmap = bitmap
            // v0.4.3 (MINIMAL N-1): the real flow no longer sets a
            // success note for image scans — the list heading announces
            // the count — so the fixture does not set one either.
            detections.addAll(
                listOf(
                    DetectedCode(
                        rawValue = "https://example.com/first",
                        formatName = "QR_CODE",
                        kind = CodeKind.URL,
                        count = 1,
                        centerX = 250f, centerY = 220f,
                        frameW = 800, frameH = 600,
                    ),
                    DetectedCode(
                        rawValue = "WIFI:T:WPA;S:HomeWifi;P:secret123;;",
                        formatName = "QR_CODE",
                        kind = CodeKind.WIFI,
                        count = 2,
                        centerX = 560f, centerY = 380f,
                        frameW = 800, frameH = 600,
                    ),
                )
            )
        }
        shot("scan_image_mode.png", scanScreenContent(state))
    }

    /** Create: URL type with input — live QR preview pinned on top. */
    @Test
    fun create_urlForm() {
        val state = CreateState().apply {
            type = CreateType.URL
            fields["url"] = "https://example.com/qr-ikkiyomi"
        }
        shot("create_url.png") {
            CreateScreen(state = state, onSaved = {}, onGoLibrary = {})
        }
    }

    /** Library with nothing in it: the centred empty guide. */
    @Test
    fun library_empty() {
        shot("library_empty.png") {
            LibraryScreen(
                entries = emptyList(),
                onUpdate = {}, onAdd = {}, onDelete = {},
                onEditCreated = {}, onMakeQrFromText = {}, onGoScan = {},
            )
        }
    }

    /** Library with a created QR, a favourite scan, and a folder. */
    @Test
    fun library_entries() {
        val entries = listOf(
            Entry(
                id = "shot-1",
                origin = Origin.CREATED,
                rawValue = "https://example.com/my-qr",
                formatName = "QR_CODE",
                kind = CodeKind.URL,
                createdAt = 1763676000000L,
                createType = CreateType.URL,
                fields = mapOf("url" to "https://example.com/my-qr"),
            ),
            Entry(
                id = "shot-2",
                origin = Origin.SCANNED,
                rawValue = "WIFI:T:WPA;S:CafeWifi;P:coffee123;;",
                formatName = "QR_CODE",
                kind = CodeKind.WIFI,
                createdAt = 1763589600000L,
                favorite = true,
                folder = "お店",
                memo = "レジ横のWi-Fi",
            ),
            Entry(
                id = "shot-3",
                origin = Origin.SCANNED,
                rawValue = "4901234567890",
                formatName = "EAN_13",
                kind = CodeKind.TEXT,
                createdAt = 1763503200000L,
            ),
        )
        shot("library_entries.png") {
            LibraryScreen(
                entries = entries,
                onUpdate = {}, onAdd = {}, onDelete = {},
                onEditCreated = {}, onMakeQrFromText = {}, onGoScan = {},
            )
        }
    }

    /** Settings: defaults as a fresh install shows them, with 3 entries. */
    @Test
    fun settings_defaults() {
        shot("settings.png") {
            SettingsScreen(
                autoSaveOnDetect = true,
                onToggleAutoSaveOnDetect = {},
                autoSaveHistory = true,
                onToggleAutoSave = {},
                vibrateOnDetect = true,
                onToggleVibrate = {},
                autoDeleteDays = 0,
                onSetAutoDeleteDays = {},
                onExportBackup = {},
                onImportBackup = {},
                onDeleteAllHistory = {},
                entryCount = 3,
            )
        }
    }

    /** Result sheet content for a shortened URL: host first, warning shown. */
    @Test
    fun resultSheet_shortUrl() {
        shot("result_short_url.png") {
            ResultContent(
                rawValue = "https://bit.ly/3AbCdEf",
                formatName = "QR_CODE",
                onMakeQrFromText = {},
            )
        }
    }
}

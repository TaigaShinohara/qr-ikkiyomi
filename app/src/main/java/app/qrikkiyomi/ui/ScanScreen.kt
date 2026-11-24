package app.qrikkiyomi.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.qrikkiyomi.core.AppearanceTracker
import app.qrikkiyomi.core.CodeClassifier
import app.qrikkiyomi.core.CodeKind
import app.qrikkiyomi.core.Entry
import app.qrikkiyomi.core.Origin
import app.qrikkiyomi.core.ZoomControl
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One detected code in the current frame (duplicates already merged). */
data class DetectedCode(
    val rawValue: String,
    val formatName: String,
    val kind: CodeKind,
    val count: Int,
    val centerX: Float, // in rotated-frame coordinates, -1 when unknown (image import)
    val centerY: Float,
    val frameW: Int,
    val frameH: Int,
)

/**
 * R2-6: the scan screen's working state, held at Activity level so a
 * tab switch no longer discards unsaved detections (or an image being
 * examined). Camera resources are still released when the screen
 * leaves — only the *results* are kept.
 */
class ScanState {
    val detections: SnapshotStateList<DetectedCode> = mutableStateListOf()
    val missCounts: MutableMap<String, Int> = mutableMapOf()
    var selected by mutableStateOf<DetectedCode?>(null)
    /** P2-10: the code whose sheet was opened last, kept highlighted. */
    var openedRaw by mutableStateOf<String?>(null)
    /** P2-5: whether [selected] was already in the library when opened. */
    var selectedAlreadySaved by mutableStateOf(false)
    var torchOn by mutableStateOf(false)
    var zoom by mutableStateOf(1f)
    /** v0.4.5: the bound camera's real zoom range, adopted from its
     * zoomState at bind time; the defaults apply until then (and on
     * cameras that never report one). All three zoom controls —
     * preset chips, pinch, slider — clamp to this range. */
    var zoomMin by mutableStateOf(ZoomControl.DEFAULT_MIN)
    var zoomMax by mutableStateOf(ZoomControl.DEFAULT_MAX)
    var fromImageNote by mutableStateOf<String?>(null)
    /** R2-1: while true, the still image (not the live camera) is shown
     * and its results are never overwritten by camera analysis. */
    var imageMode by mutableStateOf(false)
    var imageBitmap by mutableStateOf<Bitmap?>(null)
    /** P2-9: an image decode/scan is in flight. */
    var imageScanning by mutableStateOf(false)
    /** v0.4.2: which raw values are currently "present", so auto-save
     * on detection fires once per appearance, not per frame. */
    val appearanceTracker = AppearanceTracker()
}

private fun formatNameOf(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR_CODE"
    Barcode.FORMAT_AZTEC -> "AZTEC"
    Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_EAN_13 -> "EAN_13"
    Barcode.FORMAT_EAN_8 -> "EAN_8"
    Barcode.FORMAT_UPC_A -> "UPC_A"
    Barcode.FORMAT_UPC_E -> "UPC_E"
    Barcode.FORMAT_CODE_128 -> "CODE_128"
    Barcode.FORMAT_CODE_39 -> "CODE_39"
    Barcode.FORMAT_CODE_93 -> "CODE_93"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_CODABAR -> "CODABAR"
    else -> "UNKNOWN"
}

private fun mergeBarcodes(barcodes: List<Barcode>, frameW: Int, frameH: Int): List<DetectedCode> {
    val byRaw = LinkedHashMap<String, MutableList<Barcode>>()
    for (b in barcodes) {
        val raw = b.rawValue ?: continue
        byRaw.getOrPut(raw) { mutableListOf() }.add(b)
    }
    return byRaw.entries.map { (raw, list) ->
        val first = list.first()
        val box: Rect? = first.boundingBox
        DetectedCode(
            rawValue = raw,
            formatName = formatNameOf(first.format),
            kind = CodeClassifier.classify(raw),
            count = list.size,
            centerX = box?.exactCenterX() ?: -1f,
            centerY = box?.exactCenterY() ?: -1f,
            frameW = frameW,
            frameH = frameH,
        )
    }
}

/**
 * R-1 smoothing: a code that stays in view keeps (nearly) its previous pin
 * position instead of jumping with every frame's bounding-box jitter.
 * Tiny movements are ignored entirely; larger ones are halved per frame.
 */
private fun smoothPosition(old: DetectedCode, fresh: DetectedCode): DetectedCode {
    if (old.centerX < 0 || fresh.centerX < 0) return fresh
    if (old.frameW != fresh.frameW || old.frameH != fresh.frameH) return fresh
    val threshold = old.frameW * 0.015f
    val dx = fresh.centerX - old.centerX
    val dy = fresh.centerY - old.centerY
    val nx = if (abs(dx) < threshold) old.centerX else old.centerX + dx * 0.5f
    val ny = if (abs(dy) < threshold) old.centerY else old.centerY + dy * 0.5f
    return fresh.copy(centerX = nx, centerY = ny)
}

/** Decodes a picked/shared image, downsampled so a huge photo cannot OOM. */
private fun decodeSampledBitmap(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    state: ScanState,
    sharedImageUri: Uri?,
    onSharedImageConsumed: () -> Unit,
    autoSaveHistory: Boolean,
    autoSaveOnDetect: Boolean,
    vibrateOnDetect: Boolean,
    multiHintDone: Boolean,
    onMultiHintDone: () -> Unit,
    permissionRequestedBefore: Boolean,
    onPermissionRequested: () -> Unit,
    isInLibrary: (String) -> Boolean,
    onScanned: (Entry) -> Unit,
    onSaveEntries: (List<Entry>) -> Int,
    onMakeQrFromText: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    // UX-6: no automatic permission request on start. The reason is shown
    // in the camera area and the request goes out only from its button.

    // B-24: the permission can also change while the app is away (the user
    // grants it in system Settings after a denial); re-check on resume so
    // the screen does not keep asking for what it already has.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // R2-7: "denied once, and the system will not show the dialog again"
    // is the state that used to dead-end at the same button. It is told
    // apart by (asked before) + (no rationale to show now).
    val activity = context as? Activity
    val permanentlyDenied = !hasPermission && permissionRequestedBefore && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

    val detections = state.detections
    val sheetOpen by rememberUpdatedState(state.selected != null)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // R2-7 / P2-9: camera start is a visible state, and a failed start is
    // a recoverable one — not a silent black rectangle.
    var cameraBound by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var cameraRetryKey by remember { mutableStateOf(0) }

    fun entryOf(d: DetectedCode) = Entry(
        origin = Origin.SCANNED,
        rawValue = d.rawValue,
        formatName = d.formatName,
        kind = d.kind,
    )

    // v0.4.5: the one door every zoom control goes through — clamp to
    // the camera's range, publish to state (so the slider, the chips'
    // selected state and the next pinch all see it at once), then push
    // to the camera. The pinch handler inside the PreviewView factory
    // cannot call this (it is bound to the composition that created
    // the view), so it performs the same two steps inline there.
    fun applyZoom(ratio: Float) {
        val clamped = ZoomControl.clamp(ratio, state.zoomMin, state.zoomMax)
        state.zoom = clamped
        camera?.cameraControl?.setZoomRatio(clamped)
    }

    // v0.4.2 (FR-SCAN-07 revised): with auto-save-on-detect on, a code is
    // written to history the moment it appears in the detection set —
    // once per appearance (AppearanceTracker), never per frame. The
    // store layer still dedupes against existing history, so a code that
    // leaves and returns does not produce a duplicate row, and a code
    // saved this way reads as "保存済み" everywhere (P2-5).
    fun autoSaveAppeared(current: List<DetectedCode>) {
        if (!autoSaveOnDetect) return
        val byRaw = current.associateBy { it.rawValue }
        state.appearanceTracker.onDetections(current.map { it.rawValue })
            .forEach { raw -> byRaw[raw]?.let { onScanned(entryOf(it)) } }
    }

    fun handleDetected(list: List<DetectedCode>, fromImage: Boolean) {
        // R2-1: in image mode the still image's results are the state;
        // a live camera frame must never replace them.
        if (state.imageMode && !fromImage) return
        val prevRaws = detections.map { it.rawValue }.toSet()
        val grew = list.any { it.rawValue !in prevRaws }
        // R-2 / UX-2: vibrate once when the set grows, never per code.
        if (grew && vibrateOnDetect) vibrateOnce(context)

        if (fromImage) {
            // A still image is a single shot: replace wholesale.
            detections.clear()
            detections.addAll(list)
            state.missCounts.clear()
            state.openedRaw = null
            // v0.4.3 (MINIMAL N-1): no success note — the result list's
            // heading already announces the count, and saying it twice
            // was a re-display. Notes remain for the empty case, where
            // the note is the only signal.
            state.fromImageNote = if (list.isEmpty()) "画像の中にコードは見つかりませんでした" else null
            // A fresh image is a fresh appearance for everything in it.
            state.appearanceTracker.reset()
            autoSaveAppeared(list)
            return
        }

        // B-26: a note from image reading ("画像から○件…") should not
        // linger once the camera is finding codes on its own again.
        if (state.fromImageNote != null) state.fromImageNote = null

        // R-1 / UX-1: diff update. Codes already shown keep their slot in
        // the list (first-seen order) and a smoothed position; brand-new
        // codes append at the end; briefly-missing codes are held for up
        // to 2 frames before dropping out.
        val freshByRaw = list.associateBy { it.rawValue }
        val merged = mutableListOf<DetectedCode>()
        for (old in detections) {
            val fresh = freshByRaw[old.rawValue]
            if (fresh != null) {
                state.missCounts.remove(old.rawValue)
                merged += smoothPosition(old, fresh)
            } else {
                val misses = (state.missCounts[old.rawValue] ?: 0) + 1
                if (misses <= 2) {
                    state.missCounts[old.rawValue] = misses
                    merged += old
                } else {
                    state.missCounts.remove(old.rawValue)
                }
            }
        }
        for (fresh in list) {
            if (fresh.rawValue !in prevRaws) merged += fresh
        }
        detections.clear()
        detections.addAll(merged)
        // The tracker sees the merged set (including briefly-held codes),
        // so a 1–2 frame flicker does not count as a new appearance.
        autoSaveAppeared(merged)
    }

    // R-2 (revised in v0.4.2): opening a code still saves it when the
    // "開いたコードを履歴に保存" setting is on; with auto-save-on-detect
    // on (the default) the code is normally in history already by then.
    fun openResult(d: DetectedCode) {
        // P2-5: capture "was already saved" before the auto-save below
        // makes the question meaningless.
        state.selectedAlreadySaved = isInLibrary(d.rawValue)
        state.selected = d
        state.openedRaw = d.rawValue
        // P2-1: the first-run hint's job is done once the user has
        // actually opened a result — single-code users included.
        if (!multiHintDone) onMultiHintDone()
        if (autoSaveHistory) onScanned(entryOf(d))
    }

    fun saveAllDetected() {
        if (detections.isEmpty()) return
        val added = onSaveEntries(detections.map { entryOf(it) })
        // P2-5: say what really happened, including "nothing was new".
        val message = when {
            added == detections.size -> "履歴に保存しました"
            added == 0 -> "すべて保存済みです"
            else -> "${added}件を保存しました（${detections.size - added}件は保存済み）"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun exitImageMode() {
        state.imageMode = false
        state.imageBitmap = null
        detections.clear()
        state.missCounts.clear()
        state.fromImageNote = null
        state.openedRaw = null
        state.appearanceTracker.reset()
    }

    // D-5: the first-run hint also retires itself the moment a multi
    // detection actually happens; no onboarding screens are added.
    LaunchedEffect(detections.size) {
        if (!multiHintDone && detections.size >= 2) onMultiHintDone()
    }

    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        BarcodeScanning.getClient(options)
    }

    // B-12: the camera is bound to the Activity lifecycle, so without an
    // explicit release it keeps analysing (and vibrating) while the user
    // is on another tab. These are hoisted so DisposableEffect below can
    // unbind the camera and stop the analyzer executor when this screen
    // leaves the composition; cameraGone also covers the race where the
    // provider future completes after disposal.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val cameraGone = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val scanScope = rememberCoroutineScope()
    fun scanImageUri(uri: Uri) {
        scanScope.launch {
            state.imageScanning = true
            // B-15: decoding a picked/shared photo (possibly tens of
            // megapixels) on the main thread can freeze or OOM the UI;
            // read it on IO, scan afterwards.
            val bitmap = withContext(Dispatchers.IO) {
                decodeSampledBitmap(context, uri)
            }
            if (bitmap == null) {
                state.imageScanning = false
                state.fromImageNote = "画像を開けませんでした"
                return@launch
            }
            // R2-1: from here the still image is the state — it is shown,
            // its results are kept, and the live camera stays out of it.
            state.imageBitmap = bitmap
            state.imageMode = true
            val image = InputImage.fromBitmap(bitmap, 0)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    state.imageScanning = false
                    handleDetected(
                        mergeBarcodes(barcodes, bitmap.width, bitmap.height),
                        fromImage = true,
                    )
                }
                .addOnFailureListener {
                    state.imageScanning = false
                    state.fromImageNote = "画像の読み取りに失敗しました"
                }
        }
    }

    LaunchedEffect(sharedImageUri) {
        if (sharedImageUri != null) {
            scanImageUri(sharedImageUri)
            onSharedImageConsumed()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) scanImageUri(uri) }

    val imageModeActive = state.imageMode && state.imageBitmap != null

    // v0.4.2: once codes are on screen in camera mode, the result list —
    // not the preview — gets the larger share of the screen (preview
    // ≈ 2 : list ≈ 3 of the flexible height), so a full sweep can be
    // read at a glance. Pin mapping is unaffected: pins are positioned
    // from the preview box's measured size (viewSize) either way.
    // Image mode keeps the previous layout, as does the empty state.
    val wideList = !imageModeActive && detections.isNotEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .weight(if (wideList) 2f else 1f)
                .background(Color.Black)
                .onSizeChanged { viewSize = it },
        ) {
            if (imageModeActive) {
                // ---- R2-1: image mode — the still image and its pins ----
                val bitmap = state.imageBitmap!!
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "読み取り中の画像",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                val density = LocalDensity.current
                detections.forEachIndexed { index, d ->
                    if (d.centerX >= 0 && d.frameW > 0 && viewSize.width > 0) {
                        // Fit letterboxing: the whole image is visible.
                        val scale = min(
                            viewSize.width.toFloat() / d.frameW,
                            viewSize.height.toFloat() / d.frameH,
                        )
                        val drawnW = d.frameW * scale
                        val drawnH = d.frameH * scale
                        val offX = (viewSize.width - drawnW) / 2f
                        val offY = (viewSize.height - drawnH) / 2f
                        val px = offX + d.centerX * scale
                        val py = offY + d.centerY * scale
                        val pinSizePx = with(density) { 30.dp.toPx() }
                        DetectionPin(
                            index = index,
                            d = d,
                            opened = state.openedRaw == d.rawValue,
                            modifier = Modifier.offset {
                                IntOffset(
                                    (px - pinSizePx / 2).roundToInt(),
                                    (py - pinSizePx / 2).roundToInt(),
                                )
                            },
                            onClick = { openResult(d) },
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    TextButton(onClick = { exitImageMode() }) {
                        Text("カメラに戻る")
                    }
                }
            } else if (hasPermission) {
                key(cameraRetryKey) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                                providerFuture.addListener({
                                    val provider = providerFuture.get()
                                    cameraProvider = provider
                                    if (cameraGone.get()) {
                                        // Disposed while the provider was loading.
                                        provider.unbindAll()
                                    } else {
                                        val preview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(surfaceProvider)
                                        }
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                        analysis.setAnalyzer(analysisExecutor) { proxy ->
                                            if (sheetOpen) {
                                                proxy.close()
                                            } else {
                                                analyzeProxy(scanner, proxy) { list ->
                                                    // Back on the main thread via listener executor below.
                                                    handleDetected(list, fromImage = false)
                                                }
                                            }
                                        }
                                        try {
                                            provider.unbindAll()
                                            camera = provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                analysis,
                                            )
                                            // B-25: a freshly bound camera starts
                                            // with torch off and zoom 1x; re-apply
                                            // the state the UI already shows.
                                            camera?.cameraControl?.enableTorch(state.torchOn)
                                            if (state.zoom != 1f) {
                                                camera?.cameraControl?.setZoomRatio(state.zoom)
                                            }
                                            // v0.4.5: adopt this camera's real
                                            // zoom range, so the presets,
                                            // pinch and slider all clamp to
                                            // what the hardware can do (a
                                            // range that reads as broken is
                                            // ignored, keeping the defaults).
                                            camera?.cameraInfo?.zoomState?.value?.let { zs ->
                                                if (zs.maxZoomRatio > zs.minZoomRatio) {
                                                    state.zoomMin = zs.minZoomRatio
                                                    state.zoomMax = zs.maxZoomRatio
                                                    val clamped = ZoomControl.clamp(
                                                        state.zoom, state.zoomMin, state.zoomMax,
                                                    )
                                                    if (clamped != state.zoom) {
                                                        state.zoom = clamped
                                                        camera?.cameraControl?.setZoomRatio(clamped)
                                                    }
                                                }
                                            }
                                            cameraBound = true
                                            cameraFailed = false
                                        } catch (e: Exception) {
                                            // R2-7: a busy/broken camera is a
                                            // state the user can act on, not
                                            // a silent black rectangle.
                                            cameraFailed = true
                                        }
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                // v0.4.5: pinch zoom on the preview. The
                                // detector and the tap-to-focus share this
                                // one listener; any touch sequence that
                                // involved a second finger (or an actual
                                // scale) is marked, and its final ACTION_UP
                                // must not fall through to focusing.
                                var pinchZoom = state.zoom
                                var sawMultiTouch = false
                                val scaleDetector = ScaleGestureDetector(
                                    ctx,
                                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                                            pinchZoom = state.zoom
                                            sawMultiTouch = true
                                            return true
                                        }

                                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                                            // Same two steps as applyZoom in
                                            // the composable (clamp → state →
                                            // camera), inlined: this listener
                                            // is bound to the composition that
                                            // created the view.
                                            pinchZoom = ZoomControl.scaled(
                                                pinchZoom, detector.scaleFactor,
                                                state.zoomMin, state.zoomMax,
                                            )
                                            state.zoom = pinchZoom
                                            camera?.cameraControl?.setZoomRatio(pinchZoom)
                                            return true
                                        }
                                    },
                                )
                                setOnTouchListener { v, event ->
                                    scaleDetector.onTouchEvent(event)
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_POINTER_DOWN -> sawMultiTouch = true
                                        MotionEvent.ACTION_UP -> {
                                            if (!sawMultiTouch && !scaleDetector.isInProgress) {
                                                val cam = camera
                                                if (cam != null) {
                                                    val point = meteringPointFactory.createPoint(event.x, event.y)
                                                    val action = FocusMeteringAction.Builder(point).build()
                                                    cam.cameraControl.startFocusAndMetering(action)
                                                }
                                                v.performClick()
                                            }
                                            sawMultiTouch = false
                                        }
                                        MotionEvent.ACTION_CANCEL -> sawMultiTouch = false
                                    }
                                    true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Detection pins (catalog: DetectionPin)
                val density = LocalDensity.current
                detections.forEachIndexed { index, d ->
                    if (d.centerX >= 0 && d.frameW > 0 && viewSize.width > 0) {
                        val scale = max(
                            viewSize.width.toFloat() / d.frameW,
                            viewSize.height.toFloat() / d.frameH,
                        )
                        val drawnW = d.frameW * scale
                        val drawnH = d.frameH * scale
                        val offX = (viewSize.width - drawnW) / 2f
                        val offY = (viewSize.height - drawnH) / 2f
                        val px = offX + d.centerX * scale
                        val py = offY + d.centerY * scale
                        val pinSizePx = with(density) { 30.dp.toPx() }
                        DetectionPin(
                            index = index,
                            d = d,
                            opened = state.openedRaw == d.rawValue,
                            modifier = Modifier.offset {
                                IntOffset(
                                    (px - pinSizePx / 2).roundToInt(),
                                    (py - pinSizePx / 2).roundToInt(),
                                )
                            },
                            onClick = { openResult(d) },
                        )
                    }
                }

                // D-5: first-run only, one quiet line over the camera.
                if (!multiHintDone) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "複数のQRも一度に読めます",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // v0.4.5: preset zoom chips along the preview's lower
                // edge — one tap to a fixed ratio. The selected chip is
                // the only place the current ratio is spelled out (the
                // slider's thumb position says the same thing in shape);
                // no separate numeric readout is added. Shown only
                // while a live camera is actually bound.
                if (cameraBound && !cameraFailed) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ZoomControl.visiblePresets(state.zoomMin, state.zoomMax).forEach { preset ->
                            val selected = ZoomControl.isSelected(state.zoom, preset)
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                        }
                                    )
                                    .clickable(role = Role.Button) { applyZoom(preset) }
                                    .semantics {
                                        contentDescription = "ズーム ${ZoomControl.label(preset)}"
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    ZoomControl.label(preset),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }

                // P2-9: camera start is a visible, quiet state.
                if (!cameraBound && !cameraFailed) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("カメラを起動しています", color = Color.White)
                    }
                }
                // R2-7: failed start — say so, and offer the two ways out.
                if (cameraFailed) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "カメラを起動できませんでした",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryActionButton("もう一度試す") {
                            cameraFailed = false
                            cameraBound = false
                            cameraRetryKey++
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { imagePicker.launch("image/*") }) {
                            Text("画像から読む", color = Color.White)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    // UX-6: the reason comes before the permission request.
                    Text(
                        "QRを読むためにカメラを使います。画像は端末の外に送りません。",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "許可するとその場で読み取れます。許可しなくても「画像から読む」「作る」「宝箱」は使えます。",
                        color = Color.White,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (permanentlyDenied) {
                        // R2-7: the system dialog will never appear again;
                        // the only honest route is the system Settings.
                        Text(
                            "カメラの許可がオフになっています。端末の設定で許可すると読み取れるようになります。",
                            color = Color.White,
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryActionButton("端末の設定を開く") {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            }
                        }
                    } else {
                        PrimaryActionButton("カメラを許可する") {
                            onPermissionRequested()
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            }

            // P2-9: image work in flight, whichever mode we are in.
            if (state.imageScanning) {
                Column(
                    modifier = Modifier.align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("画像を読み取り中", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // Catalog: ScanToolbar — torch / zoom / image, kept at the edge.
        // v0.3.0: flat surface (no tonal lift) separated by one hairline,
        // both side tools get the same width and a small label, and the
        // slider takes the theme colours instead of stock Material.
        // R2-7: with no live camera (no permission, image mode, failed
        // start) the torch and zoom are disabled rather than decorative.
        val cameraLive = hasPermission && !imageModeActive && !cameraFailed
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(52.dp),
                    ) {
                        IconButton(
                            onClick = {
                                state.torchOn = !state.torchOn
                                camera?.cameraControl?.enableTorch(state.torchOn)
                            },
                            enabled = cameraLive,
                        ) {
                            Icon(
                                imageVector = if (state.torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                                contentDescription = "懐中電灯",
                                tint = if (state.torchOn) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Text(
                            "ライト",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            "ズーム",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = state.zoom,
                            // v0.4.5: the slider spans the bound
                            // camera's real range and goes through
                            // applyZoom, so chips and pinch follow it
                            // (and it follows them) via state.zoom.
                            onValueChange = { applyZoom(it) },
                            valueRange = state.zoomMin..state.zoomMax,
                            enabled = cameraLive,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(52.dp),
                    ) {
                        IconButton(
                            onClick = { imagePicker.launch("image/*") },
                            enabled = !state.imageScanning,
                        ) {
                            Icon(
                                Icons.Filled.PhotoLibrary,
                                contentDescription = "画像から読む",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "画像",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.fromImageNote != null) {
                    Text(
                        state.fromImageNote!!,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }

        // Result list: number + kind + preview; tap opens the result sheet.
        // v0.4.3 (MINIMAL N-2): in image mode the empty-state line ("aim
        // at a code…") describes the wrong scene — the image note above
        // already carries the state there, so the line is camera-only.
        // v0.4.5 (MINIMAL round 2, N2-1): the list block renders only
        // when it has something in it. Image mode with zero hits used
        // to fall into the list branch anyway and show a "0件見つけま
        // した（タップで開く）" heading — nothing there to tap, and
        // the toolbar note already carries that state.
        if (detections.isNotEmpty()) {
          Column(modifier = if (wideList) Modifier.weight(3f) else Modifier) {
            // UX-6: the heading says how many and what a tap does.
            // R2-9: it is a live region, so TalkBack announces the count
            // as it changes instead of leaving the user in silence.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${detections.size}件見つけました（タップで開く）",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                // v0.4.3 (MINIMAL N-3): with auto-save-on-detect on,
                // every code here is already in history, so the button
                // could only ever report "all saved" — it is shown only
                // when it is a real save path (auto-save off).
                if (!autoSaveOnDetect) {
                    TextButton(onClick = { saveAllDetected() }) { Text("全部を履歴に保存") }
                }
            }
            // P2-2: height follows the count (up to a cap) instead of a
            // fixed two-row window, so a full sweep can be eyeballed.
            // v0.4.2: in wide-list mode the cap is gone — the list fills
            // its share of the screen and scrolls within it.
            LazyColumn(
                modifier = if (wideList) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().heightIn(max = 304.dp)
                },
            ) {
                itemsIndexed(detections, key = { _, d -> d.rawValue }) { index, d ->
                    val summaryText = CodeClassifier.summary(d.rawValue, d.kind)
                    // R2-3 + P2-2: the preview line is the display-safe
                    // form, and is dropped when it would just repeat the
                    // summary (the library rows' UX-11 rule).
                    val rawLine = CodeClassifier.maskedForDisplay(d.rawValue)
                        .replace("\n", " ").trim()
                    val showRawLine = rawLine.isNotEmpty() &&
                        !CodeClassifier.rawLineRedundant(d.rawValue, d.kind, summaryText)
                    val opened = state.openedRaw == d.rawValue
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(
                                if (opened) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    Color.Transparent
                                }
                            )
                            // R2-9: one row = one operable, announceable
                            // node for assistive technology.
                            .semantics(mergeDescendants = true) {}
                            .clickable { openResult(d) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(28.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TypeChip(d.kind)
                                if (d.count > 1) MiniChip("×${d.count}")
                            }
                            Text(
                                summaryText,
                                style = MaterialTheme.typography.bodyLarge,
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
                        }
                    }
                }
            }
          }
        } else if (!imageModeActive) {
            Text(
                "コードにかざすと、見つけたものがここに並びます",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        // Image mode with no detections renders nothing here: the note
        // in the toolbar already says what happened (N2-1).
    }

    if (state.selected != null) {
        ModalBottomSheet(onDismissRequest = { state.selected = null }) {
            ResultContent(
                rawValue = state.selected!!.rawValue,
                formatName = state.selected!!.formatName,
                alreadySaved = state.selectedAlreadySaved,
                // v0.4.4 (MINIMAL J-1): the sheet's raw block starts folded.
                collapseRawByDefault = true,
                onMakeQrFromText = { text ->
                    state.selected = null
                    onMakeQrFromText(text)
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // B-12: release everything this screen holds — camera,
            // analyzer executor, ML Kit client — when the tab is left.
            // (The results themselves live in ScanState and survive.)
            cameraGone.set(true)
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }
}

/**
 * Catalog: DetectionPin — a numbered marker over a detected code.
 * R2-9: the pin announces itself ("3番目 Wi-Fi …") instead of being a
 * meaningless number to TalkBack. P2-10: the opened code's pin carries
 * a white ring, and long labels ("10×3") step down a type size so they
 * stay inside the 30dp circle.
 */
@Composable
private fun DetectionPin(
    index: Int,
    d: DetectedCode,
    opened: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val label = if (d.count > 1) "${index + 1}×${d.count}" else "${index + 1}"
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .then(
                if (opened) {
                    Modifier.border(2.dp, Color.White, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(role = Role.Button) { onClick() }
            .semantics {
                contentDescription =
                    "${index + 1}番目 ${d.kind.label} ${CodeClassifier.summary(d.rawValue, d.kind)}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            // R-3 / UX-3: onPrimary pairs with the primary background in
            // both themes (white text was unreadable on the light-blue
            // dark primary).
            color = MaterialTheme.colorScheme.onPrimary,
            style = if (label.length >= 3) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.labelMedium
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

@Suppress("DEPRECATION")
private fun analyzeProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    proxy: ImageProxy,
    onResult: (List<DetectedCode>) -> Unit,
) {
    // B-31: the proxy must be closed on every path — if converting the
    // frame throws, an unclosed proxy stalls the analyzer for good.
    try {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        // Frame size in rotated (upright) coordinates for pin mapping.
        val frameW = if (rotation % 180 == 0) proxy.width else proxy.height
        val frameH = if (rotation % 180 == 0) proxy.height else proxy.width
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                onResult(mergeBarcodes(barcodes, frameW, frameH))
            }
            .addOnCompleteListener { proxy.close() }
    } catch (e: Exception) {
        runCatching { proxy.close() }
    }
}

private fun vibrateOnce(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    } catch (e: Exception) {
        // Vibration is a hint only; never let it break scanning.
    }
}

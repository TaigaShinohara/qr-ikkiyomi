package app.qrikkiyomi.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.qrikkiyomi.core.QrFactory
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Writes [bitmap] as a PNG into Pictures/QRいっき読み. Returns success;
 * shows no UI, so it is safe to call off the main thread.
 */
private fun writePngToPictures(context: Context, bitmap: Bitmap, name: String): Boolean =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRいっき読み")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            // B-13: a stream that cannot be opened, or a failed compress,
            // is a failed save — never report success for it.
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("no output stream")
            val ok = out.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!ok) throw IllegalStateException("compress failed")
        } else {
            // B-14: WRITE_EXTERNAL_STORAGE is a runtime permission and this
            // app never asks for it, so the public directory is only usable
            // when the permission is already granted. Otherwise fall back
            // to the app-specific Pictures folder, which needs none.
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            val base = if (granted) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: File(context.filesDir, "Pictures")
            }
            val dir = File(base, "QRいっき読み").apply { mkdirs() }
            val file = File(dir, "$name.png")
            val ok = FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (!ok) throw IllegalStateException("compress failed")
            // Without a media scan the file stays invisible to galleries.
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("image/png"), null,
            )
        }
        true
    }.getOrDefault(false)

/** Saves a QR bitmap into Pictures/QRいっき読み and tells the user. */
fun savePngToPictures(context: Context, bitmap: Bitmap, name: String = "qr_${System.currentTimeMillis()}") {
    val ok = writePngToPictures(context, bitmap, name)
    Toast.makeText(
        context,
        if (ok) "写真に保存しました" else "保存に失敗しました",
        Toast.LENGTH_SHORT,
    ).show()
}

/** Writes [bitmap] to the share cache and returns the file, or null. */
private fun writeShareFile(context: Context, bitmap: Bitmap): File? = runCatching {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    // B-27: a fixed file name can collide with a previous share still
    // being read by the receiving app; make each share its own file.
    val file = File(dir, "qr_share_${System.currentTimeMillis()}.png")
    val ok = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    if (!ok) throw IllegalStateException("compress failed")
    file
}.getOrNull()

/** Shares a QR bitmap as a PNG through the system share sheet. */
fun sharePng(context: Context, bitmap: Bitmap) {
    val file = writeShareFile(context, bitmap)
    if (file == null) {
        Toast.makeText(context, "共有に失敗しました", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "QRコードを共有"))
    } catch (e: Exception) {
        Toast.makeText(context, "共有に失敗しました", Toast.LENGTH_SHORT).show()
    }
}

// B-19: generating a large bitmap (up to 2048px) and writing it used to run
// on the tap handler's thread, and a payload too big for a QR was silently
// swallowed (bitmap == null, nothing happened). These entry points generate
// and write off the main thread and always tell the user the outcome.
// (Toasts and the share sheet stay on the main thread; file work does not.)

fun saveQrPngAsync(
    context: Context,
    payload: String,
    sizePx: Int,
    ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    margin: Int = 2,
) {
    val appContext = context.applicationContext
    CoroutineScope(Dispatchers.Default).launch {
        val bitmap = QrFactory.makeBitmap(payload, sizePx, ec, margin)
        if (bitmap == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext, "内容が長すぎてQRを生成できませんでした",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return@launch
        }
        val ok = withContext(Dispatchers.IO) {
            writePngToPictures(appContext, bitmap, "qr_${System.currentTimeMillis()}")
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(
                appContext,
                if (ok) "写真に保存しました" else "保存に失敗しました",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

fun shareQrPngAsync(
    context: Context,
    payload: String,
    sizePx: Int,
    ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    margin: Int = 2,
) {
    val appContext = context.applicationContext
    CoroutineScope(Dispatchers.Default).launch {
        val bitmap = QrFactory.makeBitmap(payload, sizePx, ec, margin)
        if (bitmap == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext, "内容が長すぎてQRを生成できませんでした",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return@launch
        }
        val file = withContext(Dispatchers.IO) { writeShareFile(appContext, bitmap) }
        withContext(Dispatchers.Main) {
            if (file == null) {
                Toast.makeText(appContext, "共有に失敗しました", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val uri = FileProvider.getUriForFile(
                        appContext, appContext.packageName + ".fileprovider", file,
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    appContext.startActivity(
                        Intent.createChooser(intent, "QRコードを共有").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (e: Exception) {
                    Toast.makeText(appContext, "共有に失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

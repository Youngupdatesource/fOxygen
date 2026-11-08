package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import frb.axeron.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import androidx.compose.foundation.layout.aspectRatio
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/** Rasio banner kartu status Home (lebar : tinggi = 2 : 1). */
const val STATUS_BANNER_ASPECT = 2f

/**
 * Penyimpanan banner kartu status Home. File disalin sekali ke filesDir (tidak bergantung pada Uri picker),
 * ukurannya dibatasi, dan divalidasi bisa didekode sebelum dipakai.
 */
object BannerStore {
    enum class Result { OK, TOO_LARGE, FAILED }

    private const val FILE_NAME = "home_banner"
    private const val MAX_BYTES = 8L * 1024 * 1024

    /** Naik setiap banner diganti/dihapus supaya UI dan cache Coil ikut diperbarui. */
    var version by mutableLongStateOf(0L)
        private set

    private const val PREFS = "home_banner"

    /** Titik fokus crop, 0..1 (0.5 = tengah). Dipakai untuk memilih bagian gambar yang tampil. */
    var focusX by mutableFloatStateOf(0.5f)
        private set
    var focusY by mutableFloatStateOf(0.5f)
        private set
    private var focusLoaded = false

    fun loadFocus(context: Context) {
        if (focusLoaded) return
        focusLoaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        focusX = prefs.getFloat("focus_x", 0.5f).coerceIn(0f, 1f)
        focusY = prefs.getFloat("focus_y", 0.5f).coerceIn(0f, 1f)
    }

    fun setFocus(context: Context, x: Float, y: Float) {
        focusLoaded = true
        focusX = x.coerceIn(0f, 1f)
        focusY = y.coerceIn(0f, 1f)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("focus_x", focusX)
            .putFloat("focus_y", focusY)
            .apply()
    }

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    suspend fun import(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.FAILED
            var total = 0L
            var tooLarge = false
            input.use { src ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = src.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            tooLarge = true
                            break
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (tooLarge) {
                tmp.delete()
                return@withContext Result.TOO_LARGE
            }

            // Validasi murah: hanya baca dimensi, tanpa memuat bitmap.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                tmp.delete()
                return@withContext Result.FAILED
            }

            val target = file(context)
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            setFocus(context, 0.5f, 0.5f)
            version = System.currentTimeMillis()
            Result.OK
        } catch (e: Exception) {
            tmp.delete()
            Result.FAILED
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        setFocus(context, 0.5f, 0.5f)
        version = System.currentTimeMillis()
    }
}

/** File banner kustom bila ada; dihitung ulang setiap banner diganti atau dihapus. */
@Composable
fun rememberStatusBannerFile(): File? {
    val context = LocalContext.current
    val version = BannerStore.version
    BannerStore.loadFocus(context)
    return remember(version) { BannerStore.file(context).takeIf { it.exists() } }
}

/**
 * Menggambar banner (gambar/GIF) mengisi parent.
 *
 * - Digambar lewat Modifier.paint(sizeToIntrinsics = false) supaya ukuran gambar tidak ikut
 *   menentukan tinggi kartu (StatusCard memakai IntrinsicSize.Min).
 * - Ukuran request = ukuran area gambar, jadi gambar/GIF didekode di ukuran tampil, bukan ukuran asli.
 * - GIF hanya dianimasikan saat Activity STARTED; selain itu painter dilepas dari komposisi
 *   sehingga animasi berhenti dan tidak menghabiskan baterai di background.
 */
private fun Context.findComponentActivity(): ComponentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}

@Composable
fun StatusBannerImage(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = BannerStore.version
    val ownerLifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycle = remember(context, ownerLifecycle) {
        context.findComponentActivity()?.lifecycle ?: ownerLifecycle
    }
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // Cache frame terakhir + ukuran area: banner langsung tergambar di frame pertama saat tab
    // dibuka lagi (geser Home <-> Privileges), tidak lagi kosong lalu muncul (kedip).
    // Kunci mengikuti file banner (bukan counter di memori): frame juga disimpan ke disk dan
    // dibaca lagi saat app dibuka dari nol, jadi kunci harus tetap sama antar proses dan
    // berubah begitu banner diganti.
    val cacheKey = remember(file, version) { "status_banner:${file.lastModified()}:${file.length()}" }
    CachedBannerImage(
        cacheKey = cacheKey,
        model = file,
        modifier = modifier,
        alignment = BiasAlignment(BannerStore.focusX * 2f - 1f, BannerStore.focusY * 2f - 1f),
        animate = visible && rememberBannerAnimate()
    )
}

/** Dialog untuk menggeser gambar: bagian yang tampil di banner mengikuti jari. */
@Composable
fun BannerPositionDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var fx by remember { mutableFloatStateOf(BannerStore.focusX) }
    var fy by remember { mutableFloatStateOf(BannerStore.focusY) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_banner_adjust)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.home_banner_adjust_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(STATUS_BANNER_ASPECT)
                        .clip(RoundedCornerShape(12.dp))
                        .onSizeChanged { boxSize = it }
                        .pointerInput(imageSize, boxSize) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                if (imageSize.width <= 0 || imageSize.height <= 0 ||
                                    boxSize.width <= 0 || boxSize.height <= 0
                                ) return@detectDragGestures
                                val scale = max(
                                    boxSize.width.toFloat() / imageSize.width,
                                    boxSize.height.toFloat() / imageSize.height
                                )
                                val overflowX = imageSize.width * scale - boxSize.width
                                val overflowY = imageSize.height * scale - boxSize.height
                                if (overflowX > 1f) fx = (fx - drag.x / overflowX).coerceIn(0f, 1f)
                                if (overflowY > 1f) fy = (fy - drag.y / overflowY).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(file)
                            .memoryCacheKey("status_banner_adjust:${BannerStore.version}")
                            .build(),
                        contentDescription = null,
                        alignment = BiasAlignment(fx * 2f - 1f, fy * 2f - 1f),
                        contentScale = ContentScale.Crop,
                        onSuccess = { state ->
                            val d = state.result.drawable
                            imageSize = IntSize(d.intrinsicWidth, d.intrinsicHeight)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                BannerStore.setFocus(context, fx, fy)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { fx = 0.5f; fy = 0.5f }) {
                    Text(stringResource(R.string.reset))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

/** Item pengaturan banner untuk layar Appearance. */
@Composable
fun BannerSettingItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = rememberStatusBannerFile()
    var showAdjust by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = BannerStore.import(context.applicationContext, uri)
            val message = when (result) {
                BannerStore.Result.OK -> null
                BannerStore.Result.TOO_LARGE -> R.string.home_banner_too_large
                BannerStore.Result.FAILED -> R.string.home_banner_failed
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            if (result == BannerStore.Result.OK) showAdjust = true
        }
    }

    SettingsItem(
        iconVector = Icons.Filled.Image,
        label = stringResource(R.string.status_banner),
        description = stringResource(R.string.status_banner_desc),
        onClick = { picker.launch("image/*") }
    ) { _, _ ->
        if (file != null) {
            StatusBannerImage(
                file = file,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .aspectRatio(STATUS_BANNER_ASPECT)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }

    if (file != null) {
        SettingsItem(
            iconVector = Icons.Filled.OpenWith,
            label = stringResource(R.string.home_banner_adjust),
            description = stringResource(R.string.home_banner_adjust_desc),
            onClick = { showAdjust = true }
        )
        SettingsItem(
            iconVector = Icons.Filled.Delete,
            label = stringResource(R.string.home_banner_remove),
            onClick = { BannerStore.clear(context) }
        )
    }

    if (showAdjust && file != null) {
        BannerPositionDialog(file = file, onDismiss = { showAdjust = false })
    }
}

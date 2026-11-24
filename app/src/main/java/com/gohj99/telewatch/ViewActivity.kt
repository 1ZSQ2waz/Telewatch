/*
 * Copyright (c) 2024-2025 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.gohj99.telewatch.model.tgFile
import com.gohj99.telewatch.ui.main.ErrorScreen
import com.gohj99.telewatch.ui.main.SplashLoadingScreen
import com.gohj99.telewatch.ui.media.PhotoViewport
import com.gohj99.telewatch.ui.theme.TelewatchTheme
import com.gohj99.telewatch.utils.telegram.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream

class ViewActivity : ComponentActivity() {
    private var tgApi: TgApi? = null
    private var photoSource by mutableStateOf<Any?>(null)
    private var photoPath: String? = null
    private var originalPhoto: TdApi.File? = null
    private var showingPhoto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TelewatchTheme {
                SplashLoadingScreen(modifier = Modifier.fillMaxSize())
            }
        }

        initSelf()
    }

    private fun initSelf() {
        lifecycleScope.launch {
            try {
                tgApi = TgApiManager.tgApi
                val messageId = intent.getLongExtra("messageId", -1L)
                if (messageId == -1L) {
                    val file: tgFile? = intent.getParcelableExtra("file")
                    if (file != null) showFile(file.file, ::showImage) else showError()
                    return@launch
                }

                when (val content = tgApi?.getMessageTypeById(messageId)?.content) {
                    is TdApi.MessagePhoto -> showPhoto(content.photo)
                    is TdApi.MessageSticker -> showFile(content.sticker.sticker, ::showTgs)
                    is TdApi.MessageAnimation -> when (content.animation.mimeType) {
                        "image/gif" -> showFile(content.animation.animation, ::showImage)
                        "video/mp4" -> showFile(content.animation.animation, ::showMp4)
                        else -> showError()
                    }
                    else -> showError()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setContent {
                    TelewatchTheme {
                        ErrorScreen(onRetry = ::initSelf)
                    }
                }
            }
        }
    }

    private suspend fun showPhoto(photo: TdApi.Photo) {
        val downloads = tgApi?.thumbnailDownloads ?: return showError()
        val sizes = photo.sizes.sortedBy { it.width.toLong() * it.height }
        val largest = sizes.lastOrNull() ?: return showError()
        originalPhoto = largest.photo
        val cached = sizes.lastOrNull { downloads.cachedPath(it.photo) != null }
        if (cached != null) showPath(downloads.cachedPath(cached.photo), ::showImage)
        val minithumbnail = photo.minithumbnail?.data
        if (!showingPhoto && minithumbnail?.isNotEmpty() == true) {
            photoSource = minithumbnail
            showPhotoContent()
        }

        val displayEdge = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val preview = sizes.firstOrNull { maxOf(it.width, it.height) >= displayEdge } ?: largest
        if (cached == null || cached.width.toLong() * cached.height < preview.width.toLong() * preview.height) {
            showPath(downloads.path(preview.photo, priority = 24), ::showImage)
        }
        showPath(downloads.path(largest.photo, priority = 32, timeoutMillis = 90_000), ::showImage)
        if (!showingPhoto) showError()
    }

    private suspend fun showFile(file: TdApi.File, render: (String) -> Unit) {
        val downloads = tgApi?.thumbnailDownloads ?: return showError()
        if (!showPath(downloads.path(file, priority = 32, timeoutMillis = 90_000), render)) showError()
    }

    private suspend fun showPath(path: String?, render: (String) -> Unit): Boolean {
        val validFile = path?.takeIf(String::isNotBlank)?.let {
            withContext(Dispatchers.IO) { File(it).let { file -> file.isFile && file.length() > 0 } }
        } == true
        if (isFinishing || isDestroyed || !validFile || path == null) return false
        render(path)
        return true
    }

    private fun showImage(photoPath: String) {
        this.photoPath = photoPath
        photoSource = File(photoPath)
        showPhotoContent()
    }

    private fun showPhotoContent() {
        if (showingPhoto) return
        showingPhoto = true
        setContent {
            TelewatchTheme {
                SplashImgView(photoSource!!, onClose = ::finish, saveImage = {
                    lifecycleScope.launch {
                        val original = originalPhoto
                        val savePath = if (original != null) {
                            tgApi?.thumbnailDownloads?.path(original, priority = 32, timeoutMillis = 90_000)
                        } else photoPath
                        val result = withContext(Dispatchers.IO) {
                            if (savePath != null) saveImageToExternalStorage(this@ViewActivity, savePath)
                            else getString(R.string.Read_failed)
                        }
                        Toast.makeText(this@ViewActivity, result, Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }
    }

    private fun showTgs(photoPath: String) {
        setContent {
            TelewatchTheme {
                SplashTgsView(photoPath)
            }
        }
    }

    private fun showMp4(photoPath: String) {
        setContent {
            TelewatchTheme {
                SplashMp4View(photoPath)
            }
        }
    }

    private fun showError() {
        println("Error: ${R.string.Error_ImgView}")
        setContent {
            TelewatchTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.Error_ImgView),
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

fun saveImageToExternalStorage(context: Context, photoPath: String): String {
    // 获取图片文件名
    val imageName = File(photoPath).nameWithoutExtension

    // 从内部存储读取图片
    val bitmap = BitmapFactory.decodeFile(photoPath) ?: return context.getString(R.string.Read_failed)

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10及以上使用MediaStore
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf("$imageName.jpg")
        val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor = context.contentResolver.query(queryUri, null, selection, selectionArgs, null)

        if (cursor != null && cursor.count > 0) {
            cursor.close()
            return context.getString(R.string.Same_name_image_exists)
        }
        cursor?.close()

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$imageName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Telewatch")
            put(MediaStore.Images.Media.IS_PENDING, 1) // 设置IS_PENDING状态
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return context.getString(R.string.failling)

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.flush()
        }

        // 更新IS_PENDING状态
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, contentValues, null, null)

        return context.getString(R.string.Save_success)
    } else {
        // Android 8和9使用传统方式保存到外部存储
        val imagesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Telewatch")
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }

        val file = File(imagesDir, "$imageName.jpg")

        if (file.exists()) {
            return context.getString(R.string.Same_name_image_exists)
        }

        FileOutputStream(file).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        }

        // 通知系统图库更新
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))

        return context.getString(R.string.Save_success)
    }
}

@Composable
private fun SplashImgView(photoSource: Any, onClose: () -> Unit, saveImage: () -> Unit) {
    // Some watches deliver their global swipe as Back even if the view requested the touch.
    BackHandler { }
    val context = LocalContext.current
    val viewport = remember(context) { PhotoViewport(context) }
    var loadFailed by remember(viewport) { mutableStateOf(false) }
    var transforming by remember(viewport) { mutableStateOf(false) }
    var showClose by remember(viewport) { mutableStateOf(true) }
    LaunchedEffect(transforming) {
        if (transforming) {
            showClose = false
        } else if (!showClose) {
            delay(300)
            showClose = true
        }
    }
    DisposableEffect(viewport, photoSource) {
        val display = context.resources.displayMetrics
        val decodeSize = (maxOf(display.widthPixels, display.heightPixels) * 4).coerceIn(1, 2048)
        val request = context.imageLoader.enqueue(
            ImageRequest.Builder(context).data(photoSource)
                .size(decodeSize).scale(Scale.FIT).precision(Precision.INEXACT)
                .allowHardware(false).crossfade(false)
                .target(
                    onSuccess = { drawable -> viewport.replaceImage(drawable); loadFailed = false },
                    onError = { loadFailed = viewport.drawable == null }
                ).build()
        )
        onDispose { request.dispose() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { viewport },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.onSave = saveImage
                it.onTransformGesture = { active -> transforming = active }
            }
        )
        if (loadFailed) {
            Text(
                text = stringResource(R.string.Error_ImgView),
                color = Color.White,
                modifier = Modifier.padding(24.dp)
            )
        }
        if (!transforming && showClose) {
            val closeLabel = stringResource(R.string.close_photo_viewer)
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                    .background(Color(0xB0000000), CircleShape)
                    .semantics { contentDescription = closeLabel }
            ) {
                Canvas(Modifier.size(16.dp)) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2
                    drawLine(Color.White, Offset(inset, inset), Offset(size.width - inset, size.height - inset), stroke, StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width - inset, inset), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun SplashTgsView(photoPath: String) {
    Box(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Load the Lottie composition from the file path
        val composition by rememberLottieComposition(LottieCompositionSpec.File(photoPath))

        // Create and control the animation
        val progress by animateLottieCompositionAsState(
            composition,
            iterations = LottieConstants.IterateForever // Set to repeat forever
        )

        // Render the animation
        LottieAnimation(
            composition = composition,
            progress = progress,
        )
    }
}

@Composable
private fun SplashMp4View(photoPath: String) {
    Box(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        LoopingVideoPlayer(Uri.parse(photoPath))
    }
}

@Composable
fun LoopingVideoPlayer(videoUri: Uri) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(
        AndroidView(factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = false // Disable the playback controls
            }
        })
    ) {
        onDispose {
            exoPlayer.release()
        }
    }
}

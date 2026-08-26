package com.xmax.xlab.modules.xlstorage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ai.xmax.sdk.XmaxClient
import ai.xmax.sdk.XmaxConfiguration
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxSdk
import ai.xmax.sdk.XmaxStorageProgressListener
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val StorageOrange = Color(0xFFF5B86C)
private val StoragePrimaryText = Color(0xFFF4EEE6)
private val StorageSecondaryText = Color(0xFF8E8377)

private enum class StorageMediaKind {
    IMAGE,
    VIDEO,
}

private data class SelectedMedia(
    val sourceUri: Uri,
    val localFile: File,
    val kind: StorageMediaKind,
    val mimeType: String,
    val resolution: String,
    val size: Long,
)

@Composable
public fun StorageScreen(
    apiKey: String,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var selectedMedia by remember { mutableStateOf<SelectedMedia?>(null) }
    var isPicking by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var activeUploadUsesSafetyCheck by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var uploadElapsedMs by remember { mutableLongStateOf(0L) }
    var uploadedUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            isPicking = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                selectedMedia = loadSelectedMedia(context, uri)
                uploadProgress = 0f
                uploadElapsedMs = 0L
                uploadedUrl = ""
                errorMessage = ""
            } catch (error: Throwable) {
                errorMessage = error.message ?: "读取文件失败，请重试"
            } finally {
                isPicking = false
            }
        }
    }

    fun selectMedia() {
        if (isPicking || isUploading) return
        isPicking = true
        picker.launch(arrayOf("image/*", "video/*"))
    }

    fun uploadSelectedFile(withSafetyCheck: Boolean) {
        val media = selectedMedia ?: return
        if (isUploading) return
        isUploading = true
        activeUploadUsesSafetyCheck = withSafetyCheck
        uploadProgress = 0f
        uploadElapsedMs = 0L
        uploadedUrl = ""
        errorMessage = ""
        val startedAt = System.currentTimeMillis()

        scope.launch {
            try {
                val manager = XmaxClient(context, XmaxConfiguration(apiKey)).createStorageManager()
                val progress = XmaxStorageProgressListener { value ->
                    scope.launch {
                        uploadProgress = maxOf(uploadProgress, value.fractionCompleted)
                    }
                }
                val uploaded = when (media.kind) {
                    StorageMediaKind.IMAGE -> if (withSafetyCheck) {
                        manager.uploadImageFileWithSafetyCheck(media.localFile, media.mimeType, progress)
                    } else {
                        manager.uploadImageFile(media.localFile, media.mimeType, progress)
                    }
                    StorageMediaKind.VIDEO -> manager.uploadVideoFile(media.localFile, media.mimeType, progress)
                }
                uploadProgress = 1f
                uploadElapsedMs = System.currentTimeMillis() - startedAt
                uploadedUrl = uploaded.url
                delay(100)
                scrollState.animateScrollTo(scrollState.maxValue)
            } catch (error: Throwable) {
                errorMessage = when (error) {
                    is XmaxError -> "${error.code}: ${error.message}"
                    else -> error.message ?: "上传失败，请检查 API Key 和网络后重试"
                }
            } finally {
                isUploading = false
            }
        }
    }

    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090A0C)),
        ) {
            AmbientGlow(
                color = StorageOrange,
                size = 280.dp,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 150.dp, y = (-70).dp),
            )
            AmbientGlow(
                color = Color(0xFFC67A35),
                size = 180.dp,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = (-130).dp, y = (-30).dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                StorageTopBar(onBack = onBack)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .navigationBarsPadding()
                        .padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 28.dp),
                ) {
                    StorageIntroCard()
                    StoragePreviewCard(
                        media = selectedMedia,
                        isPicking = isPicking,
                        isUploading = isUploading,
                        activeUploadUsesSafetyCheck = activeUploadUsesSafetyCheck,
                        uploadProgress = uploadProgress,
                        onSelectMedia = ::selectMedia,
                        onUpload = ::uploadSelectedFile,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    if (errorMessage.isNotEmpty()) {
                        ErrorCard(errorMessage, Modifier.padding(top = 12.dp))
                    }
                    if (uploadedUrl.isNotEmpty()) {
                        StorageResultCard(
                            url = uploadedUrl,
                            elapsedMs = uploadElapsedMs,
                            onCopy = { copyUrl(context, uploadedUrl) },
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmbientGlow(color: Color, size: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .blur(82.dp)
            .background(color.copy(alpha = 0.18f), CircleShape),
    )
}

@Composable
private fun StorageTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(start = 8.dp, top = 12.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(32.dp)) {
                drawCircle(Color(0xB5181B20), style = androidx.compose.ui.graphics.drawscope.Fill)
                drawCircle(
                    Color.White.copy(alpha = 0.16f),
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawLine(
                    color = Color(0xFFE9EDF3),
                    start = Offset(size.width * 0.62f, size.height * 0.27f),
                    end = Offset(size.width * 0.38f, size.height * 0.5f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color(0xFFE9EDF3),
                    start = Offset(size.width * 0.38f, size.height * 0.5f),
                    end = Offset(size.width * 0.62f, size.height * 0.73f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = "存储服务",
                color = Color(0xFFF4F7FB),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "EXAMPLE / ANDROID",
                modifier = Modifier.padding(top = 3.dp),
                color = StorageOrange.copy(alpha = 0.72f),
                fontSize = 8.sp,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "v${XmaxSdk.VERSION.substringBefore('-')}",
            modifier = Modifier
                .height(25.dp)
                .background(StorageOrange.copy(alpha = 0.14f), RoundedCornerShape(13.dp))
                .border(1.dp, StorageOrange.copy(alpha = 0.29f), RoundedCornerShape(13.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
            color = StorageOrange,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StorageIntroCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(20.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black)
            .background(
                Brush.linearGradient(listOf(Color(0xF01D1711), Color(0xE80F1115))),
                RoundedCornerShape(18.dp),
            )
            .border(1.dp, StorageOrange.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
            .padding(17.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(StorageOrange, CircleShape))
            Text(
                text = "STORAGE PIPELINE",
                modifier = Modifier.padding(start = 7.dp),
                color = StorageOrange,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "READY",
                modifier = Modifier
                    .height(23.dp)
                    .background(StorageOrange.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                color = StorageOrange,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = "把本地媒体交给 XmaxSDK",
            modifier = Modifier.padding(top = 13.dp),
            color = StoragePrimaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "选择图片或视频，上传后获取可直接使用的远程地址。",
            modifier = Modifier.padding(top = 7.dp),
            color = StorageSecondaryText,
            fontSize = 10.sp,
            lineHeight = 17.sp,
        )
        Row(modifier = Modifier.padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            PipelineLabel("LOCAL FILE", Color(0xFF9A8B7A))
            PipelineDivider()
            PipelineLabel("XMAX SDK", StorageOrange)
            PipelineDivider()
            PipelineLabel("REMOTE URL", Color(0xFF9A8B7A))
        }
    }
}

@Composable
private fun PipelineLabel(label: String, color: Color) {
    Text(label, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun PipelineDivider() {
    Text("—", modifier = Modifier.padding(horizontal = 8.dp), color = Color(0xFF66513A), fontSize = 9.sp)
}

@Composable
private fun StoragePreviewCard(
    media: SelectedMedia?,
    isPicking: Boolean,
    isUploading: Boolean,
    activeUploadUsesSafetyCheck: Boolean,
    uploadProgress: Float,
    onSelectMedia: () -> Unit,
    onUpload: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black)
            .background(
                Brush.linearGradient(listOf(Color(0xED1B1712), Color(0xE8111216))),
                RoundedCornerShape(18.dp),
            )
            .border(1.dp, StorageOrange.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .padding(17.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepBadge("01")
            Text(
                text = "文件预览",
                modifier = Modifier.padding(start = 9.dp),
                color = Color(0xFFF2ECE4),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (media == null) {
                Text("点击选择", color = Color(0xFF6E6257), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            } else {
                SmallOutlineButton(
                    label = "重新上传",
                    enabled = !isPicking && !isUploading,
                    onClick = onSelectMedia,
                )
            }
        }
        if (media?.kind == StorageMediaKind.VIDEO) {
            Text(
                text = "视频生成暂不支持安全检测",
                modifier = Modifier.padding(top = 10.dp),
                color = Color(0xFF596678),
                fontSize = 9.sp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(176.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE60B0C0F))
                .border(1.dp, StorageOrange.copy(alpha = 0.24f), RoundedCornerShape(14.dp))
                .clickable(enabled = media == null && !isPicking && !isUploading, onClick = onSelectMedia),
            contentAlignment = Alignment.Center,
        ) {
            if (media == null) {
                EmptyMediaPreview(isPicking)
            } else {
                MediaPreview(media)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MediaInfo(
                label = "type",
                value = media?.kind?.let { if (it == StorageMediaKind.IMAGE) "图片" else "视频" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MediaInfo(
                label = "resolution",
                value = media?.resolution?.ifBlank { "--" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MediaInfo(
                label = "size",
                value = media?.size?.let(::formatFileSize) ?: "--",
                modifier = Modifier.weight(1f),
            )
        }
        if (isUploading) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 7.dp)) {
                Text(
                    text = "上传中 ${(uploadProgress * 100).toInt()}%",
                    color = StorageOrange,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = when (media?.kind) {
                        StorageMediaKind.IMAGE -> if (activeUploadUsesSafetyCheck) "包含内容安全检查" else "正在上传图片"
                        StorageMediaKind.VIDEO -> "正在上传视频"
                        null -> ""
                    },
                    color = Color(0xFF657386),
                    fontSize = 9.sp,
                )
            }
            LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = StorageOrange,
                trackColor = StorageOrange.copy(alpha = 0.14f),
            )
        }
        if (media != null) {
            if (media.kind == StorageMediaKind.IMAGE) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    UploadButton(
                        label = if (isUploading && activeUploadUsesSafetyCheck) "正在检测上传" else "安全检测上传",
                        enabled = !isUploading,
                        onClick = { onUpload(true) },
                        modifier = Modifier.weight(1f),
                    )
                    UploadButton(
                        label = if (isUploading && !activeUploadUsesSafetyCheck) "正在上传" else "普通上传",
                        enabled = !isUploading,
                        onClick = { onUpload(false) },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                UploadButton(
                    label = if (isUploading) "正在上传" else "上传并获取地址",
                    enabled = !isUploading,
                    onClick = { onUpload(false) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun StepBadge(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .width(28.dp)
            .height(22.dp)
            .background(StorageOrange.copy(alpha = 0.12f), RoundedCornerShape(11.dp))
            .border(1.dp, StorageOrange.copy(alpha = 0.27f), RoundedCornerShape(11.dp))
            .padding(top = 7.dp),
        color = StorageOrange,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SmallOutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(66.dp)
            .height(28.dp)
            .background(StorageOrange.copy(alpha = 0.07f), RoundedCornerShape(9.dp))
            .border(1.dp, StorageOrange.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = StorageOrange.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyMediaPreview(isPicking: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "+",
            modifier = Modifier
                .size(42.dp)
                .background(StorageOrange.copy(alpha = 0.09f), CircleShape)
                .border(1.dp, StorageOrange.copy(alpha = 0.24f), CircleShape)
                .padding(top = 7.dp),
            color = StorageOrange,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (isPicking) "正在打开媒体库" else "点击选择图片或视频",
            modifier = Modifier.padding(top = 11.dp),
            color = Color(0xFF9D9185),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "IMAGE  /  VIDEO",
            modifier = Modifier.padding(top = 5.dp),
            color = Color(0xFF62584E),
            fontSize = 8.sp,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun MediaPreview(media: SelectedMedia) {
    if (media.kind == StorageMediaKind.IMAGE) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { it.setImageURI(media.sourceUri) },
        )
    } else {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setOnPreparedListener { player ->
                        player.isLooping = true
                        start()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (view.tag != media.sourceUri) {
                    view.tag = media.sourceUri
                    view.setVideoURI(media.sourceUri)
                    view.start()
                }
            },
            onRelease = { it.stopPlayback() },
        )
    }
}

@Composable
private fun MediaInfo(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(51.dp)
            .background(Color(0x8A0C0D10), RoundedCornerShape(10.dp))
            .padding(start = 11.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = Color(0xFF6E6257), fontSize = 8.sp)
        Text(
            text = value,
            modifier = Modifier.padding(top = 4.dp),
            color = if (value == "--") Color(0xFF5C534A) else Color(0xFFB9AA9B),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UploadButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(38.dp)
            .background(StorageOrange.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, StorageOrange.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = StorageOrange.copy(alpha = if (enabled) 1f else 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x28FF5F68), RoundedCornerShape(11.dp))
            .border(1.dp, Color(0x38FF6B72), RoundedCornerShape(11.dp))
            .padding(12.dp),
        color = Color(0xFFFFB5B5),
        fontSize = 10.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun StorageResultCard(
    url: String,
    elapsedMs: Long,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black)
            .background(
                Brush.linearGradient(listOf(Color(0xED1B1712), Color(0xE8111216))),
                RoundedCornerShape(18.dp),
            )
            .border(1.dp, StorageOrange.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(17.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepBadge("02")
            Text(
                text = "上传结果",
                modifier = Modifier.padding(start = 9.dp),
                color = Color(0xFFF2ECE4),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "SUCCESS",
                modifier = Modifier
                    .width(66.dp)
                    .height(28.dp)
                    .background(StorageOrange.copy(alpha = 0.07f), RoundedCornerShape(9.dp))
                    .border(1.dp, StorageOrange.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
                    .padding(top = 9.dp),
                color = StorageOrange,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 15.dp)) {
            Text("上传耗时", color = Color(0xFF718095), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(elapsedMs), color = StorageOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "REMOTE URL",
            modifier = Modifier.padding(top = 14.dp),
            color = Color(0xFF667589),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Text(
            text = url,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp)
                .background(Color(0x950B0C0F), RoundedCornerShape(11.dp))
                .border(1.dp, StorageOrange.copy(alpha = 0.13f), RoundedCornerShape(11.dp))
                .padding(11.dp),
            color = Color(0xFFCDBEAF),
            fontSize = 9.sp,
            lineHeight = 15.sp,
        )
        UploadButton(
            label = "复制地址",
            enabled = true,
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

private suspend fun loadSelectedMedia(context: Context, uri: Uri): SelectedMedia = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri).orEmpty()
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }.orEmpty()
    val kind = when {
        mimeType.startsWith("video/") -> StorageMediaKind.VIDEO
        mimeType.startsWith("image/") -> StorageMediaKind.IMAGE
        displayName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS -> StorageMediaKind.VIDEO
        else -> StorageMediaKind.IMAGE
    }
    val resolvedMimeType = mimeType.ifBlank {
        if (kind == StorageMediaKind.IMAGE) "image/jpeg" else "video/mp4"
    }
    val extension = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        ?: extensionForMimeType(resolvedMimeType, kind)
    val directory = File(context.cacheDir, "storage_playground").apply { mkdirs() }
    val destination = File(directory, "${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension")
    resolver.openInputStream(uri)?.use { input ->
        destination.outputStream().use { output -> input.copyTo(output) }
    } ?: error("无法读取所选文件")
    val resolution = if (kind == StorageMediaKind.IMAGE) {
        readImageResolution(destination)
    } else {
        readVideoResolution(destination)
    }
    SelectedMedia(uri, destination, kind, resolvedMimeType, resolution, destination.length())
}

private fun readImageResolution(file: File): String {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return formatResolution(options.outWidth, options.outHeight)
}

private fun readVideoResolution(file: File): String {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) formatResolution(height, width) else formatResolution(width, height)
    } finally {
        retriever.release()
    }
}

private fun formatResolution(width: Int, height: Int): String =
    if (width > 0 && height > 0) "$width × $height" else ""

private fun formatFileSize(size: Long): String = when {
    size < 1024L -> "$size B"
    size < 1024L * 1024L -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}

private fun formatDuration(milliseconds: Long): String =
    if (milliseconds < 1000L) "$milliseconds ms" else "%.2f s".format(milliseconds / 1000.0)

private fun extensionForMimeType(mimeType: String, kind: StorageMediaKind): String = when (mimeType) {
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    "image/gif" -> "gif"
    "video/quicktime" -> "mov"
    "video/webm" -> "webm"
    else -> if (kind == StorageMediaKind.IMAGE) "jpg" else "mp4"
}

private fun copyUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Xmax remote URL", url))
}

private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm", "avi", "mkv", "3gp", "3g2", "ts")

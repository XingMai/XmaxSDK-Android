package com.xmax.xlab.modules.xlfeed

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxSdk

public enum class XLabFeedAction {
    API_KEYS,
    CAMERA,
    VIDEO,
    IMAGE,
    TRAJECTORY,
    STORAGE,
}

private val Mint = Color(0xFF8EF0C8)
private val VideoBlue = Color(0xFF78A9FF)
private val ImagePurple = Color(0xFFC9A3FF)
private val TrajectoryPink = Color(0xFFFF8FD8)
private val StorageOrange = Color(0xFFF5B86C)
private val PrimaryText = Color(0xFFF4F7FB)
private val SecondaryText = Color(0xFF8E9AA9)

private data class PipelineUiModel(
    val sequence: String,
    val modeId: String,
    val color: Color,
    val title: String,
    val subtitle: String,
    val capability: String,
    val action: XLabFeedAction,
)

private data class FeatureUiModel(
    val categoryLabel: String,
    val watermark: String,
    val color: Color,
    val iconLabel: String,
    val title: String,
    val subtitle: String,
    val tags: List<String>,
    val highlightedTag: String,
    val action: XLabFeedAction,
)

private val pipelines = listOf(
    PipelineUiModel(
        sequence = "01",
        modeId = "MODE_01 / CAMERA",
        color = Mint,
        title = "摄像头实时流",
        subtitle = "实时采集摄像头画面，持续驱动视频生成。",
        capability = "createLocalCameraStream()",
        action = XLabFeedAction.CAMERA,
    ),
    PipelineUiModel(
        sequence = "02",
        modeId = "MODE_02 / VIDEO.FILE",
        color = VideoBlue,
        title = "视频生成管线",
        subtitle = "选择本地视频，将连续画面逐帧送入生成链路。",
        capability = "createLocalVideoStream()",
        action = XLabFeedAction.VIDEO,
    ),
    PipelineUiModel(
        sequence = "03",
        modeId = "MODE_03 / IMAGE.FILE",
        color = ImagePurple,
        title = "图片生成管线",
        subtitle = "选择本地图片，让静态画面持续流动起来。",
        capability = "createLocalImageStream()",
        action = XLabFeedAction.IMAGE,
    ),
)

private val features = listOf(
    FeatureUiModel(
        categoryLabel = "SDK RENDERING / TRAJECTORY",
        watermark = "FX",
        color = TrajectoryPink,
        iconLabel = "RENDER",
        title = "自定义轨迹渲染",
        subtitle = "使用自定义 Renderer 绘制交互轨迹。",
        tags = listOf("CANVAS", "MULTI-TOUCH", "CUSTOM EFFECT"),
        highlightedTag = "CUSTOM EFFECT",
        action = XLabFeedAction.TRAJECTORY,
    ),
    FeatureUiModel(
        categoryLabel = "SDK SERVICE / STORAGE",
        watermark = "URL",
        color = StorageOrange,
        iconLabel = "UPLOAD",
        title = "存储服务",
        subtitle = "上传图片或视频，获取可复用的远程地址",
        tags = listOf("IMAGE", "VIDEO", "REMOTE URL"),
        highlightedTag = "REMOTE URL",
        action = XLabFeedAction.STORAGE,
    ),
)

@Composable
public fun FeedScreen(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onAction: (XLabFeedAction) -> Unit,
) {
    CompositionLocalProvider(LocalTextStyle provides TextStyle.Default) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0C121B),
                            Color(0xFF070A0F),
                            Color(0xFF090D13),
                        ),
                        start = Offset.Zero,
                        end = Offset(850f, 1500f),
                    ),
                ),
        ) {
            AmbientGlow(
                color = Color(0xFF4B7BFF),
                size = 260.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 135.dp, y = (-55).dp),
            )
            AmbientGlow(
                color = Color(0xFF4DF0B5),
                size = 220.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-145).dp, y = 330.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 18.dp, top = 20.dp, end = 18.dp, bottom = 32.dp),
            ) {
                BrandHeader()
                HeroCard(modifier = Modifier.padding(top = 34.dp))
                RuntimeMetrics(modifier = Modifier.padding(top = 12.dp))
                ModelRegistryCard(
                    apiKey = apiKey,
                    onApiKeyChange = onApiKeyChange,
                    onApiKeyLinkClick = { onAction(XLabFeedAction.API_KEYS) },
                    modifier = Modifier.padding(top = 14.dp),
                )

                SectionHeader(
                    title = "GENERATION PIPELINES",
                    subtitle = "选择一种内容输入方式",
                    modifier = Modifier.padding(top = 30.dp, bottom = 14.dp),
                )
                pipelines.forEachIndexed { index, pipeline ->
                    PipelineCard(
                        model = pipeline,
                        onOpen = { onAction(pipeline.action) },
                        modifier = if (index == 0) Modifier else Modifier.padding(top = 14.dp),
                    )
                }

                SectionHeader(
                    title = "SDK FEATURES",
                    subtitle = "更多能力与接入示例",
                    modifier = Modifier.padding(top = 30.dp, bottom = 14.dp),
                )
                features.forEachIndexed { index, feature ->
                    FeatureCard(
                        model = feature,
                        onOpen = { onAction(feature.action) },
                        modifier = if (index == 0) Modifier else Modifier.padding(top = 14.dp),
                    )
                }
                Footer(modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun AmbientGlow(color: Color, size: Dp, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .blur(70.dp)
            .background(color.copy(alpha = 0.14f), CircleShape),
    )
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XmaxMark()
            Column(modifier = Modifier.padding(start = 11.dp)) {
                Text(
                    text = "XMAXSDK",
                    color = Color(0xFFF2F4F7),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                )
                Text(
                    text = "EXAMPLE / ANDROID",
                    modifier = Modifier.padding(top = 3.dp),
                    color = Color.White.copy(alpha = 0.38f),
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
        ConsoleTag(label = "v${XmaxSdk.VERSION.substringBefore('-')}")
    }
}

@Composable
private fun XmaxMark() {
    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(25.dp)
                .rotate(45f)
                .background(
                    Brush.linearGradient(listOf(Mint, Color(0xFF6495FF))),
                    RoundedCornerShape(8.dp),
                ),
        )
        Text(
            text = "X",
            color = Color(0xFF07110D),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ConsoleTag(
    label: String,
    color: Color = Mint,
    background: Color = Mint.copy(alpha = 0.086f),
) {
    Box(
        modifier = Modifier
            .height(26.dp)
            .background(background, RoundedCornerShape(13.dp))
            .border(1.dp, Color.White.copy(alpha = 0.19f), RoundedCornerShape(13.dp))
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HeroCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xE0141D28), Color(0xD00C1118))),
            )
            .border(1.dp, Color.White.copy(alpha = 0.125f), RoundedCornerShape(20.dp)),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 36.dp, y = 42.dp)
                .blur(28.dp)
                .background(Color(0xFF6895FF).copy(alpha = 0.08f), CircleShape),
        )
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(Mint, RoundedCornerShape(1.dp)),
                )
                Text(
                    text = "XMAX PLAYGROUND",
                    modifier = Modifier.padding(start = 7.dp),
                    color = Mint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
            }
            Text(
                text = "实时交互视频模型",
                modifier = Modifier.padding(top = 18.dp),
                color = Color(0xFFF5F7FB),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
            )
            Text(
                text = "选择输入源，启动 XmaxSDK 流式生成链路",
                modifier = Modifier.padding(top = 12.dp),
                color = Color(0xFF91A0B2),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun RuntimeMetrics(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuntimeMetric("RUNTIME", "Android", Modifier.weight(1f))
        RuntimeMetric("MIN API", "26+", Modifier.weight(1f))
        RuntimeMetric("LATEST MODEL", "X2.0", Modifier.weight(1f))
    }
}

@Composable
private fun RuntimeMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(66.dp)
            .background(Color(0xB30E141C), RoundedCornerShape(13.dp))
            .border(1.dp, Color.White.copy(alpha = 0.094f), RoundedCornerShape(13.dp))
            .padding(start = 12.dp, top = 12.dp, end = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.38f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 7.dp),
            color = Color(0xFFE8EDF5),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ModelRegistryCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onApiKeyLinkClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiKeyVisible by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(17.dp), ambientColor = Color.Black)
            .background(
                Brush.linearGradient(listOf(Color(0xE8121B19), Color(0xE80D1218))),
                RoundedCornerShape(17.dp),
            )
            .border(1.dp, Mint.copy(alpha = 0.145f), RoundedCornerShape(17.dp))
            .padding(18.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "选择你的模型",
                color = Color(0xFFE9EDF3),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "1 MODEL",
                color = Color.White.copy(alpha = 0.44f),
                fontSize = 8.sp,
                letterSpacing = 0.8.sp,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .background(Color(0x42080C12), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
                .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 11.dp),
        ) {
            Text(
                text = "API KEY",
                color = Color(0xFF7E8A9A),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
            )
            ApiKeyField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                isVisible = apiKeyVisible,
                onToggleVisibility = { apiKeyVisible = !apiKeyVisible },
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(modifier = Modifier.padding(top = 7.dp)) {
                Text(
                    text = "还没有 API Key？",
                    color = Color(0xFF607080).copy(alpha = 0.56f),
                    fontSize = 9.sp,
                )
                Text(
                    text = "前往 Xmax 开放平台申请",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clickable(onClick = onApiKeyLinkClick),
                    color = Mint.copy(alpha = 0.63f),
                    fontSize = 9.sp,
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            color = Color.White.copy(alpha = 0.094f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Mint.copy(alpha = 0.063f), RoundedCornerShape(10.dp))
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("◆", color = Mint, fontSize = 7.sp)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = "X2.0",
                    color = Color(0xFFF0F2F5),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "RealtimeModel.${RealtimeModel.X2_0.name}",
                    modifier = Modifier.padding(top = 3.dp),
                    color = Color.White.copy(alpha = 0.44f),
                    fontSize = 8.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            ConsoleTag(label = "ACTIVE")
        }
    }
}

@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    isVisible: Boolean,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0x66080C12), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.11f), RoundedCornerShape(10.dp)),
        textStyle = TextStyle(color = Color(0xFFD6DEE9), fontSize = 10.sp),
        singleLine = true,
        visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
        cursorBrush = Brush.verticalGradient(listOf(Mint, Mint)),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = "输入 Xmax API Key",
                            color = Color(0xFF607080).copy(alpha = 0.31f),
                            fontSize = 10.sp,
                        )
                    }
                    innerTextField()
                }
                VisibilityGlyph(
                    isVisible = isVisible,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onToggleVisibility)
                        .padding(2.dp),
                )
            }
        },
    )
}

@Composable
private fun VisibilityGlyph(isVisible: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val color = Color(0xFFB8C3D1)
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val eyeRect = Rect(
            left = size.width * 0.1f,
            top = size.height * 0.27f,
            right = size.width * 0.9f,
            bottom = size.height * 0.73f,
        )
        drawOval(color = color, topLeft = eyeRect.topLeft, size = eyeRect.size, style = stroke)
        if (isVisible) {
            drawCircle(color = color, radius = size.minDimension * 0.11f, style = stroke)
        } else {
            drawLine(
                color = color,
                start = Offset(size.width * 0.14f, size.height * 0.14f),
                end = Offset(size.width * 0.86f, size.height * 0.86f),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = Color(0xFFC6D0DD),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 5.dp),
            color = Color(0xFF667384),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PipelineCard(
    model: PipelineUiModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(20.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xF0141B25), Color(0xF00C1118))),
            )
            .border(1.dp, Color.White.copy(alpha = 0.133f), RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .align(Alignment.TopEnd)
                .offset(x = 38.dp, y = (-46).dp)
                .background(model.color.copy(alpha = 0.1f), CircleShape),
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(70.dp)
                .align(Alignment.CenterStart)
                .background(
                    model.color,
                    RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp),
                ),
        )
        Text(
            text = model.sequence,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 5.dp, end = 17.dp),
            color = Color.White.copy(alpha = 0.031f),
            fontSize = 54.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.padding(start = 18.dp, top = 17.dp, end = 18.dp, bottom = 17.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .shadow(7.dp, CircleShape, ambientColor = model.color)
                        .background(model.color, CircleShape),
                )
                Text(
                    text = model.modeId,
                    modifier = Modifier.padding(start = 8.dp),
                    color = Color(0xFF9AA7B7),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.weight(1f))
                StatusPill(label = "READY", color = model.color, withDot = true)
            }
            Text(
                text = model.title,
                modifier = Modifier.padding(top = 17.dp),
                color = PrimaryText,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = model.subtitle,
                modifier = Modifier.padding(top = 7.dp),
                color = SecondaryText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 2,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(Color(0x66080C12), RoundedCornerShape(10.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.086f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = model.capability,
                        color = Color(0xFFB8C3D1),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .width(82.dp)
                        .height(36.dp)
                        .shadow(10.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black)
                        .background(model.color, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "运行",
                        color = Color(0xFF08110E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color, withDot: Boolean = false) {
    Row(
        modifier = Modifier
            .height(25.dp)
            .background(Color.White.copy(alpha = 0.047f), RoundedCornerShape(13.dp))
            .border(1.dp, Color.White.copy(alpha = 0.094f), RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (withDot) {
            Text("●", color = color, fontSize = 7.sp)
        }
        Text(
            text = label,
            color = color,
            fontSize = if (withDot) 9.sp else 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
        )
    }
}

@Composable
private fun FeatureCard(
    model: FeatureUiModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(20.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color(0xF01C1813),
                        0.72f to Color(0xF00D1117),
                        1f to Color(0xF0151210),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.133f), RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
    ) {
        Box(
            modifier = Modifier
                .size(126.dp)
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = (-52).dp)
                .background(model.color.copy(alpha = 0.09f), CircleShape),
        )
        Text(
            text = model.watermark,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-16).dp, y = 9.dp),
            color = Color.White.copy(alpha = 0.031f),
            fontSize = 45.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-2).sp,
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(54.dp)
                .align(Alignment.CenterStart)
                .background(
                    model.color,
                    RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp),
                ),
        )

        Column(modifier = Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .shadow(7.dp, CircleShape, ambientColor = model.color)
                        .background(model.color, CircleShape),
                )
                Text(
                    text = model.categoryLabel,
                    modifier = Modifier.padding(start = 8.dp),
                    color = Color(0xFFA99A8A),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                Spacer(Modifier.weight(1f))
                StatusPill(label = "AVAILABLE", color = model.color)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeatureIcon(model = model)
                Column(modifier = Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(
                        text = model.title,
                        color = PrimaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = model.subtitle,
                        modifier = Modifier.padding(top = 5.dp),
                        color = Color(0xFF81786F),
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .width(58.dp)
                        .height(34.dp)
                        .shadow(10.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black)
                        .background(model.color, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "进入",
                        color = Color(0xFF08110E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                model.tags.forEach { tag ->
                    FeatureTag(
                        label = tag,
                        color = model.color,
                        highlighted = tag == model.highlightedTag,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureIcon(model: FeatureUiModel) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                Brush.linearGradient(listOf(model.color.copy(alpha = 0.28f), Color(0xFF101B17))),
                RoundedCornerShape(14.dp),
            )
            .border(1.dp, model.color.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
    ) {
        val glyphModifier = Modifier
            .size(width = 24.dp, height = 22.dp)
            .align(Alignment.TopCenter)
            .offset(y = 7.dp)
        if (model.action == XLabFeedAction.TRAJECTORY) {
            TrajectoryGlyph(color = model.color, modifier = glyphModifier)
        } else {
            StorageGlyph(modifier = glyphModifier)
        }
        Text(
            text = model.iconLabel,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 30.dp),
            color = model.color,
            fontSize = 6.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            lineHeight = 7.sp,
        )
    }
}

@Composable
private fun TrajectoryGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 64f
        val origin = Offset(
            x = (size.width - 64f * scale) / 2f,
            y = (size.height - 64f * scale) / 2f,
        )
        fun point(x: Float, y: Float): Offset = origin + Offset(x * scale, y * scale)

        val path = Path().apply {
            val start = point(10f, 48f)
            moveTo(start.x, start.y)
            val control1 = point(15f, 34f)
            val control2 = point(23f, 43f)
            val middle = point(29f, 29f)
            cubicTo(
                control1.x,
                control1.y,
                control2.x,
                control2.y,
                middle.x,
                middle.y,
            )
            val control3 = point(34f, 17f)
            val control4 = point(42f, 13f)
            val end = point(52f, 18f)
            cubicTo(
                control3.x,
                control3.y,
                control4.x,
                control4.y,
                end.x,
                end.y,
            )
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 6f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(color = color, radius = 5f * scale, center = point(10f, 48f))
        drawCircle(
            color = Color(0xFF211322),
            radius = 8f * scale,
            center = point(52f, 18f),
        )
        drawCircle(
            color = color,
            radius = 8f * scale,
            center = point(52f, 18f),
            style = Stroke(width = 5f * scale),
        )
        drawCircle(color = color, radius = 2.5f * scale, center = point(52f, 18f))
        drawLine(
            color = color,
            start = point(39f, 8f),
            end = point(42f, 4f),
            strokeWidth = 3f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = point(58f, 31f),
            end = point(61f, 35f),
            strokeWidth = 3f * scale,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun StorageGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val scale = minOf(size.width, size.height) / 64f
        val origin = Offset(
            x = (size.width - 64f * scale) / 2f,
            y = (size.height - 64f * scale) / 2f,
        )
        fun point(x: Float, y: Float): Offset = origin + Offset(x * scale, y * scale)

        val arrow = Path().apply {
            val points = listOf(
                point(32f, 11f),
                point(51f, 29.5f),
                point(45.5f, 35f),
                point(36f, 25.5f),
                point(36f, 53f),
                point(28f, 53f),
                point(28f, 25.5f),
                point(18.5f, 35f),
                point(13f, 29.5f),
            )
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path = arrow, color = Color(0xFFF8C47F))
    }
}

@Composable
private fun FeatureTag(label: String, color: Color, highlighted: Boolean) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .background(
                if (highlighted) Color.White.copy(alpha = 0.07f) else Color(0x65080C12),
                RoundedCornerShape(8.dp),
            )
            .border(1.dp, Color.White.copy(alpha = 0.094f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (highlighted) color else Color(0xFFA89A8B),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun Footer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider(
            modifier = Modifier.width(36.dp),
            color = Color.White.copy(alpha = 0.094f),
        )
        Text(
            text = "Copyright © 2026 XMAX.AI PTE.LTD All rights reserved.",
            modifier = Modifier.padding(top = 18.dp),
            color = Color.White.copy(alpha = 0.31f),
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "sdk@xmax.ai",
            modifier = Modifier.padding(top = 7.dp),
            color = Mint.copy(alpha = 0.41f),
            fontSize = 9.sp,
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
        )
    }
}

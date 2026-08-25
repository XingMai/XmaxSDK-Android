package com.xmax.xlab

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxSdk

private data class Pipeline(
    val id: String,
    val title: String,
    val subtitle: String,
    val capability: String,
)

private val pipelines = listOf(
    Pipeline("01", "实时相机生成", "相机预览、连接与实时生成链路", "CAMERA / RTC"),
    Pipeline("02", "视频与图片输入", "从本地媒体创建或替换输入流", "MEDIA INPUT"),
    Pipeline("03", "轨迹交互", "将 Compose 触控轨迹映射到模型坐标", "TRAJECTORY"),
    Pipeline("04", "对象存储", "上传图片与视频并返回可用 URL", "STORAGE"),
)

@Composable
public fun XLabApp() {
    var apiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedPipeline by remember { mutableStateOf<Pipeline?>(null) }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B1516), Color(0xFF070A0E)),
                    ),
                )
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Header()
            }
            item {
                RuntimeMetrics()
            }
            item {
                ModelRegistryCard(
                    apiKey = apiKey,
                    apiKeyVisible = apiKeyVisible,
                    onApiKeyChange = { apiKey = it },
                    onToggleVisibility = { apiKeyVisible = !apiKeyVisible },
                )
            }
            item {
                SectionTitle("SDK PLAYGROUNDS", "${pipelines.size} PIPELINES")
            }
            items(pipelines, key = { it.id }) { pipeline ->
                PipelineCard(
                    pipeline = pipeline,
                    isSelected = selectedPipeline == pipeline,
                    onClick = { selectedPipeline = pipeline },
                )
            }
            selectedPipeline?.let { pipeline ->
                item {
                    PortingNotice(
                        pipeline = pipeline,
                        hasApiKey = apiKey.isNotBlank(),
                        onClose = { selectedPipeline = null },
                    )
                }
            }
            item {
                Text(
                    text = "XmaxSDK Android bootstrap · API keys are not persisted",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    color = Color(0xFF647181),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                text = "  XMAX DEVELOPER CONSOLE",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
        }
        Text(
            text = "XLab",
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 36.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "Android SDK 实验场",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun RuntimeMetrics() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Metric("PLATFORM", "API ${Build.VERSION.SDK_INT}", Modifier.weight(1f))
        Metric("SDK", XmaxSdk.VERSION, Modifier.weight(1f))
        Metric("STATUS", "BOOTSTRAP", Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xB30E141C), RoundedCornerShape(13.dp))
            .padding(12.dp),
    ) {
        Text(label, color = Color(0xFF718091), fontSize = 8.sp, letterSpacing = 1.sp)
        Text(
            value,
            modifier = Modifier.padding(top = 7.dp),
            color = Color(0xFFE8EDF5),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ModelRegistryCard(
    apiKey: String,
    apiKeyVisible: Boolean,
    onApiKeyChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE8121B19), RoundedCornerShape(17.dp))
            .padding(18.dp),
    ) {
        SectionTitle("选择你的模型", "1 MODEL")
        Text(
            text = "API KEY",
            modifier = Modifier.padding(top = 16.dp),
            color = Color(0xFF7E8A9A),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            placeholder = { Text("输入 Xmax API Key") },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = onToggleVisibility) {
                    Text(if (apiKeyVisible) "隐藏" else "显示")
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0x66080C12),
                unfocusedContainerColor = Color(0x66080C12),
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("X2.0", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "RealtimeModel.${RealtimeModel.X2_0.name}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            StatusTag("ACTIVE")
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(detail, color = Color(0xFF718091), fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun PipelineCard(
    pipeline: Pipeline,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE80D1218), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pipeline.id,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                "  ${pipeline.capability}",
                color = Color(0xFF9AA7B7),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            StatusTag("PORTING", accent)
        }
        Text(
            pipeline.title,
            modifier = Modifier.padding(top = 16.dp),
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            pipeline.subtitle,
            modifier = Modifier.padding(top = 7.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatusTag(label: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = label,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
    )
}

@Composable
private fun PortingNotice(
    pipeline: Pipeline,
    hasApiKey: Boolean,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF151D27), RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Text("${pipeline.title} · Android 移植中", fontWeight = FontWeight.Bold)
        Text(
            text = if (hasApiKey) {
                "API Key 已在当前进程中就绪。对应能力尚未接入，避免把占位实现误认为真实 SDK 行为。"
            } else {
                "可以先输入 API Key 验证示例配置；对应能力将在 RTC、媒体或存储实现落地后开放。"
            },
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Button(
            onClick = onClose,
            modifier = Modifier.padding(top = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("知道了")
        }
    }
}

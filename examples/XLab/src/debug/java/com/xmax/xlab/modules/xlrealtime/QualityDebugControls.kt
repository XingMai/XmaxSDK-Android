//
//  QualityDebugControls.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.RealtimeQualityDebug
import ai.xmax.sdk.XmaxRealtimeManaging
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmax.xlab.R
import com.xmax.xlab.xLabText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 在统计面板中提供上下行档位选择，仅用于 Debug 测试。
 */
@Composable
internal fun QualityDebugControls(manager: XmaxRealtimeManaging) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uplink by remember(manager) { mutableStateOf(RealtimeQualityDebug.state(manager, false)) }
    var downlink by remember(manager) { mutableStateOf(RealtimeQualityDebug.state(manager, true)) }
    var busy by remember(manager) { mutableStateOf(false) }
    val unavailable = xLabText(R.string.quality_debug_busy)
    val failure = xLabText(R.string.quality_debug_failed)

    fun refresh() {
        uplink = RealtimeQualityDebug.state(manager, false)
        downlink = RealtimeQualityDebug.state(manager, true)
    }

    fun select(isDownlink: Boolean, level: Int) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (!RealtimeQualityDebug.select(manager, isDownlink, level)) {
                    Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context, "$failure: ${error.message.orEmpty()}", Toast.LENGTH_SHORT).show()
            } finally {
                refresh()
                busy = false
            }
        }
    }

    LaunchedEffect(manager) {
        while (true) {
            refresh()
            delay(500)
        }
    }

    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(xLabText(R.string.quality_debug_title), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        QualityDirectionControls(xLabText(R.string.statistics_uplink), uplink, busy) { select(false, it) }
        QualityDirectionControls(xLabText(R.string.statistics_downlink), downlink, busy) { select(true, it) }
        Text(xLabText(R.string.quality_debug_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, lineHeight = 12.sp)
    }
}

/**
 * 展示单个方向的请求规格与 L1～L5 按钮。
 */
@Composable
private fun QualityDirectionControls(
    title: String,
    state: RealtimeQualityDebug.State?,
    busy: Boolean,
    onSelect: (Int) -> Unit,
) {
    val percentages = listOf(100, 80, 65, 50, 50)
    Text(
        "$title · ${state?.let { "L${it.level} (${percentages[it.level - 1]}%)" } ?: "—"}",
        color = Color.White,
        fontSize = 10.sp,
    )
    Text(
        state?.format?.let { "${it.width} × ${it.height}" } ?: xLabText(R.string.quality_debug_inactive),
        color = Color.White.copy(alpha = 0.65f),
        fontSize = 10.sp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..5).forEach { level ->
            val selected = state?.level == level
            val enabled = state != null && !busy
            Box(
                Modifier.weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Color(0xFF2563A6) else Color.White.copy(alpha = 0.12f))
                    .semantics { this.selected = selected }
                    .clickable(enabled = enabled, role = Role.Button) { onSelect(level) },
                contentAlignment = Alignment.Center,
            ) {
                Text("L$level", color = Color.White.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 10.sp)
            }
        }
    }
}

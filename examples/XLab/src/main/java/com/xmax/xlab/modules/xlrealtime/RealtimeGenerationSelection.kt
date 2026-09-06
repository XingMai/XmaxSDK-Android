package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.RealtimeContext
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 用户的生成意图独立于正在执行的请求，切换媒体时保留，停止或当前请求失败时清除。 */
@MainThread
internal class RealtimeGenerationSelection {
    // 上传中的本地参考图也可以先选中，此时尚未形成可提交的生成条件。
    var referenceId: String? by mutableStateOf(null)

    var current: Intent? by mutableStateOf(null)
        private set

    /** context 为 null 表示让当前输入动起来，参考图须随新的输入源重新解析。 */
    fun select(context: RealtimeContext?, referenceId: String? = null): Intent =
        Intent(context).also {
            this.referenceId = referenceId
            current = it
        }

    fun clear(expected: Intent? = current): Boolean {
        if (current !== expected) return false
        current = null
        referenceId = null
        return true
    }

    class Intent internal constructor(
        private val context: RealtimeContext?,
    ) {
        val isMotion: Boolean get() = context == null

        fun resolveContext(sourceImageReference: String?): RealtimeContext =
            context ?: RealtimeContext("让画面自然动起来", sourceImageReference)
    }
}

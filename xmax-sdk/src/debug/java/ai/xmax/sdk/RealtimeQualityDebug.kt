//
//  RealtimeQualityDebug.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.stream.quality.QualityVideoFormat

/**
 * Debug 构建专用的档位测试入口。
 */
public object RealtimeQualityDebug {
    /**
     * 当前请求的传输档位与规格，不代表 RTC 实际编码或接收结果。
     *
     * @param level 需要应用或保存的质量档位，1 为最高档。
     * @param format 视频尺寸、帧率及上行编码参数。
     */
    public data class State(
        public val level: Int,
        public val format: RealtimeVideoFormat,
    )

    /**
     * 读取正在生成的指定方向档位；未生成或该方向不可用时返回 null。
     *
     * @param manager 需要调整质量档位的实时管理器。
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @return 已解除绑定的房间状态；没有可清理状态时为 null。
     */
    public fun state(manager: XmaxRealtimeManaging, downlink: Boolean): State? {
        val direction = (manager as? XmaxRealtimeManager)?.qualityDebugDirection(downlink) ?: return null
        val level = direction.level
        return State(level, QualityVideoFormat.resolve(direction.baseline, level, downlink))
    }

    /**
     * 选择并记住 L1～L5，保留自动调档；忙碌或任务已结束时返回 false。
     *
     * @param manager 需要调整质量档位的实时管理器。
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @param level 需要应用或保存的质量档位，1 为最高档。
     * @return 已成功应用所选档位时为 true，否则为 false。
     */
    public suspend fun select(manager: XmaxRealtimeManaging, downlink: Boolean, level: Int): Boolean =
        (manager as? XmaxRealtimeManager)?.selectQualityDebugLevel(downlink, level) ?: false
}

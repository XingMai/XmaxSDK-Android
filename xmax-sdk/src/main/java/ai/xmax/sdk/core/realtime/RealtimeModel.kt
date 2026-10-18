//
//  RealtimeModel.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize

/**
 * SDK 支持的实时生成模型；[id] 是创建服务端会话时使用的模型标识。
 *
 * @param id 用于区分当前对象或操作的唯一标识。
 */
public enum class RealtimeModel(public val id: String) {
    /**
     * 国内声网实时生成模型。
     */
    X2_1_1_PREVIEW_CN("x2.1.1-preview-cn"),

    /**
     * 海外声网实时生成模型。
     */
    X2_1_1_PREVIEW_US("x2.1.1-preview-us"),

    /**
     * Dev 环境的轨迹交互测试模型。
     */
    X2_1_PREVIEW_DRAG("x2.1-preview-drag"),

    ;

    /**
     * 模型展示名称和服务入口。
     *
     * 界面展示名称，与创建会话时使用的 [id] 分开维护。
     */
    public val displayName: String
        get() = when (this) {
            X2_1_1_PREVIEW_CN -> "X2.1.1-preview-cn"
            X2_1_1_PREVIEW_US -> "X2.1.1-preview-us"
            X2_1_PREVIEW_DRAG -> "X2.1-preview-drag (Dev)"
        }

    /**
     * 模型专用的会话 API 地址；null 表示沿用客户端环境地址。
     */
    public val baseUrl: String?
        get() = when (this) {
            X2_1_1_PREVIEW_CN -> "https://cloud.xmax.22duck.cn/open/api/v1"
            X2_1_1_PREVIEW_US -> "https://api.xmax.ai/open/api/v1"
            X2_1_PREVIEW_DRAG -> "https://dev.22duck.cn/open/api/v1"
        }

    /**
     * 模型生成画布与输入尺寸约束。
     *
     * 模型支持的生成画布分辨率桶；直接指定模型输入时宽高须精确匹配。
     * 本地图片和视频保留媒体输入比例，SDK 从桶中选择比例最接近的画布，由服务端等比适配。
     * 空列表表示不限制固定尺寸，生成画布按像素面积上下限和模型对齐规则计算。
     */
    public val resolutionBuckets: List<IntSize>
        get() = listOf(
            IntSize(width = 1_024, height = 1_920),
            IntSize(width = 1_920, height = 1_024),
        )

    /**
     * 生成画布的最小总像素面积；仅在分辨率桶为空时参与计算，不放大小尺寸媒体帧。
     */
    public val minimumInputPixels: Int
        get() = 600_000

    /**
     * 本地媒体帧的最大总像素面积；分辨率桶为空时也用于限制生成画布。
     */
    public val maximumInputPixels: Int
        get() = 2_100_000

    /**
     * 输入宽度和高度分别需要对齐的像素倍数；仅在分辨率桶为空时参与尺寸计算。
     */
    public val inputSizeAlignment: Int
        get() = 32

    /**
     * 默认媒体输入规格。
     *
     * 未指定视频规格时使用的默认帧率。
     */
    public val defaultFrameRate: Int
        get() = 30

    /**
     * 摄像头采集使用的默认视频规格。
     */
    public val defaultCameraVideoFormat: RealtimeVideoFormat
        get() = RealtimeVideoFormat(
            width = 1_024,
            height = 1_920,
            fps = defaultFrameRate,
        )
}

/**
 * 创建实时 Manager 所需的业务配置。
 *
 * @param model 用于确定服务端能力、地址及输入规格的模型；默认值为 `RealtimeModel.X2_1_1_PREVIEW_CN`。
 */
public data class RealtimeConfiguration(
    /**
     * 新建实时会话使用的模型，默认国内模型；海外业务请显式选择 US 模型。
     */
    public val model: RealtimeModel = RealtimeModel.X2_1_1_PREVIEW_CN,
)

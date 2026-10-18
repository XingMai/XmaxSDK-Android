//
//  MediaServicing.kt
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
import androidx.compose.ui.geometry.Size

/**
 * 定义本地媒体、模型生成画布和回传尺寸的业务规则。
 */
public interface MediaServicing {
    /**
     * 当前媒体规则使用的实时生成模型。
     */
    public val model: RealtimeModel

    /**
     * 计算不放大、不超过模型最大像素面积的本地媒体尺寸，宽高按 4 对齐并优先保持精确比例。
     *
     * @param size 指定的视频格式尺寸；未指定格式时为原始媒体的显示尺寸。
     * @param source 媒体来源；摄像头请求尺寸须精确匹配模型已配置的分辨率桶。
     * @return 满足面积上限、比例和 4 对齐约束的媒体尺寸。
     * @throws XmaxError 尺寸无效、摄像头未匹配分辨率桶或无法形成至少 4 × 4 的输出。
     */
    public fun resolveMediaSize(size: IntSize, source: RealtimeMediaSource): IntSize

    /**
     * 为媒体选择比例最接近的模型生成画布，不改变本地媒体规格。
     *
     * @param size 媒体输入的显示尺寸，单位为像素。
     * @return 匹配的模型分辨率桶；无分辨率桶时返回满足模型面积范围和对齐要求的画布。
     * @throws XmaxError 媒体宽高不是正数。
     */
    public fun resolveGenerationSize(size: IntSize): IntSize

    /**
     * 按下行宽高上限计算最终回传尺寸，不放大媒体并优先保持精确比例。
     *
     * @param sourceSize 固定媒体基准尺寸，单位为像素。
     * @param maximumSize 下行允许的宽高上限，可包含小数像素。
     * @return 不超过上限且宽高均为 4 的倍数的回传尺寸。
     * @throws XmaxError 尺寸无效或上限内无法容纳至少 4 × 4 像素。
     */
    public fun resolveTargetSize(sourceSize: IntSize, maximumSize: Size): IntSize
}

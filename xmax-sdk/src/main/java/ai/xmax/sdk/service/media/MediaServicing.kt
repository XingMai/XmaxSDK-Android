package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize

/** 定义模型输入尺寸相关的业务规则。 */
public interface MediaServicing {
    /** 当前媒体规则使用的实时生成模型。 */
    public val model: RealtimeModel

    /**
     * 计算满足模型输入约束的尺寸。
     *
     * 分辨率桶非空时，精确匹配后原样返回；为空时按像素面积上下限及对齐要求计算。
     *
     * @param size 指定的视频格式尺寸；未指定格式时为原始媒体的显示尺寸。
     * @return 模型分辨率桶非空时，精确匹配后原样返回；为空时返回缩放并对齐后的尺寸。
     * @throws XmaxError 尺寸无效或未匹配模型支持的分辨率桶。
     */
    public fun resolveModelInputSize(size: IntSize): IntSize
}

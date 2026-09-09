package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize

/** 定义模型输入尺寸相关的业务规则。 */
public interface MediaServicing {
    /** 当前媒体规则使用的实时生成模型。 */
    public val model: RealtimeModel

    /** 按当前模型的像素面积上下限及对齐要求计算输入尺寸。 */
    public fun resolveModelInputSize(size: IntSize): IntSize
}

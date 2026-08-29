package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize

/** 定义模型输入尺寸和平台媒体能力相关的业务规则。 */
public interface MediaServicing {
    public fun resolveModelInputSize(size: IntSize): IntSize

    public fun supportsFrameInterpolation(size: IntSize): Boolean
}

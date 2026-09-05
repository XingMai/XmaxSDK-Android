package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize

/** 定义模型输入尺寸相关的业务规则。 */
public interface MediaServicing {
    public fun resolveModelInputSize(size: IntSize): IntSize
}

package ai.xmax.sdk.stream.quality

/**
 * 上下行共用的尺寸比例与上行帧率上限，档位编号从 1 开始。
 */
internal object QualityTiers {
    const val INITIAL_LEVEL = 2
    const val MINIMUM_SHORT_SIDE = 240
    val percentages = listOf(100, 80, 65, 50, 50)
    val frameRates = listOf(30, 24, 24, 20, 16)
}

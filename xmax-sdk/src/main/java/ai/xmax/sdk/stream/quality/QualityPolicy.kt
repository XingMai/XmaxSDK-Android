package ai.xmax.sdk.stream.quality

/**
 * 自动调档的连续样本阈值和时间窗口，时间单位为毫秒。
 */
internal data class QualityPolicy(
    val poorSamples: Int = 3,
    val goodSamples: Int = 5,
    val stabilizationMs: Long = 4_000,
    val probeMs: Long = 20_000,
    val upgradeCooldownMs: Long = 60_000,
    val sampleTimeoutMs: Long = 5_000,
)

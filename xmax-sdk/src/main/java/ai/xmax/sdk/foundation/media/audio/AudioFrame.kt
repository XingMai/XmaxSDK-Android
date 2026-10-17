//
//  AudioFrame.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * RTC 外部音频链路使用的 48 kHz 单声道 PCM 音频帧。
 *
 * @param data 待读取、传输或持有的二进制数据。
 * @param timestampUs 帧的单调时间戳，单位为微秒。
 */
internal class AudioFrame(
    data: ByteArray,
    val timestampUs: Long,
) {
    /**
     * 音频有效字节及安全读取接口。
     */
    private val bytes: ByteArray = data.copyOf()

    val data: ByteArray
        get() = bytes.copyOf()

    /**
     * 复制音频帧的有效 PCM 字节，供调用方独立持有。
     *
     * @return 有效数据对应的字节数组。
     */
    internal fun dataBytes(): ByteArray = bytes

    /**
     * 比较对象的值是否相等。
     *
     * @param other 用于比较或组合的另一个对象。
     * @return 对象的内容相等时为 true，否则为 false。
     */
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AudioFrame &&
            timestampUs == other.timestampUs &&
            bytes.contentEquals(other.bytes)

    /**
     * 生成与值相等规则一致的哈希值。
     *
     * @return 与值相等规则一致的哈希值。
     */
    override fun hashCode(): Int = 31 * bytes.contentHashCode() + timestampUs.hashCode()

    /**
     * 提供 AudioFrame 使用的构造入口、共享配置或常量。
     */
    internal companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val sampleRate: Int = 48_000
        const val channelCount: Int = 1
        const val samplesPerFrame: Int = 480
    }
}

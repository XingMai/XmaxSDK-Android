package ai.xmax.sdk

/** RTC 外部音频链路使用的 48 kHz 单声道 PCM 音频帧。 */
internal class AudioFrame(
    data: ByteArray,
    val timestampUs: Long,
) {
    private val bytes: ByteArray = data.copyOf()

    val data: ByteArray
        get() = bytes.copyOf()

    internal fun dataBytes(): ByteArray = bytes

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is AudioFrame &&
            timestampUs == other.timestampUs &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + timestampUs.hashCode()

    internal companion object {
        const val sampleRate: Int = 48_000
        const val channelCount: Int = 1
        const val samplesPerFrame: Int = 480
    }
}

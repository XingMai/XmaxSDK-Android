package ai.xmax.sdk.stream

/** SDK 内部使用的本地与远端媒体流标识。 */
internal enum class StreamID(val value: String) {
    LOCAL("stream-local"),
    REMOTE("stream-remote"),
}

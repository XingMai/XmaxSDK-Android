package ai.xmax.sdk

/** 生成稳定且不为空的错误描述。 */
internal object ErrorMessageFormatter {
    fun format(error: Throwable): String = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error.javaClass.simpleName.takeIf(String::isNotEmpty)
        ?: error.toString()
}

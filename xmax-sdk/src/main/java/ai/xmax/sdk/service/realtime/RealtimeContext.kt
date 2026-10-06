package ai.xmax.sdk

/** 单次实时生成任务的文本和参考资源上下文。 */
public class RealtimeContext(
    prompt: String,
    referencePath: String? = null,
    public val referenceVideo: RealtimeReferenceVideo? = null,
) {
    public val prompt: String = prompt.trim()
    public val referencePath: String? = referencePath
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    public operator fun component1(): String = prompt

    public operator fun component2(): String? = referencePath

    public operator fun component3(): RealtimeReferenceVideo? = referenceVideo

    public fun copy(
        prompt: String = this.prompt,
        referencePath: String? = this.referencePath,
        referenceVideo: RealtimeReferenceVideo? = this.referenceVideo,
    ): RealtimeContext = RealtimeContext(prompt, referencePath, referenceVideo)

    override fun equals(other: Any?): Boolean = other is RealtimeContext &&
        prompt == other.prompt &&
        referencePath == other.referencePath && referenceVideo == other.referenceVideo

    override fun hashCode(): Int = 31 * (31 * prompt.hashCode() +
        (referencePath?.hashCode() ?: 0)) + (referenceVideo?.hashCode() ?: 0)

    override fun toString(): String =
        "RealtimeContext(prompt=$prompt, referencePath=$referencePath, referenceVideo=$referenceVideo)"
}

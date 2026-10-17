//
//  RealtimeContext.kt
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
 * 单次实时生成任务的文本和参考资源上下文。
 *
 * @param prompt 描述期望生成内容的提示词。
 * @param referencePath 参考图片的可访问地址；null 表示不使用参考图片。
 * @param referenceVideo 由服务端读取的网络视频及采样配置；null 表示不使用网络视频。
 */
public class RealtimeContext(
    prompt: String,
    referencePath: String? = null,
    public val referenceVideo: RealtimeReferenceVideo? = null,
) {
    /**
     * 文本提示词与参考图片配置。
     */
    public val prompt: String = prompt.trim()
    public val referencePath: String? = referencePath
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    /**
     * 取得用于解构的生成提示词。
     *
     * @return 当前生成提示词。
     */
    public operator fun component1(): String = prompt

    /**
     * 取得用于解构的参考图片地址。
     *
     * @return 当前参考图片地址；未设置时为 null。
     */
    public operator fun component2(): String? = referencePath

    /**
     * 取得用于解构的网络视频配置。
     *
     * @return 配置的网络视频参考；未设置时为 null。
     */
    public operator fun component3(): RealtimeReferenceVideo? = referenceVideo

    /**
     * 创建包含指定生成条件的新上下文，未指定字段沿用当前值。
     *
     * @param prompt 描述期望生成内容的提示词；默认值为 `this.prompt`。
     * @param referencePath 参考图片的可访问地址；null 表示不使用参考图片。
     * @param referenceVideo 由服务端读取的网络视频及采样配置；null 表示不使用网络视频。
     * @return 包含本次提示词及参考素材的新生成上下文。
     */
    public fun copy(
        prompt: String = this.prompt,
        referencePath: String? = this.referencePath,
        referenceVideo: RealtimeReferenceVideo? = this.referenceVideo,
    ): RealtimeContext = RealtimeContext(prompt, referencePath, referenceVideo)

    /**
     * 比较对象的值是否相等。
     *
     * @param other 用于比较或组合的另一个对象。
     * @return 对象的内容相等时为 true，否则为 false。
     */
    override fun equals(other: Any?): Boolean = other is RealtimeContext &&
        prompt == other.prompt &&
        referencePath == other.referencePath && referenceVideo == other.referenceVideo

    /**
     * 生成与值相等规则一致的哈希值。
     *
     * @return 与值相等规则一致的哈希值。
     */
    override fun hashCode(): Int = 31 * (31 * prompt.hashCode() +
        (referencePath?.hashCode() ?: 0)) + (referenceVideo?.hashCode() ?: 0)

    /**
     * 返回当前提示词、参考图片与网络视频配置的描述。
     *
     * @return 适合日志或诊断显示的对象描述。
     */
    override fun toString(): String =
        "RealtimeContext(prompt=$prompt, referencePath=$referencePath, referenceVideo=$referenceVideo)"
}

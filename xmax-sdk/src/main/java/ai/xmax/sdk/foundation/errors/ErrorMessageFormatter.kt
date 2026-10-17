//
//  ErrorMessageFormatter.kt
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
 * 生成稳定且不为空的错误描述。
 */
internal object ErrorMessageFormatter {
    /**
     * 生成适合向接入方展示或记录的错误描述。
     *
     * @param error 本次操作失败的异常或 SDK 错误。
     * @return 包含异常原因的可读文本。
     */
    fun format(error: Throwable): String = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error.javaClass.simpleName.takeIf(String::isNotEmpty)
        ?: error.toString()
}

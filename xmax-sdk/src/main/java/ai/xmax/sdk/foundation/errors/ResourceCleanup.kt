//
//  ResourceCleanup.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 依次执行可独立完成的释放步骤；一个步骤失败不跳过其余步骤。
 *
 * @param actions 按顺序执行的资源清理操作。
 */
internal suspend fun cleanupResources(vararg actions: suspend () -> Unit) {
    val failure = withContext(NonCancellable) {
        var failure: Throwable? = null
        for (action in actions) {
            try {
                action()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure
    }
    /**
     * 保留收集到的原始异常及其附加清理错误。
     */
    failure?.let { throw it }
}

/**
 * 回滚失败作为补充信息，保留操作原始错误（包括 CancellationException）。
 *
 * @param error 本次操作失败的异常或 SDK 错误。
 * @param actions 按顺序执行的资源清理操作。
 */
internal suspend fun cleanupAfterFailure(error: Throwable, vararg actions: suspend () -> Unit) {
    try {
        cleanupResources(*actions)
    } catch (cleanupError: Throwable) {
        if (cleanupError !== error) error.addSuppressed(cleanupError)
    }
}

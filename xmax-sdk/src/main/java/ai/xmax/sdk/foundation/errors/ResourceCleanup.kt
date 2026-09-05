package ai.xmax.sdk

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 依次执行可独立完成的释放步骤；一个步骤失败不跳过其余步骤。 */
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
    // Throw outside withContext so stack-trace recovery does not copy the collected failure.
    failure?.let { throw it }
}

/** 回滚失败作为补充信息，保留操作原始错误（包括 CancellationException）。 */
internal suspend fun cleanupAfterFailure(error: Throwable, vararg actions: suspend () -> Unit) {
    try {
        cleanupResources(*actions)
    } catch (cleanupError: Throwable) {
        if (cleanupError !== error) error.addSuppressed(cleanupError)
    }
}

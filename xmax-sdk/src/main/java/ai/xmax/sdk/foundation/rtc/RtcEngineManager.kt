package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.ErrorMessageFormatter
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/**
 * 表示对进程级 RTC Engine 的独占使用权。
 */
internal data class RtcEngineLease(
    val id: UUID = UUID.randomUUID(),
    val engine: RtcPlatform,
)

/**
 * 管理声网 RTC Engine 的进程级独占生命周期。
 */
internal open class RtcEngineManager internal constructor(
    private val makeEngine: () -> RtcPlatform?,
    private val destroyEngine: () -> Unit,
) {
    private val leaseMutex = Mutex()
    private val stateLock = Any()
    private var activeLease: RtcEngineLease? = null
    private var destructionFailure: Throwable? = null

    internal constructor(
        context: Context,
        appId: String = DEFAULT_APP_ID,
    ) : this(
        makeEngine = { createAgoraRtcEngine(context.applicationContext, appId) },
        destroyEngine = ::destroyAgoraRtcEngine,
    )

    internal open suspend fun acquire(): RtcEngineLease {
        leaseMutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { destructionFailure }?.let {
                throw rtcError("RTC Engine is unavailable after a failed native destruction", it)
            }
            val engine = makeEngine() ?: throw rtcError("Failed to create RTC Engine")
            val lease = RtcEngineLease(engine = engine)
            synchronized(stateLock) {
                activeLease = lease
            }
            currentCoroutineContext().ensureActive()
            return lease
        } catch (error: Throwable) {
            val lease = synchronized(stateLock) {
                activeLease.also { activeLease = null }
            }
            try {
                if (lease != null) destroyEngine()
            } catch (cleanupError: Throwable) {
                synchronized(stateLock) { destructionFailure = cleanupError }
                if (cleanupError !== error) error.addSuppressed(cleanupError)
            } finally {
                leaseMutex.unlock()
            }
            throw when (error) {
                is CancellationException,
                is XmaxError,
                -> error

                else -> rtcError("Failed to create RTC Engine: ${ErrorMessageFormatter.format(error)}", error)
            }
        }
    }

    internal open fun release(lease: RtcEngineLease) {
        val shouldRelease = synchronized(stateLock) {
            if (activeLease?.id != lease.id) {
                false
            } else {
                activeLease = null
                true
            }
        }
        if (!shouldRelease) return

        try {
            destroyEngine()
        } catch (error: Throwable) {
            synchronized(stateLock) { destructionFailure = error }
            throw rtcError("RTC Engine destruction failed; native reuse is disabled", error)
        } finally {
            leaseMutex.unlock()
        }
    }

    internal companion object {
        const val DEFAULT_APP_ID: String = "5588dfe2e7874bd08ea28f4968ec2d40"

        @Volatile
        private var sharedInstance: RtcEngineManager? = null

        fun shared(context: Context): RtcEngineManager =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: RtcEngineManager(context).also {
                    sharedInstance = it
                }
            }

        private fun rtcError(
            message: String,
            cause: Throwable? = null,
        ): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = message,
            cause = cause,
        )
    }
}

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** 表示对进程级 RTC Engine 的独占使用权。 */
internal data class RtcEngineLease(
    val id: UUID = UUID.randomUUID(),
    val engine: RtcPlatformEngine,
)

/** 管理火山 RTC Engine 的进程级独占生命周期。 */
internal open class RtcEngineManager internal constructor(
    private val makeEngine: () -> RtcPlatformEngine?,
    private val destroyEngine: () -> Unit,
) {
    private val leaseMutex = Mutex()
    private val stateLock = Any()
    private var activeLease: RtcEngineLease? = null

    internal constructor(
        context: Context,
        appId: String = DEFAULT_APP_ID,
    ) : this(
        makeEngine = { createVolcRtcEngine(context.applicationContext, appId) },
        destroyEngine = ::destroyVolcRtcEngine,
    )

    internal open suspend fun acquire(): RtcEngineLease {
        leaseMutex.lock()
        try {
            currentCoroutineContext().ensureActive()
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
            if (lease != null) {
                destroyEngine()
            }
            leaseMutex.unlock()
            throw when (error) {
                is CancellationException,
                is XmaxError,
                -> error

                else -> rtcError("Failed to create RTC Engine", error)
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
        } finally {
            leaseMutex.unlock()
        }
    }

    internal companion object {
        const val DEFAULT_APP_ID: String = "69a177e226e9b90176a86b96"

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

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 提供基于火山引擎的 RTC 生命周期和房间连接能力。 */
internal class RtcManager(
    private val engineManager: RtcEngineManager,
    private val joinTimeoutMillis: Long = JOIN_TIMEOUT_MILLIS,
) : RtcManaging {
    private val lifecycleMutex = Mutex()
    private val stateLock = Any()
    private var engineLease: RtcEngineLease? = null
    private var activeRoom: RoomContext? = null
    private var pendingJoin: PendingJoin? = null

    internal constructor(context: Context) : this(
        engineManager = RtcEngineManager.shared(context.applicationContext),
    )

    override suspend fun initialize() {
        lifecycleMutex.withLock {
            if (synchronized(stateLock) { engineLease != null }) return

            val lease = try {
                engineManager.acquire()
            } catch (error: CancellationException) {
                throw cancelledError("RTC initialization")
            } catch (error: Throwable) {
                throw XmaxError.from(error)
            }
            synchronized(stateLock) {
                engineLease = lease
            }
        }
    }

    override suspend fun destroy() {
        lifecycleMutex.withLock {
            leaveRoomLocked()
            val lease = synchronized(stateLock) {
                engineLease.also { engineLease = null }
            }
            if (lease != null) {
                engineManager.release(lease)
            }
        }
    }

    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration) {
        validateVideoDimensions(
            width = configuration.width,
            height = configuration.height,
            frameRate = configuration.frameRate,
        )
        val engine = synchronized(stateLock) {
            engineLease?.engine
        } ?: throw rtcError("RTC Engine is not initialized")
        val result = try {
            engine.configureVideoEncoding(configuration)
        } catch (error: Throwable) {
            throw rtcOperationError("setVideoEncoderConfig", error)
        }
        if (result < 0) {
            throw rtcResultError("setVideoEncoderConfig", result)
        }
    }

    override suspend fun joinRoom(configuration: RoomJoinConfiguration) {
        val normalizedConfiguration = configuration.normalized()
        val pending = lifecycleMutex.withLock {
            beginJoin(normalizedConfiguration)
        }

        try {
            withTimeout(joinTimeoutMillis) {
                pending.result.await()
            }
        } catch (error: TimeoutCancellationException) {
            val timeoutError = XmaxError(
                code = XmaxErrorCode.TIMEOUT,
                message = "RTC join room timed out",
                cause = error,
            )
            cleanupPendingJoin(pending.id, timeoutError)
            throw timeoutError
        } catch (error: CancellationException) {
            val cancelledError = cancelledError("RTC join room")
            cleanupPendingJoin(pending.id, cancelledError)
            throw cancelledError
        } catch (error: Throwable) {
            throw XmaxError.from(error)
        }
    }

    override suspend fun leaveRoom() {
        lifecycleMutex.withLock {
            leaveRoomLocked()
        }
    }

    override fun sendRoomMessage(message: String) {
        if (message.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "RTC room message cannot be empty",
            )
        }
        val room = synchronized(stateLock) {
            activeRoom?.room
        } ?: throw rtcError("RTC room is not joined")
        val result = try {
            room.sendRoomMessage(message)
        } catch (error: Throwable) {
            throw rtcOperationError("sendRoomMessage", error)
        }
        if (result < 0L) {
            throw rtcResultError("sendRoomMessage", result)
        }
    }

    private fun beginJoin(configuration: RoomJoinConfiguration): PendingJoin {
        val lease = synchronized(stateLock) {
            engineLease
        } ?: throw rtcError("RTC Engine is not initialized")

        synchronized(stateLock) {
            if (activeRoom != null || pendingJoin != null) {
                throw rtcError("RTC room is already active")
            }
        }

        val room = lease.engine.createRoom(configuration.roomId)
            ?: throw rtcError("Failed to create RTC room")
        val context = RoomContext(configuration.roomId, room)
        val pending = PendingJoin(context = context)
        val listenerResult = try {
            room.setEventListener { roomId, joined, reason ->
                handleRoomState(pending.id, roomId, joined, reason)
            }
        } catch (error: Throwable) {
            room.destroy()
            throw rtcOperationError("setRTCRoomEventHandler", error)
        }
        if (listenerResult < 0) {
            room.destroy()
            throw rtcResultError("setRTCRoomEventHandler", listenerResult)
        }

        synchronized(stateLock) {
            pendingJoin = pending
        }
        val joinResult = try {
            room.join(configuration)
        } catch (error: Throwable) {
            val rtcError = rtcOperationError("joinRoom", error)
            cleanupPendingJoin(pending.id, rtcError)
            throw rtcError
        }
        if (joinResult < 0) {
            val error = rtcResultError("joinRoom", joinResult)
            cleanupPendingJoin(pending.id, error)
            throw error
        }
        return pending
    }

    private fun handleRoomState(
        pendingId: UUID,
        roomId: String,
        joined: Boolean,
        reason: String?,
    ) {
        val pending = synchronized(stateLock) {
            pendingJoin?.takeIf {
                it.id == pendingId && it.context.roomId == roomId
            }
        } ?: return

        if (joined) {
            val completed = synchronized(stateLock) {
                if (pendingJoin?.id != pendingId) {
                    false
                } else {
                    activeRoom = pending.context
                    pendingJoin = null
                    true
                }
            }
            if (completed) pending.result.complete(Unit)
            return
        }

        cleanupPendingJoin(
            pendingId,
            rtcError(
                buildString {
                    append("RTC join room failed")
                    reason?.trim()?.takeIf(String::isNotEmpty)?.let {
                        append(": ")
                        append(it)
                    }
                },
            ),
        )
    }

    private fun cleanupPendingJoin(
        pendingId: UUID,
        error: XmaxError,
    ) {
        val pending = synchronized(stateLock) {
            pendingJoin?.takeIf { it.id == pendingId }?.also {
                pendingJoin = null
            }
        } ?: return

        tearDownRoom(pending.context, leave = false)
        pending.result.completeExceptionally(error)
    }

    private fun leaveRoomLocked() {
        val resources = synchronized(stateLock) {
            RoomResources(
                active = activeRoom,
                pending = pendingJoin,
            ).also {
                activeRoom = null
                pendingJoin = null
            }
        }
        resources.pending?.result?.completeExceptionally(
            cancelledError("RTC join room"),
        )
        resources.pending?.context?.let { tearDownRoom(it, leave = false) }
        resources.active?.let { tearDownRoom(it, leave = true) }
    }

    private fun tearDownRoom(
        context: RoomContext,
        leave: Boolean,
    ) {
        try {
            if (leave) context.room.leave()
        } finally {
            context.room.destroy()
        }
    }

    private fun RoomJoinConfiguration.normalized(): RoomJoinConfiguration {
        val roomId = roomId.trim().requireValue("RTC room ID")
        val userId = userId.trim().requireValue("RTC user ID")
        val token = token.trim().requireValue("RTC room token")
        return RoomJoinConfiguration(roomId, userId, token)
    }

    private fun validateVideoDimensions(
        width: Int,
        height: Int,
        frameRate: Int,
    ) {
        if (width <= 0 || height <= 0 || frameRate <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "RTC video width and height must be positive even numbers, " +
                    "and frame rate must be greater than zero",
            )
        }
    }

    private fun String.requireValue(name: String): String = takeIf(String::isNotEmpty)
        ?: throw rtcError("$name cannot be empty")

    private data class RoomContext(
        val roomId: String,
        val room: RtcPlatformRoom,
    )

    private data class PendingJoin(
        val id: UUID = UUID.randomUUID(),
        val context: RoomContext,
        val result: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private data class RoomResources(
        val active: RoomContext?,
        val pending: PendingJoin?,
    )

    private companion object {
        const val JOIN_TIMEOUT_MILLIS: Long = 15_000L

        fun rtcError(message: String): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = message,
        )

        fun rtcResultError(operation: String, result: Number): XmaxError = rtcError(
            "RTC $operation failed: $result",
        )

        fun rtcOperationError(operation: String, cause: Throwable): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "RTC $operation failed: ${cause.message ?: cause.javaClass.simpleName}",
            cause = cause,
        )

        fun cancelledError(operation: String): XmaxError = XmaxError(
            code = XmaxErrorCode.CANCELLED,
            message = "$operation was cancelled",
        )
    }
}

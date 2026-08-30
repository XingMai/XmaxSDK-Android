package ai.xmax.sdk

import android.content.Context
import ai.xmax.sdk.foundation.storage.StorageManager
import ai.xmax.sdk.service.network.ApiService
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.service.storage.StorageService

/**
 * Root entry point for XmaxSDK.
 *
 * Constructing the client performs no network or media work.
 */
public class XmaxClient(
    public val configuration: XmaxConfiguration,
    context: Context? = null,
) {
    init {
        XmaxLogger.configure(configuration.loggerOptions)
    }

    private val applicationContext: Context? = context?.applicationContext
    private val apiService = ApiService(configuration.apiKey)

    /** Android-friendly overload with the context first. */
    public constructor(
        context: Context,
        configuration: XmaxConfiguration,
    ) : this(configuration, context)

    /** Creates a manager for uploading and downloading Xmax media files. */
    public fun createStorageManager(): XmaxStorageManaging {
        configuration.validate()
        val context = applicationContext ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Android Context is required to create a storage manager",
        )
        return XmaxStorageManager(
            StorageService(
                apiService = apiService,
                storageManager = StorageManager(context),
            ),
        )
    }

    /** Creates a realtime manager for local media input, preview, and generation. */
    public fun createRealtimeManager(
        options: RealtimeConfiguration,
    ): XmaxRealtimeManaging {
        val context = applicationContext ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Android Context is required to create a realtime manager",
        )
        return XmaxRealtimeManager(options, context, apiService)
    }

    /** Creates the platform media capability service. */
    public fun createMediaService(): MediaServicing = MediaService()
}

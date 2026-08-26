package ai.xmax.sdk

import android.content.Context
import ai.xmax.sdk.internal.network.ApiService
import ai.xmax.sdk.internal.storage.CosStorageProvider
import ai.xmax.sdk.internal.storage.StorageService

/**
 * Root entry point for XmaxSDK.
 *
 * Constructing the client performs no network or media work.
 */
public class XmaxClient(
    public val configuration: XmaxConfiguration,
    context: Context? = null,
) {
    private val applicationContext: Context? = context?.applicationContext
    private val apiService = ApiService(configuration.apiKey)

    /** Android-friendly overload with the context first. */
    public constructor(
        context: Context,
        configuration: XmaxConfiguration,
    ) : this(configuration, context)

    /** Creates a manager for uploading and downloading Xmax media files. */
    public fun createStorageManager(): XmaxStorageManager {
        configuration.validate()
        val context = applicationContext ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Android Context is required to create a storage manager",
        )
        return XmaxStorageManager(
            StorageService(
                apiService = apiService,
                storageProvider = CosStorageProvider(context),
            ),
        )
    }
}

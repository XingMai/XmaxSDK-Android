package ai.xmax.sdk

import android.content.Context
import ai.xmax.sdk.foundation.storage.StorageManager
import ai.xmax.sdk.service.network.ApiService
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.service.storage.StorageService

/**
 * SDK 入口，持有全局配置和供各业务服务共享的 API 客户端。
 *
 * 构造时设置日志选项并保存 Application Context，不发起网络请求或启动媒体采集。
 * 实时管理器的媒体、传输和渲染组件在首次操作时创建。
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

    /** 便于 Android 接入的 Context 优先构造方式。 */
    public constructor(
        context: Context,
        configuration: XmaxConfiguration,
    ) : this(configuration, context)

    /** 创建文件存储管理器；要求 API Key 非空且构造客户端时已提供 Context。 */
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

    /** 创建具有独立生命周期的实时管理器；构造客户端时必须提供 Context。 */
    public fun createRealtimeManager(
        options: RealtimeConfiguration,
    ): XmaxRealtimeManaging {
        val context = applicationContext ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Android Context is required to create a realtime manager",
        )
        return XmaxRealtimeManager(options, context, apiService)
    }

    /** 创建模型输入尺寸计算服务；计算尺寸不依赖 Context 或网络。 */
    public fun createMediaService(): MediaServicing = MediaService()
}

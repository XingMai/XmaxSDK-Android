//
//  XmaxClient.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
 *
 * @param configuration SDK 鉴权信息、服务环境和日志配置。
 * @param context Android 上下文，客户端仅保存 Application Context；未提供时不能创建依赖 Android 资源的管理器。
 */
public class XmaxClient(
    public val configuration: XmaxConfiguration,
    context: Context? = null,
) {
    init {
        XmaxLogger.configure(configuration.loggerOptions, configuration.environment)
    }

    /**
     * Android 运行环境。
     */
    private val applicationContext: Context? = context?.applicationContext

    /**
     * 共享 API 访问服务。
     */
    private val apiService = ApiService(
        apiKey = configuration.apiKey,
        baseUrl = configuration.environment.apiBaseUrl,
    )

    /**
     * 便于 Android 接入的 Context 优先构造方式。
     *
     * @param context Android 上下文，客户端仅保存 Application Context；未提供时不能创建依赖 Android 资源的管理器。
     * @param configuration SDK 鉴权信息、服务环境和日志配置。
     */
    public constructor(
        context: Context,
        configuration: XmaxConfiguration,
    ) : this(configuration, context)

    /**
     * 创建文件存储管理器；要求 API Key 非空且构造客户端时已提供 Context。
     *
     * @return 用于上传下载图片及视频的存储管理器。
     */
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

    /**
     * 创建具有独立生命周期的实时管理器；构造客户端时必须提供 Context。
     * 模型专用地址优先于环境地址，用于该管理器的创建会话、心跳和关闭会话请求。
     *
     * @param options 实时生成的模型与业务配置。
     * @return 具有独立媒体和连接生命周期的实时管理器。
     */
    public fun createRealtimeManager(
        options: RealtimeConfiguration,
    ): XmaxRealtimeManaging {
        val context = applicationContext ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Android Context is required to create a realtime manager",
        )
        val realtimeApiService = options.model.baseUrl?.let { baseUrl ->
            ApiService(apiKey = configuration.apiKey, baseUrl = baseUrl)
        } ?: apiService

        return XmaxRealtimeManager(options, context, realtimeApiService)
    }

    /**
     * 创建指定模型的输入尺寸计算服务；计算尺寸不依赖 Context 或网络。
     *
     * @param model 用于确定服务端能力、地址及输入规格的模型；默认值为 `RealtimeModel.X2_1_1_PREVIEW_CN`。
     * @return 使用指定模型规则的媒体尺寸服务。
     */
    @JvmOverloads
    public fun createMediaService(
        model: RealtimeModel = RealtimeModel.X2_1_1_PREVIEW_CN,
    ): MediaServicing = MediaService(model)
}

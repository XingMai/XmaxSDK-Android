package com.xmax.xlab

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.xmax.xlab.modules.xlfeed.XLabFeedAction
import com.xmax.xlab.modules.xlfeed.FeedScreen
import com.xmax.xlab.modules.xlrealtime.RealtimeScreen
import com.xmax.xlab.modules.xlrealtime.RealtimeSource
import com.xmax.xlab.modules.xlrealtime.RealtimeTrajectoryStyle
import com.xmax.xlab.modules.xlstorage.StorageScreen

private enum class XLabDestination {
    FEED,
    REALTIME,
    STORAGE,
}

private enum class RealtimeMediaKind {
    VIDEO,
    IMAGE,
}

@Composable
public fun XLabApp() {
    val context = LocalContext.current
    val apiKeyStore = remember { ApiKeyStore(context) }
    var apiKey by remember { mutableStateOf(apiKeyStore.load()) }
    var destination by rememberSaveable { mutableStateOf(XLabDestination.FEED) }
    var realtimeSourceKind by rememberSaveable { mutableStateOf(RealtimeMediaKind.VIDEO) }
    var realtimeMediaUri by rememberSaveable { mutableStateOf<String?>(null) }
    var realtimeTrajectoryStyle by rememberSaveable {
        mutableStateOf(RealtimeTrajectoryStyle.SDK_DEFAULT)
    }

    BackHandler(enabled = destination != XLabDestination.FEED) {
        destination = XLabDestination.FEED
    }

    AnimatedContent(
        targetState = destination,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val easing = FastOutSlowInEasing
            if (targetState != XLabDestination.FEED) {
                (slideInHorizontally(tween(340, easing = easing)) { width -> width } +
                    fadeIn(tween(durationMillis = 180, delayMillis = 80))) togetherWith
                    (slideOutHorizontally(tween(340, easing = easing)) { width -> -width / 5 } +
                        fadeOut(tween(180)))
            } else {
                (slideInHorizontally(tween(340, easing = easing)) { width -> -width / 5 } +
                    fadeIn(tween(durationMillis = 180, delayMillis = 80))) togetherWith
                    (slideOutHorizontally(tween(340, easing = easing)) { width -> width } +
                        fadeOut(tween(180)))
            }.using(SizeTransform(clip = true))
        },
        label = "XLab navigation",
    ) { currentDestination ->
        when (currentDestination) {
            XLabDestination.FEED -> FeedScreen(
                apiKey = apiKey,
                onApiKeyChange = {
                    apiKey = it
                    apiKeyStore.save(it)
                },
                onAction = { action ->
                    when (action) {
                        XLabFeedAction.API_KEYS -> openApiKeyApplicationPage(context)
                        XLabFeedAction.CAMERA -> {
                            realtimeTrajectoryStyle = RealtimeTrajectoryStyle.SDK_DEFAULT
                            realtimeMediaUri = null
                            destination = XLabDestination.REALTIME
                        }
                        XLabFeedAction.VIDEO -> {
                            realtimeTrajectoryStyle = RealtimeTrajectoryStyle.SDK_DEFAULT
                            realtimeSourceKind = RealtimeMediaKind.VIDEO
                            realtimeMediaUri = Uri.Builder()
                                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                                .authority(context.packageName)
                                .appendPath("raw")
                                .appendPath("realtime_debug_video")
                                .build()
                                .toString()
                            destination = XLabDestination.REALTIME
                        }
                        XLabFeedAction.IMAGE -> {
                            realtimeTrajectoryStyle = RealtimeTrajectoryStyle.SDK_DEFAULT
                            realtimeSourceKind = RealtimeMediaKind.IMAGE
                            realtimeMediaUri = Uri.Builder()
                                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                                .authority(context.packageName)
                                .appendPath("raw")
                                .appendPath("realtime_debug_cat")
                                .build()
                                .toString()
                            destination = XLabDestination.REALTIME
                        }
                        XLabFeedAction.TRAJECTORY -> {
                            realtimeTrajectoryStyle = RealtimeTrajectoryStyle.XLAB_CUSTOM
                            realtimeSourceKind = RealtimeMediaKind.IMAGE
                            realtimeMediaUri = Uri.Builder()
                                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                                .authority(context.packageName)
                                .appendPath("raw")
                                .appendPath("realtime_debug_cat")
                                .build()
                                .toString()
                            destination = XLabDestination.REALTIME
                        }
                        XLabFeedAction.STORAGE -> destination = XLabDestination.STORAGE
                    }
                },
            )
            XLabDestination.REALTIME -> RealtimeScreen(
                apiKey = apiKey,
                source = realtimeMediaUri?.let { uriValue ->
                    when (realtimeSourceKind) {
                        RealtimeMediaKind.VIDEO -> RealtimeSource.Video(Uri.parse(uriValue))
                        RealtimeMediaKind.IMAGE -> RealtimeSource.Image(Uri.parse(uriValue))
                    }
                } ?: RealtimeSource.Camera,
                trajectoryStyle = realtimeTrajectoryStyle,
                onBack = { destination = XLabDestination.FEED },
            )
            XLabDestination.STORAGE -> StorageScreen(
                apiKey = apiKey,
                onBack = { destination = XLabDestination.FEED },
            )
        }
    }
}

private fun openApiKeyApplicationPage(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(XMAX_API_KEYS_URL))
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "无法打开申请页面，请稍后重试", Toast.LENGTH_SHORT).show()
        }
}

private const val XMAX_API_KEYS_URL = "https://platform.xmaxai.com/api-keys"

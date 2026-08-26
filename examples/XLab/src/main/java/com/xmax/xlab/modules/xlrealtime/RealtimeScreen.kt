package com.xmax.xlab.modules.xlrealtime

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

public sealed interface RealtimeSource {
    public data object Camera : RealtimeSource

    public data class Video(public val uri: Uri) : RealtimeSource

    public data class Image(public val uri: Uri) : RealtimeSource
}

@Composable
public fun RealtimeScreen(source: RealtimeSource) {
    key(source) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070A0F)),
        )
    }
}

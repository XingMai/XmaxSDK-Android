package com.xmax.xlab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xmax.xlab.modules.xlfeed.XLabFeedAction
import com.xmax.xlab.modules.xlfeed.XLabFeedScreen

@Composable
public fun XLabApp() {
    var apiKey by remember { mutableStateOf("") }

    XLabFeedScreen(
        apiKey = apiKey,
        onApiKeyChange = { apiKey = it },
        onAction = { _: XLabFeedAction ->
            // UI-only phase. Feature navigation is connected during implementation.
        },
    )
}

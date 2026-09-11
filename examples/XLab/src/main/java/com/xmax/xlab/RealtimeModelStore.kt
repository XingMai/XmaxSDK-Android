package com.xmax.xlab

import android.content.Context
import androidx.core.content.edit
import ai.xmax.sdk.RealtimeModel

/** 持久化 XLab 首页选择的实时模型。 */
internal class RealtimeModelStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): RealtimeModel {
        val storedModelId = preferences.getString(MODEL_KEY, null)
        return RealtimeModel.entries.firstOrNull { it.id == storedModelId }
            ?: RealtimeModel.X2_0
    }

    fun save(model: RealtimeModel) {
        preferences.edit {
            putString(MODEL_KEY, model.id)
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "xlab_preferences"
        private const val MODEL_KEY = "realtime_model"
    }
}

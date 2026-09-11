package com.xmax.xlab

import android.content.Context
import android.content.res.Configuration
import android.icu.util.ULocale
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import ai.xmax.sdk.XmaxEnvironment
import java.util.Locale

/** XLab 支持的界面语言。 */
internal enum class XLabLanguage(val storageValue: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("zh-Hans"),
    ENGLISH("en"),
}

/** 持久化 XLab 的界面语言选择。 */
internal class XLabLanguageStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): XLabLanguage {
        val storedValue = preferences.getString(LANGUAGE_KEY, null)
        return XLabLanguage.entries.firstOrNull { it.storageValue == storedValue }
            ?: XLabLanguage.SYSTEM
    }

    fun save(language: XLabLanguage) {
        preferences.edit {
            putString(LANGUAGE_KEY, language.storageValue)
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "xlab_preferences"
        private const val LANGUAGE_KEY = "language"
    }
}

/** 按 XLab 当前选择的语言读取字符串资源。 */
@Composable
internal fun xLabStringResource(
    @StringRes id: Int,
    language: XLabLanguage,
    vararg formatArgs: Any,
): String {
    val currentConfiguration = LocalConfiguration.current
    val context = LocalContext.current
    val localizedContext = when (language) {
        XLabLanguage.SYSTEM -> context
        XLabLanguage.SIMPLIFIED_CHINESE -> {
            context.localized(currentConfiguration, Locale.forLanguageTag("zh-Hans"))
        }
        XLabLanguage.ENGLISH -> context.localized(currentConfiguration, Locale.ENGLISH)
    }
    return localizedContext.resources.getString(id, *formatArgs)
}

/** 按最终显示语言选择与 iOS XLab 相同的服务环境。 */
@Composable
internal fun xLabEnvironment(language: XLabLanguage): XmaxEnvironment {
    val systemLocale = LocalConfiguration.current.locales[0]
    val usesChinaEnvironment = when (language) {
        XLabLanguage.SYSTEM -> systemLocale.usesSimplifiedChinese()
        XLabLanguage.SIMPLIFIED_CHINESE -> true
        XLabLanguage.ENGLISH -> false
    }
    return if (usesChinaEnvironment) XmaxEnvironment.CHINA else XmaxEnvironment.GLOBAL
}

private fun Context.localized(currentConfiguration: Configuration, locale: Locale): Context {
    val configuration = Configuration(currentConfiguration)
    configuration.setLocale(locale)
    return createConfigurationContext(configuration)
}

private fun Locale.usesSimplifiedChinese(): Boolean {
    if (language != Locale.CHINESE.language) return false
    val likelyLocale = ULocale.addLikelySubtags(ULocale.forLocale(this))
    return likelyLocale.script == "Hans"
}

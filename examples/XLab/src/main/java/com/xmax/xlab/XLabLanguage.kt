package com.xmax.xlab

import android.content.Context
import android.content.res.Configuration
import android.icu.util.ULocale
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
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

internal val LocalXLabLanguage = compositionLocalOf { XLabLanguage.SYSTEM }

@Composable
internal fun xLabText(@StringRes id: Int, vararg formatArgs: Any): String =
    xLabStringResource(id, LocalXLabLanguage.current, *formatArgs)

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
    return xLabLocalizedContext(language).resources.getString(id, *formatArgs)
}

@Composable
internal fun xLabLocalizedContext(language: XLabLanguage): Context {
    val currentConfiguration = LocalConfiguration.current
    val context = LocalContext.current
    return remember(context, currentConfiguration, language) {
        when (language) {
            XLabLanguage.SYSTEM -> context
            XLabLanguage.SIMPLIFIED_CHINESE -> {
                context.localized(currentConfiguration, Locale.forLanguageTag("zh-Hans"))
            }
            XLabLanguage.ENGLISH -> context.localized(currentConfiguration, Locale.ENGLISH)
        }
    }
}

/** 英文界面不直接显示底层返回的中文异常。 */
internal fun xLabErrorText(message: String?, fallback: String, localizedContext: Context): String {
    val detail = message?.takeIf { it.isNotBlank() } ?: return fallback
    val isChineseUi = localizedContext.resources.configuration.locales[0].language == Locale.CHINESE.language
    return if (!isChineseUi && detail.any { it in '\u3400'..'\u9FFF' }) fallback else detail
}

/** 按最终显示语言选择与 iOS XLab 相同的服务环境。 */
@Composable
internal fun xLabUsesSimplifiedChinese(language: XLabLanguage): Boolean {
    val systemLocale = LocalConfiguration.current.locales[0]
    return when (language) {
        XLabLanguage.SYSTEM -> systemLocale.usesSimplifiedChinese()
        XLabLanguage.SIMPLIFIED_CHINESE -> true
        XLabLanguage.ENGLISH -> false
    }
}

/** 按最终显示语言选择与 iOS XLab 相同的服务环境。 */
@Composable
internal fun xLabEnvironment(language: XLabLanguage): XmaxEnvironment {
    return if (xLabUsesSimplifiedChinese(language)) XmaxEnvironment.CHINA else XmaxEnvironment.GLOBAL
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

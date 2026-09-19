package com.c0d3c.listene.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.c0d3c.listene.AppSettings

private val LightColorScheme = lightColorScheme(
    primary = AgentPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AgentPrimarySoft,
    onPrimaryContainer = AgentPrimaryDark,
    secondary = AgentInk,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3F4F6),
    onSecondaryContainer = Color(0xFF374151),
    tertiary = AgentAccent,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3F4F6),
    onTertiaryContainer = AgentInk,
    background = AgentVoid,
    onBackground = AgentInk,
    surface = AgentSurface,
    onSurface = AgentInk,
    surfaceVariant = AgentSurfaceSoft,
    onSurfaceVariant = AgentMuted,
    outline = Color(0xFFD1D5DB),
    outlineVariant = AgentLine,
    error = AgentError,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF1F1F2),
    onErrorContainer = AgentInk,
    inverseSurface = AgentInk,
    inverseOnSurface = Color(0xFFF8FAFC),
    surfaceTint = AgentPrimary
)

// 暗色：墨/纸反转的纯单色盘。primary(实心控件)变亮墨、其上文字用深墨，层级关系与浅色一致。
private val DarkColorScheme = darkColorScheme(
    primary = AgentInkNight,
    onPrimary = Color(0xFF15161B),
    primaryContainer = Color(0xFF23242B),
    onPrimaryContainer = AgentInkNight,
    secondary = AgentInkNight,
    onSecondary = Color(0xFF15161B),
    secondaryContainer = Color(0xFF23242B),
    onSecondaryContainer = Color(0xFFC9CDD4),
    tertiary = AgentInkNight,
    onTertiary = Color(0xFF15161B),
    tertiaryContainer = Color(0xFF23242B),
    onTertiaryContainer = AgentInkNight,
    background = AgentVoidNight,
    onBackground = AgentInkNight,
    surface = AgentSurfaceNight,
    onSurface = AgentInkNight,
    surfaceVariant = AgentSurfaceSoftNight,
    onSurfaceVariant = AgentMutedNight,
    outline = Color(0xFF3A3D45),
    outlineVariant = AgentLineNight,
    error = AgentInkNight,
    onError = Color(0xFF15161B),
    errorContainer = Color(0xFF23242B),
    onErrorContainer = AgentInkNight,
    inverseSurface = AgentInkNight,
    inverseOnSurface = Color(0xFF15161B),
    surfaceTint = AgentInkNight
)

// App 内外观状态：设置页改动即时全局生效；持久化交给 AppSettings。
object AppThemeState {
    val mode = mutableStateOf<String?>(null)

    fun ensureLoaded(context: Context) {
        if (mode.value == null) mode.value = AppSettings.getThemeMode(context)
    }

    fun set(context: Context, value: String) {
        AppSettings.setThemeMode(context, value)
        mode.value = value
    }
}

@Composable
fun ListenETheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(context) { AppThemeState.ensureLoaded(context) }
    val mode = AppThemeState.mode.value ?: AppSettings.THEME_SYSTEM
    val dark = when (mode) {
        AppSettings.THEME_DARK -> true
        AppSettings.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColorScheme else LightColorScheme,
        typography = Typography
    ) {
        // 全局默认字体族设为 Inter：许多 Text 只写了 fontSize 未指定字体，靠这里统一切到 Inter，
        // 大标题角色（displaySmall/headlineMedium/titleLarge）在 Typography 里单独指定 Sora。
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Inter),
            content = content
        )
    }
}

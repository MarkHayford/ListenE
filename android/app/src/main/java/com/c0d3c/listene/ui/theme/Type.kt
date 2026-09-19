package com.c0d3c.listene.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.c0d3c.listene.R

// 变体字体（单文件多字重）：在 API 26+ 通过 wght 轴取到精确字重；低版本回退到文件默认字重（仅老设备字重不变化，仍是 Inter/Sora 字形）。
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight, axis: Int) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(axis))
)

// 正文/UI：Inter —— 现代、克制、屏幕可读性极佳。全局默认字体族（见 ListenETheme 里 LocalTextStyle 注入）。
internal val Inter = FontFamily(
    variableFont(R.font.inter_variable, FontWeight.Normal, 400),
    variableFont(R.font.inter_variable, FontWeight.Medium, 500),
    variableFont(R.font.inter_variable, FontWeight.SemiBold, 600),
    variableFont(R.font.inter_variable, FontWeight.Bold, 700),
    variableFont(R.font.inter_variable, FontWeight.Black, 900)
)

// 大标题：Sora —— 几何感 display 体，撑起「高级感」。Sora 最重到 ExtraBold(800)，Black 映射到 800。
private val Sora = FontFamily(
    variableFont(R.font.sora_variable, FontWeight.SemiBold, 600),
    variableFont(R.font.sora_variable, FontWeight.Bold, 700),
    variableFont(R.font.sora_variable, FontWeight.Black, 800)
)

val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Black,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    titleLarge = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp
    )
)

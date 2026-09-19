package com.c0d3c.listene.ui.theme

import androidx.compose.ui.graphics.Color

// 底色分层：画布用极浅冷白(#FAFAFB)，卡片保持纯白(surface)，靠明度差自然「浮起」——不叠投影也有层次。
val AgentVoid = Color(0xFFFAFAFB)
// 近黑墨色比纯 #111 更柔、更耐看；统一用于文字与主色，维持纯单色身份。
val AgentInk = Color(0xFF15161B)
val AgentMuted = Color(0xFF5A6472)
val AgentSurface = Color(0xFFFFFFFF)
val AgentSurfaceSoft = Color(0xFFF4F4F6)
val AgentLine = Color(0xFFE7E7EA)
val AgentPrimary = Color(0xFF15161B)
val AgentPrimaryDark = Color(0xFF060608)
val AgentPrimarySoft = Color(0xFFF1F1F2)
val AgentAccent = Color(0xFF15161B)
val AgentSignalBlue = Color(0xFF4B5563)
val AgentSuccess = Color(0xFF15161B)
val AgentError = Color(0xFF15161B)

// 暗色墨纸盘（跟随系统夜间模式）：墨/纸反转——深灰蓝纸 + 亮墨字，仍是纯单色身份。
val AgentVoidNight = Color(0xFF0F1014)        // 画布：最深
val AgentSurfaceNight = Color(0xFF17181D)     // 卡片：比画布亮一档，维持「浮起」分层
val AgentSurfaceSoftNight = Color(0xFF1F2026) // soft 面（pill/骨架底）
val AgentInkNight = Color(0xFFE9EAEE)         // 亮墨：正文与主色
val AgentMutedNight = Color(0xFF9AA1AC)
val AgentLineNight = Color(0xFF282A31)

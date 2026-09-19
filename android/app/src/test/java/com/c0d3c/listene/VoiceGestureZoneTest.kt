package com.c0d3c.listene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

// 按住说话·上滑双区落点判定（纯函数）：验证「取消发送 / 语音转文字」左右分区 + 未升起=直接发 + 兜底。
class VoiceGestureZoneTest {
    // 两区并排在输入栏上方：cancel 在左、convert 在右，下沿 y=100。
    private val cancel = Rect(left = 0f, top = 40f, right = 100f, bottom = 100f)
    private val convert = Rect(left = 120f, top = 40f, right = 220f, bottom = 100f)
    private val slack = 28f

    @Test
    fun fingerStillLowMeansSend() {
        // 手指仍在按钮附近（未升入区域带，y 远低于下沿+松弛）→ 直接发送。
        val zone = voiceZoneForPointer(Offset(200f, 400f), cancel, convert, slack)
        assertEquals(VoiceGestureZone.SEND, zone)
    }

    @Test
    fun risenLeftHalfMeansCancel() {
        // 升入区域带后按 x 就近：偏左 → 取消发送。
        val zone = voiceZoneForPointer(Offset(30f, 70f), cancel, convert, slack)
        assertEquals(VoiceGestureZone.CANCEL, zone)
    }

    @Test
    fun risenRightHalfMeansConvert() {
        // 升入区域带后偏右 → 语音转文字。
        val zone = voiceZoneForPointer(Offset(190f, 70f), cancel, convert, slack)
        assertEquals(VoiceGestureZone.CONVERT, zone)
    }

    @Test
    fun withinSlackBelowBottomStillArmsByNearestCenter() {
        // 下沿(100)之下但在松弛范围内(<=128)：已进入选择，按就近中心划分（此处偏右→转文字）。
        val zone = voiceZoneForPointer(Offset(190f, 120f), cancel, convert, slack)
        assertEquals(VoiceGestureZone.CONVERT, zone)
    }

    @Test
    fun justBelowSlackLineIsSend() {
        // 刚好超过松弛线（>128）→ 仍算未升起，直接发送。
        val zone = voiceZoneForPointer(Offset(190f, 129f), cancel, convert, slack)
        assertEquals(VoiceGestureZone.SEND, zone)
    }

    @Test
    fun nullOrMissingBoundsFallBackToSend() {
        assertEquals(VoiceGestureZone.SEND, voiceZoneForPointer(null, cancel, convert, slack))
        assertEquals(VoiceGestureZone.SEND, voiceZoneForPointer(Offset(30f, 70f), null, convert, slack))
        assertEquals(VoiceGestureZone.SEND, voiceZoneForPointer(Offset(30f, 70f), cancel, null, slack))
    }
}

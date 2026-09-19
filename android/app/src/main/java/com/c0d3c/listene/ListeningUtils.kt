package com.c0d3c.listene

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTime(ms: Int): String {
    val s = (ms / 1000) % 60
    val m = (ms / 60000) % 60
    return String.format(Locale.CHINA, "%d:%02d", m, s)
}

fun optionLabel(i: Int) = ('A'.code + i).toChar() + "."

fun formatHistoryTime(ms: Long): String =
    SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(ms))

fun <T> List<T>.countIndexed(predicate: (Int, T) -> Boolean): Int {
    var count = 0
    for (i in indices) {
        if (predicate(i, this[i])) count++
    }
    return count
}

package com.pluxy.server.library

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** EXIF without an offset is a camera's wall-clock time: use UTC consistently with the PC index. */
internal fun photoDateMs(date: String?, offset: String?): Long? {
    if (date == null || !Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(date)) return null
    val zone = if (offset != null && Regex("[+-](?:0\\d|1[0-4]):[0-5]\\d").matches(offset)) {
        TimeZone.getTimeZone("GMT$offset")
    } else TimeZone.getTimeZone("UTC")
    val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).apply {
        isLenient = false
        timeZone = zone
    }
    val position = ParsePosition(0)
    return format.parse(date, position)?.time?.takeIf { position.index == date.length && it > 0 }
}
